/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Voice dictation entry point inside the input view scope.
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.CapabilityFlag
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.wm.HoldAwareFrameLayout
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.UniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import splitties.bitflags.hasFlag
import timber.log.Timber

class VoiceComponent :
    UniqueComponent<VoiceComponent>(), Dependent, InputBroadcastReceiver,
    ManagedHandler by managedHandler() {

    private val context by manager.context()
    private val service by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()

    private val prefs = AppPrefs.getInstance().voice

    private var passwordField = false

    /** Active session, if any. Owned here so it outlives the window (async final commit). */
    @Volatile
    var currentSession: VoiceSession? = null
        private set

    /** Space-key recording visual state (hold-to-talk only). */
    private var spacePulse: ValueAnimator? = null
    private var spaceOriginalText: String? = null

    /**
     * Session started by space long-press (hold-to-talk). Only this one may
     * be finished by the space-key Up event; toolbar sessions are tap-to-stop.
     */
    @Volatile
    private var spaceHoldSession: VoiceSession? = null

    fun canShowVoiceButton(): Boolean {
        if (!prefs.enabled.getValue()) return false
        if (!prefs.showVoiceButton.getValue()) return false
        if (passwordField) return false
        return true
    }

    /** Preview sink for hold-to-talk: realtime text into the editor composing span. */
    private val holdPreviewListener = object : VoiceSession.UiListener {
        override fun onPartial(text: String) {
            try {
                service.setVoicePreview(text)
            } catch (e: Exception) {
                Timber.w("voice preview failed: $e")
            }
        }

        override fun onVolume(level: Float) {}

        override fun onStateChanged(state: VoiceSession.State) {}

        override fun onError(message: String) {
            try {
                service.setVoicePreview("")
            } catch (_: Exception) {
            }
            val res = when {
                message.contains("authentication_failed") -> R.string.voice_error_auth
                message.contains("no_permission") -> R.string.voice_error_no_permission
                message == "connect_failed" -> R.string.voice_error_network
                else -> R.string.voice_error_network
            }
            try {
                Toast.makeText(context, context.getString(res), Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
        }
    }

    /** Toolbar / space long-press entry. Creates the session, attaches the window. */
    fun startVoiceInput(holdToTalk: Boolean = false) {
        if (!prefs.enabled.getValue()) {
            Toast.makeText(context, R.string.voice_error_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        if (passwordField) {
            // never hijack password fields with voice preview
            return
        }
        val apiKey = prefs.apiKey.getValue().trim()
        val endpoint = prefs.websocketEndpoint.getValue().trim()
        if (apiKey.isBlank() || endpoint.isBlank()) {
            Toast.makeText(context, R.string.voice_error_not_configured, Toast.LENGTH_LONG).show()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, R.string.voice_error_no_permission, Toast.LENGTH_LONG).show()
            return
        }
        val existing = currentSession
        if (existing != null) {
            // re-show the window for the ongoing session
            if (holdToTalk) {
                spaceHoldSession = existing
                setSpaceRecording(true)
            }
            if (!holdToTalk) windowManager.attachWindow(VoiceInputWindow())
            return
        }
        val model = prefs.model.getValue().trim()
            .ifEmpty { "qwen-audio-3.0-asr-flash-streaming" }
        val session = VoiceSession(context, service, apiKey, endpoint, model)
        session.onDone = {
            if (currentSession === session) currentSession = null
            if (spaceHoldSession === session) spaceHoldSession = null
            setSpaceRecording(false)
        }
        currentSession = session
        // both entries now own the composing span: drop any fcitx preedit first,
        // otherwise pinyin preedit and voice preview fight over it ("错乱")
        service.postFcitxJob {
            if (!isEmpty()) reset()
        }
        if (holdToTalk) {
            // candidate-bar mode: NO window swap (touch stays intact),
            // realtime partial goes to the editor composing span
            spaceHoldSession = session
            session.listener = holdPreviewListener
            setSpaceRecording(true)
            session.start()
            return
        }
        // The keyboard view is detached mid-press when the voice window
        // attaches, orphaning the touch: listen for release at the container,
        // which stays attached. Capture the holding pointer (the space DOWN
        // is the most recent one at this point, same main thread).
        (windowManager.view as? HoldAwareFrameLayout)?.let { frame ->
            frame.onRelease = { finishSpaceHold() }
        }
        // attach first: onAttached (listener wiring) runs synchronously inside
        windowManager.attachWindow(VoiceInputWindow())
        session.start()
    }

    /**
     * Space-key Up event: finish ONLY a space-hold session (release-to-commit).
     * No-op for taps/swipes/toolbar sessions.
     */
    fun finishSpaceHold() {
        val s = spaceHoldSession ?: return
        spaceHoldSession = null
        (windowManager.view as? HoldAwareFrameLayout)?.releasePointerId = null
        setSpaceRecording(false)
        Timber.d("voice space-hold released, finishing session")
        try {
            s.finish()
        } catch (e: Exception) {
            Timber.w("voice space-hold finish failed: $e")
        }
        try {
            windowManager.attachWindow(KeyboardWindow)
        } catch (e: Exception) {
            Timber.w("voice space-hold goBack failed: $e")
        }
    }

    fun stopVoiceInput() {
        windowManager.attachWindow(KeyboardWindow)
    }

    /**
     * Hold-to-talk visual: enlarge the space key with a breathing pulse and
     * swap its label to "录音中，松手上屏" so the finger knows it's recording.
     * Main-thread only (callers are on main; onDone re-posts via view.post).
     */
    private fun setSpaceRecording(active: Boolean) {
        try {
            windowManager.view.post {
                val space = try {
                    windowManager.view.findViewById<View>(R.id.button_space)
                        ?: windowManager.view.findViewById(R.id.button_mini_space)
                } catch (_: Exception) {
                    null
                } ?: return@post
                if (active) {
                    try {
                        if (space is TextKeyView && spaceOriginalText == null) {
                            spaceOriginalText = space.mainText.text?.toString()
                            space.mainText.text = context.getString(R.string.voice_recording_hold)
                        }
                    } catch (_: Exception) {
                    }
                    try {
                        space.isPressed = true
                        space.animate().cancel()
                        space.scaleX = 1.06f
                        space.scaleY = 1.12f
                    } catch (_: Exception) {
                    }
                    try {
                        spacePulse?.cancel()
                    } catch (_: Exception) {
                    }
                    try {
                        val pulse = ValueAnimator.ofFloat(1.04f, 1.1f).apply {
                            duration = 480
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                            interpolator = AccelerateDecelerateInterpolator()
                            addUpdateListener { anim ->
                                val v = anim.animatedValue as Float
                                try {
                                    space.scaleX = v
                                    space.scaleY = v + 0.06f
                                } catch (_: Exception) {
                                }
                            }
                        }
                        spacePulse = pulse
                        pulse.start()
                    } catch (e: Exception) {
                        Timber.w("voice space pulse failed: $e")
                    }
                } else {
                    try {
                        spacePulse?.cancel()
                    } catch (_: Exception) {
                    }
                    spacePulse = null
                    try {
                        space.animate().cancel()
                        space.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        space.isPressed = false
                    } catch (_: Exception) {
                        try {
                            space.scaleX = 1f
                            space.scaleY = 1f
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        val orig = spaceOriginalText
                        if (space is TextKeyView && orig != null) {
                            space.mainText.text = orig
                        }
                    } catch (_: Exception) {
                    }
                    spaceOriginalText = null
                }
            }
        } catch (e: Exception) {
            Timber.w("voice space visual failed: $e")
        }
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo, capFlags: org.fcitx.fcitx5.android.core.CapabilityFlags) {
        passwordField = capFlags.has(CapabilityFlag.Password)
    }
}
