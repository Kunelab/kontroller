package io.github.kunelab.kontroller

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import io.github.kunelab.kontroller.Prefs.theme

/**
 * Resolves the user's theme choice to a concrete style.
 *
 * The app deliberately avoids AndroidX AppCompat (and therefore `AppCompatDelegate`'s night
 * mode), so the theme is applied by calling [Activity.setTheme] before `super.onCreate`.
 */
object ThemeSupport {

    fun isSystemDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    /** Style for the normal, action-bar-bearing screens. */
    fun appStyle(ctx: Context): Int = when (Prefs.of(ctx).theme) {
        ThemeMode.LIGHT -> R.style.AppStyle_Light
        ThemeMode.BLACK -> R.style.AppStyle_Black
        ThemeMode.SYSTEM ->
            if (isSystemDark(ctx)) R.style.AppStyle_Black else R.style.AppStyle_Light
    }

    /** Style for the splash screen, which has no action bar. */
    fun splashStyle(ctx: Context): Int = when (Prefs.of(ctx).theme) {
        ThemeMode.LIGHT -> R.style.SplashStyle_Light
        ThemeMode.BLACK -> R.style.SplashStyle_Black
        ThemeMode.SYSTEM ->
            if (isSystemDark(ctx)) R.style.SplashStyle_Black else R.style.SplashStyle_Light
    }
}
