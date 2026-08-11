package io.github.kunelab.kontroller.extraLibraries

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import io.github.kunelab.kontroller.listeners.GestureDetectListener

/**
 * Gives [GestureDetectListener] a look at the raw touch stream (it needs multi-pointer
 * events, which GestureDetector's own callbacks do not expose) before falling back to the
 * standard gesture detection.
 */
class CustomGestureDetector(
    context: Context,
    private val listener: GestureDetectListener
) : GestureDetector(context, listener) {

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val consumed = listener.onTouchEvent(ev)
        return consumed || super.onTouchEvent(ev)
    }
}
