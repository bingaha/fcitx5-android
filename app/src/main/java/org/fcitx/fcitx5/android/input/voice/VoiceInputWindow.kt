/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * Voice dictation window: pure UI over the scope-owned VoiceSession.
 * Stop returns to the keyboard immediately; the final text commits async.
 */
package org.fcitx.fcitx5.android.input.voice

import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import timber.log.Timber

class VoiceInputWindow : InputWindow.ExtendedInputWindow<VoiceInputWindow>() {

    override val showTitle: Boolean = true
    override val title: String
        get() = try {
            context.getString(R.string.voice_dictation)
        } catch (_: Exception) {
            "Voice"
        }

    private val windowManager: InputWindowManager by manager.must()
    private val voice: VoiceComponent by manager.must()
    private val service by manager.inputMethodService()

    private var session: VoiceSession? = null

    private lateinit var rootView: View
    private lateinit var statusView: TextView
    private lateinit var partialView: TextView
    private lateinit var volumeBar: ProgressBar
    private lateinit var stopButton: Button
    private lateinit var cancelButton: Button
    private lateinit var draftBar: LinearLayout
    private lateinit var draftView: TextView

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private val uiListener = object : VoiceSession.UiListener {
        override fun onPartial(text: String) {
            if (::partialView.isInitialized) partialView.text = text
            // unified with hold-to-talk: realtime text also lives in the editor
            // composing span, so commit just finishes it (no duplication)
            try {
                service.setVoicePreview(text)
            } catch (e: Exception) {
                Timber.w("voice window preview failed: $e")
            }
        }

        override fun onVolume(level: Float) {
            if (::volumeBar.isInitialized) {
                volumeBar.progress = (level * 100).toInt().coerceIn(0, 100)
            }
        }

        override fun onStateChanged(state: VoiceSession.State) {
            if (!::statusView.isInitialized) return
            statusView.text = try {
                context.getString(
                    when (state) {
                        VoiceSession.State.CONNECTING -> R.string.voice_testing
                        VoiceSession.State.RECORDING -> R.string.voice_listening
                        VoiceSession.State.FINISHING -> R.string.please_wait
                        VoiceSession.State.DONE -> R.string.please_wait
                    }
                )
            } catch (_: Exception) {
                ""
            }
            val recording = state == VoiceSession.State.RECORDING
            stopButton.isEnabled = recording
            if (state == VoiceSession.State.DONE) goBack()
        }

        override fun onError(message: String) {
            val res = when {
                message.contains("authentication_failed") -> R.string.voice_error_auth
                message.contains("missing_") || message.contains("not_configured") ->
                    R.string.voice_error_not_configured
                message.contains("no_permission") -> R.string.voice_error_no_permission
                message == "connect_failed" -> R.string.voice_error_network
                else -> R.string.voice_error_network
            }
            try {
                val detail = if (message == "connect_failed" || message == "no_permission") "" else " ($message)"
                Toast.makeText(context, context.getString(res) + detail, Toast.LENGTH_LONG).show()
            } catch (_: Exception) {
            }
            goBack()
        }
    }

    override fun onCreateView(): View {
        val pad = dp(12)
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        // Hold-to-talk release: the space key view is detached the moment the
        // voice window attaches (mid-press), so the finger-up event lands HERE,
        // not on the space key. Do NOT consume (buttons still need their taps).
        layout.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP ||
                event.action == MotionEvent.ACTION_CANCEL
            ) {
                voice.finishSpaceHold()
            }
            false
        }
        statusView = TextView(context).apply {
            text = context.getString(R.string.voice_testing)
            textSize = 14f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        layout.addView(
            statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        volumeBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        layout.addView(
            volumeBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        partialView = TextView(context).apply {
            textSize = 18f
            minHeight = dp(48)
        }
        val scroll = ScrollView(context).apply { addView(partialView) }
        layout.addView(
            scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        stopButton = Button(context).apply {
            text = context.getString(R.string.voice_stop)
            isEnabled = false
            setOnClickListener {
                // async: session commits on final arrival; leave immediately
                try {
                    session?.finish()
                } catch (e: Exception) {
                    Timber.w("voice stop failed: $e")
                }
                goBack()
            }
        }
        cancelButton = Button(context).apply {
            text = context.getString(R.string.voice_cancel)
            setOnClickListener {
                try {
                    session?.cancel()
                } catch (e: Exception) {
                    Timber.w("voice cancel failed: $e")
                }
                goBack()
            }
        }
        val btnParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8); marginEnd = dp(8)
            }
        row.addView(stopButton, btnParams)
        row.addView(cancelButton, btnParams)
        layout.addView(row)
        // interruption draft: hidden unless a previous session stashed text
        draftBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        draftView = TextView(context).apply {
            textSize = 14f
        }
        val fillButton = Button(context).apply {
            text = context.getString(R.string.voice_draft_fill)
            setOnClickListener {
                val draft = VoiceSession.getDraft(context)
                if (draft.isNotBlank()) {
                    try {
                        service.commitText(draft)
                    } catch (e: Exception) {
                        Timber.w("voice draft commit failed: $e")
                    }
                    VoiceSession.clearDraft(context)
                }
                refreshDraftBar()
            }
        }
        val discardButton = Button(context).apply {
            text = context.getString(R.string.voice_draft_discard)
            setOnClickListener {
                VoiceSession.clearDraft(context)
                refreshDraftBar()
            }
        }
        val hintView = TextView(context).apply {
            text = context.getString(R.string.voice_draft_hint)
            textSize = 12f
        }
        draftBar.addView(
            hintView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        draftBar.addView(
            draftView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        draftBar.addView(
            fillButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        draftBar.addView(
            discardButton,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        layout.addView(
            draftBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rootView = layout
        return layout
    }

    private fun refreshDraftBar() {
        if (!::draftBar.isInitialized) return
        val draft = try {
            VoiceSession.getDraft(context)
        } catch (_: Exception) {
            ""
        }
        draftBar.visibility = if (draft.isBlank()) View.GONE else View.VISIBLE
        if (draft.isNotBlank()) draftView.text = draft.take(60)
    }

    override fun onAttached() {
        val s = voice.currentSession
        if (s == null) {
            goBack()
            return
        }
        session = s
        s.listener = uiListener
        // reflect current state immediately (session may already be recording)
        uiListener.onStateChanged(s.state)
        refreshDraftBar()
    }

    override fun onDetached() {
        val s = session
        session = null
        if (s != null) {
            if (s.listener === uiListener) s.listener = null
            // user backed out (title return) mid-recording: abort; a finishing
            // session is left alone so its async final still commits
            if (s.state == VoiceSession.State.RECORDING ||
                s.state == VoiceSession.State.CONNECTING
            ) {
                try {
                    s.cancel()
                } catch (e: Exception) {
                    Timber.w("voice detach cancel failed: $e")
                }
            }
        }
    }

    private fun goBack() {
        try {
            windowManager.attachWindow(KeyboardWindow)
        } catch (e: Exception) {
            Timber.w("voice goBack failed: $e")
        }
    }
}
