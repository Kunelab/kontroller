package io.github.kunelab.kontroller

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
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

        fun intent(ctx: Context, firstRun: Boolean = false): Intent =
            Intent(ctx, HelpActivity::class.java).putExtra(EXTRA_FIRST_RUN, firstRun)
    }
}
