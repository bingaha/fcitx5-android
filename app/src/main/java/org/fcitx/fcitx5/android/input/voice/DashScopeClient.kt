/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Voice dictation via Alibaba DashScope (funasr-protocol duplex WebSocket).
 * Ported from VocoType-linux src/workers/bailian/vocotype_bailian_worker.cpp
 */
package org.fcitx.fcitx5.android.input.voice

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal DashScope realtime ASR client (funasr-protocol).
 *
 * Flow: connect (Bearer key) -> send run-task -> wait task-started ->
 * stream binary PCM frames -> callbacks onPartial -> send finish-task ->
 * wait task-finished -> onFinal.
 */
class DashScopeClient(
    private val endpoint: String,
    private val apiKey: String,
    private val model: String,
    private val sampleRate: Int = 16000
) {

    interface Listener {
        fun onTaskStarted()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var socket: WebSocket? = null
    private val sessionId: String = UUID.randomUUID().toString().replace("-", "")
    private val started = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    @Volatile
    var listener: Listener? = null

    private val finalTextBuilder = StringBuilder()
    /** Finalized sentences (deduped via overlap merge). */
    private val confirmed = StringBuilder()
    @Volatile
    private var current: String = ""

    /**
     * Interim (replaceable) update: the server may revise earlier text
     * arbitrarily (e.g. "我们" -> "我能自己"), so always REPLACE, never merge.
     */
    private fun onInterim(text: String) {
        current = text
    }

    /**
     * Sentence-end / final update (VocoType semantics, see
     * handle_funasr_transcription): the text is the finalized form of the
     * current sentence — append it and DISCARD the interim. Never keep both
     * (that duplicates), never merge (interim is often revised).
     */
    private fun onSentenceEnd(text: String) {
        if (text.isNotEmpty()) confirmed.append(text)
        current = ""
    }

    fun accumulatedText(): String = synchronized(this) {
        confirmed.toString() + current
    }

    /** Last known text (for timeout fallback). */
    fun lastText(): String = accumulatedText()

    fun connect(listener: Listener, timeoutMs: Long = 10000): Boolean {
        this.listener = listener
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val inner = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                if (!sendRunTask()) {
                    listener.onError("run-task failed")
                    latch.countDown()
                    return
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    handleServerEvent(JSONObject(text), listener, latch, ok)
                } catch (e: Exception) {
                    Timber.w("dashscope: bad json $e")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: -1
                Timber.w("dashscope ws failure code=$code err=${t.message}")
                if (!started.get()) {
                    val msg = when {
                        code == 401 || code == 403 ||
                            t.message?.contains("401") == true ||
                            t.message?.contains("403") == true -> "authentication_failed"
                        else -> "remote_disconnected: ${t.message}"
                    }
                    listener.onError(msg)
                    latch.countDown()
                } else if (!finished.get()) {
                    listener.onError("remote_disconnected: ${t.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!started.get()) {
                    listener.onError("remote_disconnected: $reason")
                    latch.countDown()
                }
            }
        }
        client.newWebSocket(request, inner)
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) && ok.get()
        } catch (e: InterruptedException) {
            false
        }
    }

    private fun sendRunTask(): Boolean {
        val ws = socket ?: return false
        return try {
            val parameters = JSONObject()
                .put("format", "pcm")
                .put("sample_rate", sampleRate)
            val payload = JSONObject()
                .put("task_group", "audio")
                .put("task", "asr")
                .put("function", "recognition")
                .put("model", model)
                .put("parameters", parameters)
                .put("input", JSONObject())
            val msg = JSONObject()
                .put("header", JSONObject()
                    .put("action", "run-task")
                    .put("task_id", sessionId)
                    .put("streaming", "duplex"))
                .put("payload", payload)
            ws.send(msg.toString())
        } catch (e: Exception) {
            Timber.w("dashscope send run-task failed: $e")
            false
        }
    }

    fun sendPcm(data: ByteArray, offset: Int = 0, size: Int = data.size): Boolean {
        val ws = socket ?: return false
        return try {
            ws.send(data.toByteString(offset, size))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Fire-and-forget finish-task. The final result arrives asynchronously
     * via [Listener.onFinal] (VocoType-style: never block UI on it).
     */
    fun sendFinish(): Boolean {
        val ws = socket ?: return false
        return try {
            val msg = JSONObject()
                .put("header", JSONObject()
                    .put("action", "finish-task")
                    .put("task_id", sessionId)
                    .put("streaming", "duplex"))
                .put("payload", JSONObject().put("input", JSONObject()))
            ws.send(msg.toString())
        } catch (e: Exception) {
            Timber.w("dashscope send finish-task failed: $e")
            false
        }
    }

    /** Send finish-task and wait for the final result. Returns final text or null on timeout/error. */
    fun finishAndWait(timeoutMs: Long = 15000): String? {
        if (finished.get()) return accumulatedText().ifEmpty { null }
        if (socket == null) return accumulatedText().ifEmpty { null }
        val latch = CountDownLatch(1)
        val prev = listener
        // wrap listener so finish wakes the latch
        listener = object : Listener {
            override fun onTaskStarted() = prev?.onTaskStarted() ?: Unit
            override fun onPartial(text: String) {
                prev?.onPartial(text)
            }
            override fun onFinal(text: String) {
                prev?.onFinal(text)
                latch.countDown()
            }
            override fun onError(message: String) {
                prev?.onError(message)
                latch.countDown()
            }
        }
        if (!sendFinish()) return null
        return try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            finished.set(true)
            accumulatedText().ifEmpty { null }
        } catch (e: InterruptedException) {
            null
        }
    }

    fun close() {
        try {
            socket?.close(1000, "done")
        } catch (_: Exception) {
        }
        socket = null
        try {
            client.dispatcher.executorService.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun handleServerEvent(
        json: JSONObject,
        listener: Listener,
        startedLatch: CountDownLatch,
        startedOk: AtomicBoolean
    ) {
        // qwen3-style realtime events (kept for compat)
        val type = json.optString("type", "")
        if (type.startsWith("conversation.item.input_audio_transcription")) {
            val text = extractQwenText(json) ?: return
            when (type) {
                "conversation.item.input_audio_transcription.delta" -> {
                    synchronized(this) { current += text }
                    listener.onPartial(accumulatedText())
                }
                "conversation.item.input_audio_transcription.text" -> {
                    synchronized(this) { onInterim(text) }
                    listener.onPartial(accumulatedText())
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    synchronized(this) { onSentenceEnd(text) }
                    listener.onPartial(accumulatedText())
                }
            }
            return
        }
        val header = if (json.has("header")) json.optJSONObject("header") else null
        val event = header?.optString("event", "") ?: ""
        if (event == "task-started") {
            started.set(true)
            startedOk.set(true)
            listener.onTaskStarted()
            startedLatch.countDown()
            return
        }
        if (event == "task-finished" || event == "task-failed") {
            val text = extractFunasrText(json)
            finished.set(true)
            if (event == "task-failed") {
                val msg = json.optJSONObject("header")?.optString("error_message", "task failed")
                    ?: "task failed"
                listener.onError(msg ?: "task failed")
            } else {
                if (!text.isNullOrEmpty()) {
                    synchronized(this) { onSentenceEnd(text) }
                }
                listener.onFinal(accumulatedText())
            }
            return
        }
        if (event == "result-generated") {
            val text = extractFunasrText(json)
            val end = isSentenceEnd(json)
            if (!text.isNullOrEmpty()) {
                synchronized(this) {
                    if (end) onSentenceEnd(text) else onInterim(text)
                }
                listener.onPartial(accumulatedText())
            }
            return
        }
        // fallback: some gateways emit sentence directly
        val fallback = extractFunasrText(json)
        if (!fallback.isNullOrEmpty() && (event.isEmpty())) {
            val end = isSentenceEnd(json)
            synchronized(this) {
                if (end) onSentenceEnd(fallback) else onInterim(fallback)
            }
            listener.onPartial(accumulatedText())
        }
    }

    private fun extractFunasrText(json: JSONObject): String? {
        // payload.output.sentence.text (funasr-protocol)
        val payload = json.optJSONObject("payload") ?: json
        val output = payload.optJSONObject("output") ?: return payload.optString("text", null)
            ?.ifEmpty { null }
        val sentence = output.optJSONObject("sentence")
        if (sentence != null) {
            val t = sentence.optString("text", "")
            if (t.isNotEmpty()) return t
        }
        // other shapes: output.text / transcript
        val t = output.optString("text", "")
        if (t.isNotEmpty()) return t
        val tr = payload.optString("transcript", "")
        if (tr.isNotEmpty()) return tr
        return null
    }

    private fun isSentenceEnd(json: JSONObject): Boolean {
        val payload = json.optJSONObject("payload") ?: return false
        val output = payload.optJSONObject("output") ?: return false
        val sentence = output.optJSONObject("sentence") ?: return false
        return sentence.optBoolean("sentence_end", false)
    }

    private fun extractQwenText(json: JSONObject): String? {
        val delta = json.optJSONObject("delta")
        val s = delta?.optString("transcript", null) ?: json.optString("transcript", null)
        if (!s.isNullOrEmpty()) return s
        val t = json.optString("text", "")
        return t.ifEmpty { null }
    }

    companion object {
        /** Connection-only probe: handshake + run-task, no audio. Returns null on success or error string. */
        fun probe(endpoint: String, apiKey: String, model: String): String? {
            if (apiKey.isEmpty()) return "missing_api_key"
            if (endpoint.isEmpty()) return "missing_websocket_endpoint"
            if (!endpoint.startsWith("wss://")) return "websocket_endpoint_must_use_wss"
            val c = DashScopeClient(endpoint, apiKey, model)
            var err: String? = null
            val ok = c.connect(object : Listener {
                override fun onTaskStarted() {}
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) {}
                override fun onError(message: String) {
                    err = message
                }
            })
            try {
                // immediately finish so no audio is ever sent
                c.finishAndWait(8000)
            } catch (_: Exception) {
            } finally {
                c.close()
            }
            if (!ok) return err ?: "remote_disconnected"
            return null
        }
    }
}
