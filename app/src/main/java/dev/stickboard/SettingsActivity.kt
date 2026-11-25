package dev.stickboard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var imm: InputMethodManager
    private var screenValues = listOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // Let the page run under the gesture bar instead of the theme's black strip.
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        prefs = Prefs(this)
        imm = getSystemService(InputMethodManager::class.java)

        val scroll = findViewById<View>(R.id.scroll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scroll.setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.btn_select).setOnClickListener { imm.showInputMethodPicker() }
        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        val mode = findViewById<RadioGroup>(R.id.cursor_mode)
        mode.check(
            when (prefs.cursorMode) {
                Prefs.CursorMode.RELATIVE -> R.id.mode_relative
                Prefs.CursorMode.TRACKPAD -> R.id.mode_trackpad
                Prefs.CursorMode.CONSOLE -> R.id.mode_console
            }
        )
        mode.setOnCheckedChangeListener { _, id ->
            prefs.cursorMode = when (id) {
                R.id.mode_trackpad -> Prefs.CursorMode.TRACKPAD
                R.id.mode_console -> Prefs.CursorMode.CONSOLE
                else -> Prefs.CursorMode.RELATIVE
            }
            updateModeSliders()
        }

        slider(R.id.speed, R.id.speed_label, ((prefs.cursorSpeed - 0.6f) * 10).toInt(),
            { "Cursor speed (relative mode): %.1f".format(0.6f + it / 10f) }) {
            prefs.cursorSpeed = 0.6f + it / 10f
        }
        slider(R.id.console_delay, R.id.console_delay_label, (prefs.consoleDelayMs - 100) / 25,
            { "Hold before repeating (console mode): ${100 + it * 25} ms" }) {
            prefs.consoleDelayMs = 100 + it * 25
        }
        slider(R.id.console_rate, R.id.console_rate_label, prefs.consoleRate - 3,
            { "Repeat speed (console mode): ${it + 3} keys per second" }) {
            prefs.consoleRate = it + 3
        }
        slider(R.id.secondary_height, R.id.secondary_height_label, prefs.secondaryHeightPct - 30,
            { "Keyboard height on the other screen: ${it + 30}%" }) {
            prefs.secondaryHeightPct = it + 30
        }
        val minWidth = Prefs.MIN_KEYBOARD_WIDTH
        slider(R.id.keyboard_width, R.id.keyboard_width_label, prefs.keyboardWidthPct - minWidth,
            { "Keyboard width (both halves, docked to the sides): ${it + minWidth}% of the screen" }) {
            prefs.keyboardWidthPct = it + minWidth
        }
        slider(R.id.inline_height, R.id.inline_height_label, prefs.inlineHeightPct - 25,
            { "Keyboard height under the app: ${it + 25}% of the screen" }) {
            prefs.inlineHeightPct = it + 25
        }

        updateModeSliders()

        toggle(R.id.haptics, prefs.haptics) { prefs.haptics = it }
        toggle(R.id.swap_shoulders, prefs.swapShoulders) {
            prefs.swapShoulders = it
            updateControls()
        }
        updateControls()
        toggle(R.id.show_preview, prefs.showPreview) { prefs.showPreview = it }
        toggle(R.id.transparent_middle, prefs.transparentMiddle) { prefs.transparentMiddle = it }
        toggle(R.id.number_row, prefs.numberRow) { prefs.numberRow = it }
    }

    private fun toggle(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
    }

    private fun updateControls() {
        val swap = prefs.swapShoulders
        val type = if (swap) "L1 / R1" else "L2 / R2"
        val rows = listOf(
            "Left stick" to "cursor on the left half",
            "Right stick" to "cursor on the right half",
            type to "type the key under that cursor",
            "L3 / R3" to "same as $type",
            "A" to "type under the last-moved cursor",
            "X" to "backspace",
            "Y" to "space",
            (if (swap) "L2" else "L1") to "shift (twice quickly: caps lock)",
            (if (swap) "R2" else "R1") to "symbols / letters",
            "Start" to "enter",
            "D-pad" to "move the text caret",
            "B" to "hide the keyboard",
            "Select" to "move the keyboard between screens\n              (one screen: switch keyboard)",
        )
        findViewById<TextView>(R.id.controls).text =
            rows.joinToString("\n") { (button, action) -> button.padEnd(14) + action }
    }

    /** Grey out the sliders that don't apply to the selected stick mode. */
    private fun updateModeSliders() {
        val mode = prefs.cursorMode
        setSliderEnabled(R.id.speed, R.id.speed_label, mode == Prefs.CursorMode.RELATIVE)
        setSliderEnabled(R.id.console_delay, R.id.console_delay_label, mode == Prefs.CursorMode.CONSOLE)
        setSliderEnabled(R.id.console_rate, R.id.console_rate_label, mode == Prefs.CursorMode.CONSOLE)
    }

    private fun setSliderEnabled(id: Int, labelId: Int, on: Boolean) {
        findViewById<SeekBar>(id).isEnabled = on
        findViewById<TextView>(labelId).alpha = if (on) 1f else 0.5f
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshScreens()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The input method picker is a dialog over us; re-check once it closes.
        if (hasFocus) refreshStatus()
    }

    private fun slider(id: Int, labelId: Int, value: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
        val text = findViewById<TextView>(labelId)
        findViewById<SeekBar>(id).apply {
            progress = value
            text.text = label(progress)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    text.text = label(p)
                    if (fromUser) onChange(p)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
    }

    private fun refreshStatus() {
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val overlay = Settings.canDrawOverlays(this)
        val dual = DisplayPicker.usableDisplays(this).size > 1

        status(R.id.status_enabled, R.id.btn_enable, enabled, "1. StickBoard is enabled", "1. Enable StickBoard in the keyboard list")
        status(R.id.status_selected, R.id.btn_select, current, "2. StickBoard is the current keyboard", "2. Switch to StickBoard")
        status(
            R.id.status_overlay, R.id.btn_overlay, overlay,
            "3. Allowed to draw on the other screen",
            if (dual) "3. Allow \"display over other apps\" so the keyboard can appear on the other screen"
            else "3. Optional: allow \"display over other apps\" (only needed on dual-screen devices)",
        )
    }

    private fun status(textId: Int, buttonId: Int, ok: Boolean, okText: String, todoText: String) {
        findViewById<TextView>(textId).text = if (ok) "✓ $okText" else todoText
        findViewById<Button>(buttonId).isEnabled = !ok || buttonId == R.id.btn_select
    }

    private fun refreshScreens() {
        val displays = DisplayPicker.usableDisplays(this)
        val labels = mutableListOf(
            "Auto: the smaller screen on dual-screen devices",
            "The same screen as the app",
        )
        val values = mutableListOf(Prefs.SCREEN_AUTO, Prefs.SCREEN_SAME)
        for (d in displays) {
            labels += DisplayPicker.describe(d)
            values += d.displayId.toString()
        }
        // Keep a saved display that is currently off selectable.
        if (prefs.screen !in values) {
            labels += "Display ${prefs.screen} (not connected)"
            values += prefs.screen
        }
        screenValues = values
        val spinner = findViewById<Spinner>(R.id.screen)
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(values.indexOf(prefs.screen).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                prefs.screen = screenValues[pos]
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }
}
