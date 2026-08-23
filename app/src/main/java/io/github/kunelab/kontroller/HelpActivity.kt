package io.github.kunelab.kontroller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ScrollView
import io.github.kunelab.kontroller.Prefs.helpShown

/**
 * Getting-started guide. Shown once on first launch, and reachable from the overflow menu
 * afterwards.
 *
 * On first run it continues into [SelectDeviceActivity]; when opened from the menu it just
 * closes, so the button is hidden.
 */
class HelpActivity : Activity() {

    private val firstRun by lazy { intent.getBooleanExtra(EXTRA_FIRST_RUN, false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeSupport.appStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)
        SystemBars.applyTo(this)

        if (!firstRun) actionBar?.setDisplayHomeAsUpEnabled(true)

        val continueButton = findViewById<Button>(R.id.continueButton)
        continueButton.visibility = if (firstRun) View.VISIBLE else View.GONE
        continueButton.setOnClickListener {
            Prefs.of(this).helpShown = true
            startActivity(Intent(this, SelectDeviceActivity::class.java))
            finish()
        }

        scrollToRequestedSection()
    }

    /**
     * Jumps to the section named in the intent, if any.
     *
     * This page is long, and the failures that send people here are exactly the ones where
     * they are already stuck -- landing them at the top to hunt for the right heading is how
     * a help page gets closed unread.
     *
     * The scroll has to wait for a layout pass: until one has run every child is at y=0 and
     * the jump would be a no-op. `View.post` is not enough, because before the view is
     * attached it queues into the run queue, which is drained *before* measure and layout.
     */
    private fun scrollToRequestedSection() {
        val heading = intent.getStringExtra(EXTRA_SECTION)
            ?.let { SECTIONS[it] }
            ?.let { findViewById<View>(it) }
            ?: return
        val scroll = findViewById<ScrollView>(R.id.helpScroll)

        scroll.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    // Smooth rather than instant: the movement is what tells the reader they
                    // were taken somewhere, and that there is more above it.
                    scroll.smoothScrollTo(0, heading.top)
                }
            }
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val EXTRA_FIRST_RUN = "first_run"
        private const val EXTRA_SECTION = "section"

        /**
         * The host has no keyboard record for this phone and the pairing has to be redone.
         *
         * Raised by [BluetoothController.onHostRefusingHid] and surfaced by
         * [SelectDeviceActivity]. The remedy is several steps long, differs per operating
         * system and is entirely off-device, which is why it lives here rather than in the
         * dialog that sends people to it.
         */
        const val SECTION_NO_HOST_RECORD = "no_host_record"

        /** Section keys to the heading each one should scroll to. */
        private val SECTIONS = mapOf(
            SECTION_NO_HOST_RECORD to R.id.helpNoRecordHeading
        )

        fun intent(ctx: Context, firstRun: Boolean = false, section: String? = null): Intent =
            Intent(ctx, HelpActivity::class.java)
                .putExtra(EXTRA_FIRST_RUN, firstRun)
                .putExtra(EXTRA_SECTION, section)
    }
}
