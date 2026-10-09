/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * 16kHz mono 16-bit PCM recorder for realtime ASR.
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlin.math.sqrt

class VoiceAudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        /** 100ms per chunk: 1600 samples * 2 bytes, mirrors VocoType preview_chunk_samples */
        const val CHUNK_SAMPLES = 1600
        const val CHUNK_BYTES = CHUNK_SAMPLES * 2
    }

    interface Callback {
        fun onChunk(pcm: ByteArray, size: Int)
        fun onVolume(rms: Float)
        fun onError(message: String)
    }

    @Volatile
    private var recorder: AudioRecord? = null
    @Volatile
    private var running = false
    private var worker: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(callback: Callback): Boolean {
        if (!hasPermission()) {
            callback.onError("no_permission")
            return false
        }
        if (running) return true
        val minBuf = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        } catch (e: Exception) {
            callback.onError("audio_init_failed: ${e.message}")
            return false
        }
        if (minBuf <= 0) {
            callback.onError("audio_init_failed")
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuf * 2, CHUNK_BYTES * 4)
            )
        } catch (e: Exception) {
            callback.onError("audio_init_failed: ${e.message}")
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try {
                rec.release()
            } catch (_: Exception) {
            }
            callback.onError("audio_init_failed")
            return false
        }
        recorder = rec
        running = true
        worker = Thread({
            recordLoop(rec, callback)
        }, "voice-record").apply { isDaemon = true; start() }
        return true
    }

    private fun recordLoop(rec: AudioRecord, callback: Callback) {
        try {
            rec.startRecording()
        } catch (e: Exception) {
            callback.onError("audio_start_failed: ${e.message}")
            running = false
            return
        }
        // 20ms reads, accumulate into 100ms chunks before delivery
        val readBuf = ByteArray(640)
        val chunk = ByteArray(CHUNK_BYTES)
        var filled = 0
        while (running) {
            val n = try {
                rec.read(readBuf, 0, readBuf.size)
            } catch (e: Exception) {
                Timber.w("voice record read failed: $e")
                break
            }
            if (n <= 0) continue
            // volume hint (RMS over this read)
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < n) {
                val s = ((readBuf[i + 1].toInt() shl 8) or (readBuf[i].toInt() and 0xFF)).toShort().toInt()
                sum += (s * s).toDouble()
                count++
                i += 2
            }
            if (count > 0) {
                callback.onVolume(sqrt(sum / count).toFloat() / 32768f)
            }
            var off = 0
            while (off < n) {
                val take = minOf(n - off, CHUNK_BYTES - filled)
                System.arraycopy(readBuf, off, chunk, filled, take)
                filled += take
                off += take
                if (filled >= CHUNK_BYTES) {
                    callback.onChunk(chunk.copyOf(), CHUNK_BYTES)
                    filled = 0
                }
            }
        }
        // flush remainder (pad with zeros to keep alignment)
        if (filled > 0) {
            val last = ByteArray(CHUNK_BYTES)
            System.arraycopy(chunk, 0, last, 0, filled)
            callback.onChunk(last, CHUNK_BYTES)
        }
        try {
            rec.stop()
        } catch (_: Exception) {
        }
        try {
            rec.release()
        } catch (_: Exception) {
        }
        if (recorder === rec) recorder = null
    }

    fun stop() {
        running = false
        try {
            worker?.join(1000)
        } catch (_: Exception) {
        }
        worker = null
    }
}
