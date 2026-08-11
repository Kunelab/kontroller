package io.github.kunelab.kontroller

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * Keeps content clear of the navigation bar now that the window draws edge to edge.
 *
 * From `targetSdk` 35 Android stops insetting the window for the system bars, so a layout
 * that never asked for insets simply draws underneath them. Here that put the click buttons
 * and the media-key row -- the two most-tapped controls in the app -- partly behind the
 * gesture navigation bar, and in landscape put content under the cutout.
 *
 * Only the bottom and the sides are applied. The top is left alone deliberately: these
 * activities use `Theme.DeviceDefault`, whose decor already positions the platform action
 * bar below the status bar, and padding the content again would double the gap.
 *
 * The app does not use AndroidX, so this is the framework API directly, with the pre-API-30
 * fallback that `minSdk 28` still needs.
 */
object SystemBars {

    /** Applies side and bottom insets as padding to this activity's content view. */
    fun applyTo(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val root = (content as? ViewGroup)?.getChildAt(0) ?: content
        apply(root)
    }

    private fun apply(root: View) {
        // A scrolling container keeps its content visible under the bar while it scrolls,
        // and the padding then stops the last row being trapped behind it.
        if (root is ViewGroup) root.clipToPadding = false

        val startLeft = root.paddingLeft
        val startRight = root.paddingRight
        val startBottom = root.paddingBottom

        root.setOnApplyWindowInsetsListener { view, insets ->
            val left: Int
            val right: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                left = bars.left
                right = bars.right
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                left = insets.systemWindowInsetLeft
                @Suppress("DEPRECATION")
                right = insets.systemWindowInsetRight
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }

            view.setPadding(
                startLeft + left,
                view.paddingTop,
                startRight + right,
                startBottom + bottom
            )
            insets
        }
        root.requestApplyInsets()
    }
}
