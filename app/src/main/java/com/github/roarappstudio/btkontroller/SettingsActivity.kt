package com.github.roarappstudio.btkontroller

import android.app.Activity
import android.app.LocaleManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.github.roarappstudio.btkontroller.Prefs.autoPair
import com.github.roarappstudio.btkontroller.Prefs.autoReconnect
import com.github.roarappstudio.btkontroller.Prefs.clickBar
import com.github.roarappstudio.btkontroller.Prefs.clipboardAction
import com.github.roarappstudio.btkontroller.Prefs.gyroInvertX
import com.github.roarappstudio.btkontroller.Prefs.gyroInvertY
import com.github.roarappstudio.btkontroller.Prefs.gyroPointer
import com.github.roarappstudio.btkontroller.Prefs.hostLayout
import com.github.roarappstudio.btkontroller.Prefs.keepScreenOn
import com.github.roarappstudio.btkontroller.Prefs.mediaKeys
import com.github.roarappstudio.btkontroller.Prefs.orientation
import com.github.roarappstudio.btkontroller.Prefs.sensitivity
import com.github.roarappstudio.btkontroller.Prefs.stayConnected
import com.github.roarappstudio.btkontroller.Prefs.theme

/**
 * Settings. Changes are written immediately and picked up by
 * [SelectDeviceActivity.onStart] when this screen is dismissed.
 */
class SettingsActivity : Activity() {

    private val prefs by lazy { Prefs.of(this) }

    private lateinit var sensitivityLabel: TextView
    private lateinit var orientationGroup: RadioGroup
    private lateinit var orientationAuto: RadioButton
    private lateinit var orientationGyroNote: View
    private lateinit var invertX: Switch
    private lateinit var invertY: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeSupport.appStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        SystemBars.applyTo(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        orientationGroup = findViewById(R.id.orientationGroup)
        orientationAuto = findViewById(R.id.orientationAuto)
        orientationGyroNote = findViewById(R.id.orientationGyroNote)

        setUpSensitivity()
        setUpSwitches()
        setUpHostLayout()
        setUpLanguage()
        setUpTheme()
        setUpOrientation()

        findViewById<Button>(R.id.openHelpButton).setOnClickListener {
            startActivity(HelpActivity.intent(this))
        }
    }

    private fun setUpSensitivity() {
        val seek = findViewById<SeekBar>(R.id.sensitivitySeek)
        seek.max = Prefs.SENSITIVITY_MAX - Prefs.SENSITIVITY_MIN
        seek.progress = prefs.sensitivity - Prefs.SENSITIVITY_MIN
        showSensitivity(prefs.sensitivity)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val value = progress + Prefs.SENSITIVITY_MIN
                showSensitivity(value)
                if (fromUser) prefs.sensitivity = value
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun showSensitivity(value: Int) {
        sensitivityLabel.text = getString(R.string.setting_sensitivity_value, value)
    }

    private fun setUpSwitches() {
        bind(R.id.switchClickBar, prefs.clickBar) { prefs.clickBar = it }
        bind(R.id.switchMediaKeys, prefs.mediaKeys) { prefs.mediaKeys = it }
        bind(R.id.switchClipboard, prefs.clipboardAction) { prefs.clipboardAction = it }
        bind(R.id.switchAutoPair, prefs.autoPair) { prefs.autoPair = it }
        bind(R.id.switchAutoReconnect, prefs.autoReconnect) { prefs.autoReconnect = it }
        bind(R.id.switchStayConnected, prefs.stayConnected) { prefs.stayConnected = it }
        bind(R.id.switchKeepScreenOn, prefs.keepScreenOn) { prefs.keepScreenOn = it }

        invertX = bind(R.id.switchGyroInvertX, prefs.gyroInvertX) { prefs.gyroInvertX = it }
        invertY = bind(R.id.switchGyroInvertY, prefs.gyroInvertY) { prefs.gyroInvertY = it }

        bind(R.id.switchGyro, prefs.gyroPointer) {
            prefs.gyroPointer = it
            // Auto-rotate and the gyro pointer are mutually exclusive, so the Auto option
            // has to be taken away (or given back) as soon as this changes.
            refreshOrientationAvailability()
            refreshGyroAxisAvailability()
        }
        refreshGyroAxisAvailability()
    }

    /** The axis flips only mean anything while the gyro pointer is driving the cursor. */
    private fun refreshGyroAxisAvailability() {
        val gyroOn = prefs.gyroPointer
        invertX.isEnabled = gyroOn
        invertY.isEnabled = gyroOn
    }

    private fun bind(id: Int, initial: Boolean, onChange: (Boolean) -> Unit): Switch =
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }

    private fun setUpHostLayout() {
        val spinner = findViewById<Spinner>(R.id.hostLayoutSpinner)
        val layouts = HostLayout.entries
        val labels = layouts.map { getString(labelFor(it)) }

        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        spinner.setSelection(layouts.indexOf(prefs.hostLayout), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.hostLayout = layouts[pos]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun labelFor(layout: HostLayout): Int = when (layout) {
        HostLayout.US -> R.string.layout_us
        HostLayout.UK -> R.string.layout_uk
        HostLayout.FR -> R.string.layout_fr
        HostLayout.BE -> R.string.layout_be
        HostLayout.DE -> R.string.layout_de
        HostLayout.CH -> R.string.layout_ch
        HostLayout.ES -> R.string.layout_es
        HostLayout.LATAM -> R.string.layout_latam
        HostLayout.IT -> R.string.layout_it
        HostLayout.PT -> R.string.layout_pt
        HostLayout.BR -> R.string.layout_br
        HostLayout.SE -> R.string.layout_se
        HostLayout.DK -> R.string.layout_dk
        HostLayout.NO -> R.string.layout_no
        HostLayout.JP -> R.string.layout_jp
        HostLayout.TR -> R.string.layout_tr
    }

    /**
     * In-app language switching uses the platform per-app locale API, which only exists from
     * Android 13. Below that the app follows the system language, so the picker is hidden
     * rather than shown doing nothing.
     */
    private fun setUpLanguage() {
        val label = findViewById<View>(R.id.languageLabel)
        val spinner = findViewById<Spinner>(R.id.languageSpinner)
        val summary = findViewById<View>(R.id.languageSummary)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            label.visibility = View.GONE
            spinner.visibility = View.GONE
            summary.visibility = View.GONE
            return
        }

        val languages = AppLanguage.entries
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.nativeName }
        )

        val manager = getSystemService(LocaleManager::class.java)
        val current = manager?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)
        val selected = AppLanguage.entries.firstOrNull {
            it.tag.isNotEmpty() && current != null && it.tag.equals(current.toLanguageTag(), true)
        } ?: AppLanguage.entries.firstOrNull {
            it.tag.isNotEmpty() && current != null && it.tag.equals(current.language, true)
        } ?: AppLanguage.SYSTEM

        spinner.setSelection(languages.indexOf(selected), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val chosen = languages[pos]
                if (chosen == selected) return
                manager?.applicationLocales = if (chosen.tag.isEmpty()) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(chosen.tag)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun setUpTheme() {
        val group = findViewById<RadioGroup>(R.id.themeGroup)
        group.check(
            when (prefs.theme) {
                ThemeMode.SYSTEM -> R.id.themeSystem
                ThemeMode.LIGHT -> R.id.themeLight
                ThemeMode.BLACK -> R.id.themeBlack
            }
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            val chosen = when (checkedId) {
                R.id.themeLight -> ThemeMode.LIGHT
                R.id.themeBlack -> ThemeMode.BLACK
                else -> ThemeMode.SYSTEM
            }
            if (chosen != prefs.theme) {
                prefs.theme = chosen
                // A theme is only applied at activity creation, so restart this screen to
                // show it immediately. SelectDeviceActivity re-checks in onStart.
                recreate()
            }
        }
    }

    private fun setUpOrientation() {
        orientationGroup.check(
            when (prefs.orientation) {
                OrientationMode.PORTRAIT -> R.id.orientationPortrait
                OrientationMode.LANDSCAPE -> R.id.orientationLandscape
                OrientationMode.AUTO -> R.id.orientationAuto
            }
        )
        orientationGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.orientation = when (checkedId) {
                R.id.orientationLandscape -> OrientationMode.LANDSCAPE
                R.id.orientationAuto -> OrientationMode.AUTO
                else -> OrientationMode.PORTRAIT
            }
        }
        refreshOrientationAvailability()
    }

    /**
     * Tilting the phone to move the pointer would spin the screen, so Auto is disabled
     * while the gyro pointer is on. An already-selected Auto falls back to portrait.
     */
    private fun refreshOrientationAvailability() {
        val gyroOn = prefs.gyroPointer

        orientationAuto.isEnabled = !gyroOn
        orientationGyroNote.visibility = if (gyroOn) View.VISIBLE else View.GONE

        if (gyroOn && prefs.orientation == OrientationMode.AUTO) {
            prefs.orientation = OrientationMode.PORTRAIT
            orientationGroup.check(R.id.orientationPortrait)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
