package com.github.roarappstudio.btkontroller

import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection

/**
 * Bridges the soft keyboard to the HID keyboard report.
 *
 * Soft keyboards deliver input two different ways and upstream only ever handled one of
 * them, which is why typing did not work on modern Android:
 *
 *  - [sendKeyEvent] -- raw key events (Enter, Backspace, arrows, hardware keyboards, and
 *    anything an IME sends when the field is `TYPE_NULL`).
 *  - [commitText] -- finished text, which is how Gboard and friends deliver ordinary
 *    characters, predictions and autocorrect.
 *
 * Characters from [commitText] are converted back into [KeyEvent]s via
 * [KeyCharacterMap], so the existing Android-keycode to HID-usage table
 * (`KeyboardReport.KeyEventMap`) keeps doing the mapping -- including the shift presses
 * needed for uppercase letters and symbols.
 */
class HidInputConnection(
    private val view: TrackpadView
) : BaseInputConnection(view, /* fullEditor = */ false) {

    private val charMap: KeyCharacterMap =
        KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text.isNullOrEmpty()) return true

        val events = charMap.getEvents(text.toString().toCharArray())
        if (events == null) {
            Log.w(TAG, "No key events available for \"$text\" on a virtual keyboard")
            return true
        }
        // getEvents() returns paired down/up events; the sender emits a full
        // press-and-release per call, so only the down events are forwarded.
        for (event in events) {
            if (event.action == KeyEvent.ACTION_DOWN) view.dispatchHidKey(event)
        }
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            view.dispatchHidKey(event)
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        repeat(beforeLength) {
            view.dispatchHidKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        }
        repeat(afterLength) {
            view.dispatchHidKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        view.dispatchHidKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        return true
    }

    companion object {
        private const val TAG = "HidInputConnection"
    }
}
