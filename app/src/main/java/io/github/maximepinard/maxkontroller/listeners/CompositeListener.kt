package io.github.maximepinard.maxkontroller.listeners

import android.view.MotionEvent
import android.view.View

/**
 * Fans one touch stream out to several listeners.
 *
 * The trackpad needs both the gesture detector and [ViewListener] to see every event, and a
 * view only holds one [View.OnTouchListener]. Every listener is called regardless of what
 * the previous one returned -- they handle disjoint parts of the gesture (movement versus
 * taps and scroll) and neither should be able to starve the other.
 */
class CompositeListener(
    private vararg val listeners: View.OnTouchListener
) : View.OnTouchListener {

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        for (listener in listeners) listener.onTouch(v, event)
        return true
    }
}
