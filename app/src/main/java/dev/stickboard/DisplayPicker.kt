package dev.stickboard

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Decides which screen the keyboard lives on. */
object DisplayPicker {

    fun usableDisplays(context: Context): List<Display> {
        val dm = context.getSystemService(DisplayManager::class.java)
        return dm.displays.filter {
            it.isValid && it.state != Display.STATE_OFF && (it.flags and Display.FLAG_PRIVATE) == 0
        }
    }

    /** Built-in panels. Display.getType() is hidden, but its value is part of Display.toString(). */
    private fun builtIn(displays: List<Display>): List<Display> {
        val described = displays.filter { it.toString().contains(" type ") }
        if (described.isEmpty()) return displays
        return described.filter { it.toString().contains(" type INTERNAL") }
    }

    private fun area(d: Display): Long = d.mode.physicalWidth.toLong() * d.mode.physicalHeight

    /**
     * The screen the keyboard should be drawn on, or null to draw it under the app as usual.
     * In auto mode a handheld with two built-in screens gets the keyboard on the smaller one,
     * which is the bottom screen on DS-style devices.
     */
    fun keyboardDisplay(context: Context, prefs: Prefs): Display? {
        val displays = usableDisplays(context)
        return when (val mode = prefs.screen) {
            Prefs.SCREEN_SAME -> null
            Prefs.SCREEN_AUTO -> {
                val panels = builtIn(displays)
                if (panels.size < 2) null else panels.minByOrNull(::area)
            }
            else -> displays.firstOrNull { it.displayId.toString() == mode }
        }
    }

    fun describe(d: Display): String =
        "Display ${d.displayId}: ${d.name} (${d.mode.physicalWidth}×${d.mode.physicalHeight})"
}
