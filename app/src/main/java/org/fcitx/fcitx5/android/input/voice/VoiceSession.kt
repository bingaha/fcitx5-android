/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Scope-level voice session: owns recorder + DashScope streaming.
 *
 * VocoType-style async commit: stop() only sends finish-task and returns
 * immediately; the final text commits whenever Listener.onFinal arrives
 * (no fixed waits on the UI path). A timeout falls back to the last partial.
 */
package org.fcitx.fcitx5.android.input.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VoiceSession(
    private val appContext: Context,
    private val service: FcitxInputMethodService,
    apiKey: String,
    endpoint: String,
    model: String
) {

    enum class State { CONNECTING, RECORDING, FINISHING, DONE }

    interface UiListener {
        fun onPartial(text: String)
        fun onVolume(level: Float)
        fun onStateChanged(state: State)
        fun onError(message: String)
    }

    companion object {
        /** Fallback cap: commit last partial if server final is this late. */
        const val FINISH_TIMEOUT_MS = 8000L

        /** Max single session length (auto finish). */
        const val MAX_DURATION_MS = 60000L

        private const val DRAFT_KEY = "voice_draft"

        fun getDraft(ctx: Context): String =
            PreferenceManager.getDefaultSharedPreferences(ctx).getString(DRAFT_KEY, "") ?: ""

        fun clearDraft(ctx: Context) {
            PreferenceManager.getDefaultSharedPreferences(ctx).edit { remove(DRAFT_KEY) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val client = DashScopeClient(endpoint, apiKey, model)
    private val recorder = VoiceAudioRecorder(appContext)

    /** Set false the moment stop() runs: no PCM may be sent after finish-task. */
    private val sending = AtomicBoolean(true)
    private val done = AtomicBoolean(false)

    @Volatile
    var state: State = State.CONNECTING
        private set

    /** Latest partial text (for interruption draft). */
    @Volatile
    private var lastPartial: String = ""

    @Volatile
    var listener: UiListener? = null

    var onDone: (() -> Unit)? = null

    private fun setState(s: State) {
        state = s
        val l = listener
        if (l != null) main.post { l.onStateChanged(s) }
    }

    private fun postError(msg: String) {
        val l = listener
        if (l != null) main.post { l.onError(msg) }
    }

    fun start() {
        // hard cap: never record forever (e.g. keyboard hidden mid-hold with no UI)
        main.postDelayed({
            if (state == State.RECORDING) {
                Timber.w("voice max duration reached, auto finish")
                finish()
            }
        }, MAX_DURATION_MS)
        executor.execute {
            Timber.d("voice session starting")
            val connected = client.connect(object : DashScopeClient.Listener {
                override fun onTaskStarted() {
                    Timber.d("voice task started")
                    setState(State.RECORDING)
                }

                override fun onPartial(text: String) {
                    Timber.d("voice partial len=${text.length}")
                    lastPartial = text
                    val l = listener
                    if (l != null) main.post { l.onPartial(text) }
                }

                override fun onFinal(text: String) {
                    Timber.d("voice final len=${text.length}")
                    lastPartial = text
                    val l = listener
                    if (l != null) main.post { l.onPartial(text) }
                    // async commit: whenever it arrives, commit immediately
                    if (done.compareAndSet(false, true)) {
                        Timber.d("voice committing final now")
                        main.post { commitText(text) }
                        cleanup()
                        onDone?.invoke()
                    }
                }

                override fun onError(message: String) {
                    Timber.w("voice session error: $message")
                    if (done.compareAndSet(false, true)) {
                        saveDraft()
                        clearPreview()
                        postError(message)
                        cleanup()
                        onDone?.invoke()
                    }
                }
            })
            if (!connected) {
                Timber.w("voice connect failed")
                if (done.compareAndSet(false, true)) {
                    // UX (toast) is the listener's job; session stays UI-agnostic
                    clearPreview()
                    postError("connect_failed")
                    cleanup()
                    onDone?.invoke()
                }
                return@execute
            }
            Timber.d("voice connected, starting recorder")
            val ok = recorder.start(object : VoiceAudioRecorder.Callback {
                override fun onChunk(pcm: ByteArray, size: Int) {
                    if (sending.get()) client.sendPcm(pcm, 0, size)
                }

                override fun onVolume(rms: Float) {
                    val l = listener
                    if (l != null) main.post { l.onVolume(rms) }
                }

                override fun onError(message: String) {
                    if (message == "no_permission") {
                        if (done.compareAndSet(false, true)) {
                            postError(message)
                            cleanup()
                            onDone?.invoke()
                        }
                    }
                }
            })
            if (!ok && done.compareAndSet(false, true)) {
                cleanup()
                onDone?.invoke()
            }
        }
    }

    /**
     * User hit stop / lifted hold finger: cut audio, send finish-task,
     * return IMMEDIATELY (safe on touch/main thread: blocking work goes bg).
     * Commit happens in onFinal (or timeout fallback) on the main thread.
     */
    fun finish() {
        if (state != State.RECORDING) {
            // still connecting: cancel outright
            cancel()
            return
        }
        setState(State.FINISHING)
        sending.set(false)
        bg {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            client.sendFinish()
            Timber.d("voice finish-task sent, waiting async for final")
            try {
                Thread.sleep(FINISH_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                return@bg
            }
            if (done.compareAndSet(false, true)) {
                val fallback = client.lastText()
                Timber.w("voice finish timeout, fallback len=${fallback.length}")
                main.post { commitText(fallback) }
                cleanup()
                onDone?.invoke()
            }
        }
    }

    fun cancel() {
        if (!done.compareAndSet(false, true)) return
        Timber.d("voice session cancelled")
        saveDraft()
        clearPreview()
        sending.set(false)
        setState(State.DONE)
        bg {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            cleanup()
        }
        onDone?.invoke()
    }

    /** Run on bg executor; falls back to inline if it is already shut down. */
    private fun bg(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            block()
        }
    }

    /** Drop any composing preview so a cancelled/failed session leaves nothing stale. */
    private fun clearPreview() {
        main.post {
            try {
                service.setVoicePreview("")
            } catch (_: Exception) {
            }
        }
    }

    private fun commitText(text: String?) {        if (text.isNullOrBlank()) {
            Timber.d("voice nothing to commit")
            return
        }
        try {
            service.commitText(text.trim())
            Timber.d("voice committed ${text.trim().length} chars")
        } catch (e: Exception) {
            Timber.w("voice commit failed: $e")
        }
    }

    /**
     * Stash uncommitted text so an interruption (popup, keyboard hide,
     * error, cancel) doesn't lose everything. Committed sessions never
     * reach here with text (final path commits instead).
     */
    private fun saveDraft() {
        val text = lastPartial.trim()
        if (text.isEmpty()) return
        try {
            PreferenceManager.getDefaultSharedPreferences(appContext).edit {
                putString(DRAFT_KEY, text)
            }
            Timber.d("voice draft saved len=${text.length}")
        } catch (e: Exception) {
            Timber.w("voice draft save failed: $e")
        }
    }

    private fun cleanup() {
        try {
            client.close()
        } catch (_: Exception) {
        }
        setState(State.DONE)
        executor.shutdown()
    }
}
