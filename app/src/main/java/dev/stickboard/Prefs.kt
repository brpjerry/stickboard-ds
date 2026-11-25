package dev.stickboard

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("stickboard", Context.MODE_PRIVATE)

    enum class CursorMode {
        /** The stick steers a cursor; deflection sets its speed. */
        RELATIVE,

        /** The stick's position maps straight to a spot on the half (Steam Deck trackpad style). */
        TRACKPAD,

        /** The stick moves the selection one key at a time in eight directions. */
        CONSOLE,
    }

    var cursorMode: CursorMode
        get() = sp.getString(KEY_CURSOR_MODE, null)?.let { runCatching { CursorMode.valueOf(it) }.getOrNull() }
            // Older versions stored only a trackpad on/off flag.
            ?: if (sp.getBoolean(KEY_ABSOLUTE, false)) CursorMode.TRACKPAD else CursorMode.RELATIVE
        set(v) = sp.edit().putString(KEY_CURSOR_MODE, v.name).remove(KEY_ABSOLUTE).apply()

    val trackpadMode get() = cursorMode == CursorMode.TRACKPAD

    /** Console mode: how long a held stick waits before repeating, in milliseconds. */
    var consoleDelayMs: Int
        get() = sp.getInt(KEY_CONSOLE_DELAY, 350)
        set(v) = sp.edit().putInt(KEY_CONSOLE_DELAY, v).apply()

    /** Console mode: keys per second while a held stick repeats. */
    var consoleRate: Int
        get() = sp.getInt(KEY_CONSOLE_RATE, 10)
        set(v) = sp.edit().putInt(KEY_CONSOLE_RATE, v).apply()

    /** Relative-mode cursor speed at full tilt, in half-widths per second. */
    var cursorSpeed: Float
        get() = sp.getFloat(KEY_SPEED, 1.6f)
        set(v) = sp.edit().putFloat(KEY_SPEED, v).apply()

    /** "auto", "same", or a display id. */
    var screen: String
        get() = sp.getString(KEY_SCREEN, SCREEN_AUTO) ?: SCREEN_AUTO
        set(v) = sp.edit().putString(KEY_SCREEN, v).apply()

    /** Share of the other screen's height the keyboard takes on dual-screen devices. */
    var secondaryHeightPct: Int
        get() = sp.getInt(KEY_SECONDARY_HEIGHT, 100)
        set(v) = sp.edit().putInt(KEY_SECONDARY_HEIGHT, v).apply()

    /** Where the keyboard sits on the other screen: 0 = top, 1 = bottom, as a share of the free space. */
    var secondaryPosition: Float
        get() = sp.getFloat(KEY_SECONDARY_POSITION, 1f)
        set(v) = sp.edit().putFloat(KEY_SECONDARY_POSITION, v).apply()

    /** Share of the screen height the keyboard takes when it sits under the app. */
    var inlineHeightPct: Int
        get() = sp.getInt(KEY_INLINE_HEIGHT, 42)
        set(v) = sp.edit().putInt(KEY_INLINE_HEIGHT, v).apply()

    /** Show the text around the caret above the keyboard when it's on the other screen. */
    var showPreview: Boolean
        get() = sp.getBoolean(KEY_SHOW_PREVIEW, false)
        set(v) = sp.edit().putBoolean(KEY_SHOW_PREVIEW, v).apply()

    /** Leave the space between the halves see-through, with no button guide in it. */
    var transparentMiddle: Boolean
        get() = sp.getBoolean(KEY_TRANSPARENT_MIDDLE, false)
        set(v) = sp.edit().putBoolean(KEY_TRANSPARENT_MIDDLE, v).apply()

    /** L1/R1 type and L2/R2 are shift / symbols, instead of the other way round. */
    var swapShoulders: Boolean
        get() = sp.getBoolean(KEY_SWAP_SHOULDERS, false)
        set(v) = sp.edit().putBoolean(KEY_SWAP_SHOULDERS, v).apply()

    /** Width of both halves together, as a percentage of the screen width. The rest is the gap. */
    var keyboardWidthPct: Int
        get() = sp.getInt(KEY_KEYBOARD_WIDTH, DEFAULT_KEYBOARD_WIDTH)
        set(v) = sp.edit().putInt(KEY_KEYBOARD_WIDTH, v).apply()

    /** Called (on the main thread) whenever any setting changes. Keeps a strong reference. */
    fun onChange(listener: () -> Unit) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> listener() }
        listeners += l
        sp.registerOnSharedPreferenceChangeListener(l)
    }

    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** Show the row of digits above the letters. */
    var numberRow: Boolean
        get() = sp.getBoolean(KEY_NUMBER_ROW, true)
        set(v) = sp.edit().putBoolean(KEY_NUMBER_ROW, v).apply()

    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(v) = sp.edit().putBoolean(KEY_HAPTICS, v).apply()

    companion object {
        const val SCREEN_AUTO = "auto"
        const val SCREEN_SAME = "same"
        const val MIN_KEYBOARD_WIDTH = 40
        const val DEFAULT_KEYBOARD_WIDTH = 83
        private const val KEY_ABSOLUTE = "absolute_cursor"
        private const val KEY_CURSOR_MODE = "cursor_mode"
        private const val KEY_CONSOLE_DELAY = "console_delay_ms"
        private const val KEY_CONSOLE_RATE = "console_rate"
        private const val KEY_SPEED = "cursor_speed"
        private const val KEY_SCREEN = "screen"
        private const val KEY_SECONDARY_HEIGHT = "secondary_height_pct"
        private const val KEY_INLINE_HEIGHT = "inline_height_pct"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_KEYBOARD_WIDTH = "keyboard_width_pct"
        private const val KEY_SECONDARY_POSITION = "secondary_position"
        private const val KEY_SWAP_SHOULDERS = "swap_shoulders"
        private const val KEY_SHOW_PREVIEW = "show_preview"
        private const val KEY_TRANSPARENT_MIDDLE = "transparent_middle"
    }
}
