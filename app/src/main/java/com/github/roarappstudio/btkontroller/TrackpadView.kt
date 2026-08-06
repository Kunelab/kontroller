package com.github.roarappstudio.btkontroller

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView

/**
 * The trackpad surface. It doubles as the soft-keyboard target: it declares itself a
 * text editor so the IME attaches to it, and hands every keystroke to [onHidKey].
 *
 * Upstream relied solely on `Activity.onKeyUp` receiving [KeyEvent]s. That only ever
 * worked for the handful of keys an IME dispatches as raw key events -- ordinary
 * characters arrive through `InputConnection.commitText` and were silently dropped.
 * See [HidInputConnection].
 */
class TrackpadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextView(context, attrs, defStyleAttr) {

    /**
     * Invoked for every key press to forward. Returns true when the key was mapped to a
     * HID usage and sent. Wired up by the activity once the HID link is established.
     */
    var onHidKey: ((KeyEvent) -> Boolean)? = null

    /**
     * Invoked for a typed character so the host-layout table can pick the key *position*
     * that produces it. Returns false when the active layout has no mapping, in which case
     * the caller falls back to the keycode path.
     */
    var onHidChar: ((Char) -> Boolean)? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun dispatchHidKey(event: KeyEvent): Boolean = onHidKey?.invoke(event) ?: false

    fun dispatchHidChar(ch: Char): Boolean = onHidChar?.invoke(ch) ?: false

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // TYPE_NULL asks the IME to deliver raw key events where it is able to, which
        // preserves modifier state for things like Ctrl+C. Anything the IME insists on
        // committing as text is caught by HidInputConnection.commitText instead.
        outAttrs.inputType = InputType.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_ACTION_NONE
        return HidInputConnection(this)
    }
}
