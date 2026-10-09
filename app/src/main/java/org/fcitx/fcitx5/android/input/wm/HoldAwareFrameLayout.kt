/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * FrameLayout that reports finger-up even when the child under the finger
 * was swapped mid-press (stale touch target): it always sees the event
 * because it stays attached.
 */
package org.fcitx.fcitx5.android.input.wm

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

class HoldAwareFrameLayout(ctx: Context) : FrameLayout(ctx) {

    /** Last pointer id seen on ACTION_DOWN. */
    var lastDownPointerId: Int = -1
        private set

    /**
     * When non-null, only a lift of this pointer fires [onRelease].
     * Null = any pointer lift fires (default).
     */
    var releasePointerId: Int? = null

    var onRelease: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastDownPointerId = ev.getPointerId(0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val lifted = try {
                    ev.getPointerId(ev.actionIndex)
                } catch (_: Exception) {
                    -1
                }
                val expected = releasePointerId
                if (expected == null || lifted == expected) {
                    try {
                        onRelease?.invoke()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
