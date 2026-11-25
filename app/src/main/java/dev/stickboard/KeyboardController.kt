package dev.stickboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Keyboard state shared by every view that draws it, plus the Steam Deck style controller
 * scheme: each stick drives a cursor on its own half, and the matching trigger types the key
 * under that cursor.
 */
class KeyboardController(private val ime: StickBoardIme, private val prefs: Prefs) {

    enum class Shift { OFF, ONCE, LOCK }

    var symbols = false
        private set
    var shift = Shift.OFF
        private set
    private var autoShifted = false
    private var lastShiftTap = 0L

    val layout
        get() = when {
            symbols -> Layouts.SYMBOLS
            prefs.numberRow -> Layouts.LETTERS
            else -> Layouts.LETTERS_NO_NUMBERS
        }

    /** Cursor positions per side (0 = left, 1 = right), normalized to that half. */
    val cursorX = floatArrayOf(HOME_X[0], HOME_X[1])
    val cursorY = floatArrayOf(0.5f, 0.5f)
    var activeSide = 0
        private set
    var cursorsVisible = false
    var controllerPresent = false
        private set

    var enterLabel = "↵"
        private set

    val transparentMiddle get() = prefs.transparentMiddle

    /** Set by the IME: Select moves the keyboard between screens instead of opening the picker. */
    var dualScreen = false

    /** Half width / half height in pixels, so the cursor moves at the same speed in x and y. */
    var halfAspect = 1f

    private val views = LinkedHashSet<View>()
    private val handler = Handler(Looper.getMainLooper())

    init {
        // Redraw straight away when a setting changes, e.g. while dragging a slider in settings.
        prefs.onChange { invalidate() }
    }

    val keyboardWidthPct get() = prefs.keyboardWidthPct.coerceIn(Prefs.MIN_KEYBOARD_WIDTH, 100)

    // ---- views ----

    fun attach(v: View) {
        views += v
    }

    fun detach(v: View) {
        views -= v
    }

    fun invalidate() = views.forEach { it.invalidate() }

    private val flashed = arrayOfNulls<Key>(2)
    private val clearFlash = Runnable { flashed.fill(null); invalidate() }

    fun isFlashed(key: Key) = flashed[0] === key || flashed[1] === key

    fun hoveredKey(side: Int): Key? = if (cursorsVisible) aimedKey(side) else null

    /** The key a trigger press on [side] would type right now. */
    private fun aimedKey(side: Int): Key? {
        if (prefs.trackpadMode) {
            // Let go of the stick and pull the trigger at the same moment: the stick often reaches
            // center first. Type what was aimed at just before, as a trackpad lift would.
            if (TrackpadMapping.isResting(stickX[side], stickY[side]) &&
                SystemClock.uptimeMillis() - lastAimedAt[side] < RELEASE_GRACE_MS
            ) {
                lastAimed[side]?.let { return it }
            }
            sticky[side]?.let { return it }
        }
        return layout.half(side).keyAt(cursorX[side], cursorY[side])
    }

    /** Whether the cursor on [side] is sitting at rest (trackpad mode, stick centered). */
    fun cursorResting(side: Int) = prefs.trackpadMode && TrackpadMapping.isResting(stickX[side], stickY[side])

    // ---- editor lifecycle ----

    fun onStartInput(info: EditorInfo) {
        val cls = info.inputType and EditorInfo.TYPE_MASK_CLASS
        symbols = cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_PHONE ||
            cls == EditorInfo.TYPE_CLASS_DATETIME
        shift = Shift.OFF
        autoShifted = false
        enterLabel = enterLabelFor(info)
        controllerPresent = gamepadConnected()
        cursorsVisible = controllerPresent
        recenter()
        updateAutoCap()
        invalidate()
    }

    private fun recenter() {
        for (s in 0..1) {
            stickX[s] = 0f
            stickY[s] = 0f
            sticky[s] = null
            lastAimed[s] = null
            when (prefs.cursorMode) {
                Prefs.CursorMode.TRACKPAD -> applyTrackpad(s) // a centered stick points at the middle of the half
                Prefs.CursorMode.CONSOLE -> selectHome(s)
                Prefs.CursorMode.RELATIVE -> {
                    cursorX[s] = HOME_X[s]
                    cursorY[s] = layout.half(s).rowCenterOf(HOME_KEYS[s]) ?: 0.5f
                }
            }
        }
        console.reset()
    }

    private fun enterLabelFor(info: EditorInfo): String {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return "↵"
        info.actionLabel?.let { if (it.isNotBlank()) return it.toString() }
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "Go"
            EditorInfo.IME_ACTION_SEARCH -> "Search"
            EditorInfo.IME_ACTION_SEND -> "Send"
            EditorInfo.IME_ACTION_NEXT -> "Next"
            EditorInfo.IME_ACTION_DONE -> "Done"
            EditorInfo.IME_ACTION_PREVIOUS -> "Prev"
            else -> "↵"
        }
    }

    /** Turns shift on at the start of a sentence when the editor asks for it, and back off when not. */
    fun updateAutoCap() {
        val wanted = ime.capsModeActive()
        if (wanted && shift == Shift.OFF) {
            shift = Shift.ONCE
            autoShifted = true
            invalidate()
        } else if (!wanted && shift == Shift.ONCE && autoShifted) {
            shift = Shift.OFF
            autoShifted = false
            invalidate()
        }
    }

    // ---- key actions ----

    fun press(key: Key, haptic: Boolean = true) {
        when (key.type) {
            KeyType.CHAR -> {
                val text = if (shift != Shift.OFF) key.shifted ?: key.text.uppercase() else key.text
                ime.commit(text)
                if (shift == Shift.ONCE) {
                    shift = Shift.OFF
                    autoShifted = false
                }
                updateAutoCap()
            }
            KeyType.SPACE -> {
                ime.commit(" ")
                updateAutoCap()
            }
            KeyType.BACKSPACE -> {
                ime.backspace()
                updateAutoCap()
            }
            KeyType.ENTER -> {
                ime.enter()
                updateAutoCap()
            }
            KeyType.SHIFT -> toggleShift()
            KeyType.LAYER -> {
                symbols = !symbols
                sticky.fill(null)
                lastAimed.fill(null)
                if (prefs.trackpadMode) for (s in 0..1) applyTrackpad(s)
                if (prefs.cursorMode == Prefs.CursorMode.CONSOLE) for (s in 0..1) snapToKey(s)
            }
        }
        if (haptic) ime.haptic()
        invalidate()
    }

    private fun toggleShift() {
        val now = SystemClock.uptimeMillis()
        shift = when {
            shift == Shift.ONCE && !autoShifted && now - lastShiftTap < DOUBLE_TAP_MS -> Shift.LOCK
            shift == Shift.OFF -> Shift.ONCE
            else -> Shift.OFF
        }
        autoShifted = false
        lastShiftTap = now
    }

    /** Types the key under one side's cursor. */
    private fun typeSide(side: Int) {
        activeSide = side
        cursorsVisible = true
        val key = aimedKey(side) ?: return
        flashed[side] = key
        handler.removeCallbacks(clearFlash)
        handler.postDelayed(clearFlash, FLASH_MS)
        press(key)
    }

    private fun pressType(type: KeyType) {
        val key = Key("", type, text = if (type == KeyType.SPACE) " " else "")
        press(key)
    }

    fun hapticOnly() = ime.haptic()

    fun onTouchStart() {
        if (cursorsVisible) {
            cursorsVisible = false
            invalidate()
        }
    }

    // ---- gamepad ----

    private fun gamepadConnected(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK)
    }

    private fun markController() {
        controllerPresent = true
        if (!cursorsVisible) {
            cursorsVisible = true
            invalidate()
        }
    }

    /** Returns true if the key was handled. Only called for keys coming from a gamepad. */
    fun onGamepadKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val repeat = event.repeatCount > 0
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> if (!repeat) trigger(0, SRC_KEY)
            KeyEvent.KEYCODE_BUTTON_R2 -> if (!repeat) trigger(1, SRC_KEY)
            KeyEvent.KEYCODE_BUTTON_L1 -> if (!repeat) shoulder(0)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (!repeat) shoulder(1)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> if (!repeat) typeSide(0)
            KeyEvent.KEYCODE_BUTTON_THUMBR -> if (!repeat) typeSide(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> if (!repeat) typeSide(activeSide)
            KeyEvent.KEYCODE_BUTTON_X -> pressType(KeyType.BACKSPACE)
            KeyEvent.KEYCODE_BUTTON_Y -> pressType(KeyType.SPACE)
            KeyEvent.KEYCODE_BUTTON_START -> if (!repeat) pressType(KeyType.ENTER)
            KeyEvent.KEYCODE_BUTTON_B -> if (!repeat) ime.hideKeyboard()
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (!repeat) ime.onSelectButton()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> arrow(keyCode, SRC_KEY, repeat)
            else -> return false
        }
        markController()
        return true
    }

    // Sticks, triggers and hat as last reported.
    private val stickX = FloatArray(2)
    private val stickY = FloatArray(2)
    private val triggerDown = BooleanArray(2)
    private var hatX = 0
    private var hatY = 0

    fun onJoystickMotion(ev: MotionEvent): Boolean {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) return false
        val dev = ev.device
        val zRz = dev == null ||
            (dev.getMotionRange(MotionEvent.AXIS_Z) != null && dev.getMotionRange(MotionEvent.AXIS_RZ) != null)
        stickX[0] = ev.getAxisValue(MotionEvent.AXIS_X)
        stickY[0] = ev.getAxisValue(MotionEvent.AXIS_Y)
        stickX[1] = ev.getAxisValue(if (zRz) MotionEvent.AXIS_Z else MotionEvent.AXIS_RX)
        stickY[1] = ev.getAxisValue(if (zRz) MotionEvent.AXIS_RZ else MotionEvent.AXIS_RY)

        axisTrigger(0, maxOf(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE)))
        axisTrigger(1, maxOf(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS)))

        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X).toHat()
        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y).toHat()
        if (hx != hatX) {
            hatX = hx
            if (hx < 0) arrow(KeyEvent.KEYCODE_DPAD_LEFT, SRC_AXIS, false)
            if (hx > 0) arrow(KeyEvent.KEYCODE_DPAD_RIGHT, SRC_AXIS, false)
        }
        if (hy != hatY) {
            hatY = hy
            if (hy < 0) arrow(KeyEvent.KEYCODE_DPAD_UP, SRC_AXIS, false)
            if (hy > 0) arrow(KeyEvent.KEYCODE_DPAD_DOWN, SRC_AXIS, false)
        }

        val mag = FloatArray(2) { hypot(stickX[it], stickY[it]) }
        if (mag[0] > DEADZONE || mag[1] > DEADZONE) {
            markController()
            activeSide = if (mag[0] >= mag[1]) 0 else 1
        }
        when (prefs.cursorMode) {
            Prefs.CursorMode.TRACKPAD -> {
                for (s in 0..1) applyTrackpad(s)
                invalidate()
            }
            Prefs.CursorMode.CONSOLE -> {
                // Step on the event itself for the quickest response; the frame loop handles repeats.
                consoleTick()
                if (console.anyHeld()) startTicking()
            }
            Prefs.CursorMode.RELATIVE -> startTicking()
        }
        return true
    }

    /** Whether to draw the stick cursor dots. Console mode shows only the selected key. */
    val showCursorDots get() = prefs.cursorMode != Prefs.CursorMode.CONSOLE

    // Console mode: discrete steps between keys (see ConsoleNavigator).
    private val console = ConsoleNavigator()
    private val center = FloatArray(2)

    /** Applies any steps due now. Returns true while a stick is still held in a direction. */
    private fun consoleTick(): Boolean {
        val now = SystemClock.uptimeMillis()
        val delay = prefs.consoleDelayMs.toLong()
        val interval = (1000 / prefs.consoleRate.coerceAtLeast(1)).toLong()
        var stepped = false
        for (s in 0..1) {
            val dir = console.update(s, stickX[s], stickY[s], now, delay, interval)
            if (dir >= 0) {
                consoleStep(s, ConsoleNavigator.DX[dir], ConsoleNavigator.DY[dir])
                stepped = true
            }
        }
        if (stepped) invalidate()
        return console.anyHeld()
    }

    private fun consoleStep(side: Int, dx: Int, dy: Int) {
        val half = layout.half(side)
        val key = half.keyAt(cursorX[side], cursorY[side]) ?: return
        val (row0, index0) = half.positionOf(key) ?: return
        val row = (row0 + dy).coerceIn(0, half.rows.lastIndex)
        // Changing rows lands on the key under the goal column, so passing through a wide key
        // (like space) and back returns to the same column.
        var index = if (row == row0) index0 else half.indexAt(row, console.goalX[side])
        if (dx != 0) index = (index + dx).coerceIn(0, half.rows[row].lastIndex)
        if (row == row0 && index == index0) return
        half.centerOf(row, index, center)
        cursorX[side] = center[0]
        cursorY[side] = center[1]
        if (dx != 0) console.goalX[side] = center[0]
    }

    /** Puts the selection on the home key (f / j), or the middle if this layer has none. */
    private fun selectHome(side: Int) {
        val half = layout.half(side)
        val home = half.keyLabeled(HOME_KEYS[side])
        if (home != null) {
            val (r, i) = half.positionOf(home)!!
            half.centerOf(r, i, center)
            cursorX[side] = center[0]
            cursorY[side] = center[1]
        } else {
            cursorX[side] = 0.5f
            cursorY[side] = 0.5f
            snapToKey(side)
        }
        console.goalX[side] = cursorX[side]
    }

    /** Centers the selection on whatever key is under it, e.g. after switching layers. */
    private fun snapToKey(side: Int) {
        val half = layout.half(side)
        val key = half.keyAt(cursorX[side], cursorY[side]) ?: return
        val (r, i) = half.positionOf(key) ?: return
        half.centerOf(r, i, center)
        cursorX[side] = center[0]
        cursorY[side] = center[1]
        console.goalX[side] = center[0]
    }

    private fun Float.toHat() = if (this < -0.5f) -1 else if (this > 0.5f) 1 else 0

    // Trackpad mode: the stick's position is the cursor's position (see TrackpadMapping).
    private val mapped = FloatArray(2)
    private val sticky = arrayOfNulls<Key>(2)
    private val lastAimed = arrayOfNulls<Key>(2)
    private val lastAimedAt = LongArray(2)

    private fun applyTrackpad(side: Int) {
        val half = layout.half(side)
        TrackpadMapping.map(stickX[side], stickY[side], half.trackpadReach, mapped)
        cursorX[side] = mapped[0]
        cursorY[side] = mapped[1]

        // Hysteresis: keep the current key until the cursor is clearly into a neighbor, so stick
        // noise on a border doesn't flicker between two keys.
        val current = sticky[side]
        val b = current?.let { half.boundsOf(it) }
        val keep = b != null && run {
            val mx = (b[2] - b[0]) * STICKY_MARGIN
            val my = (b[3] - b[1]) * STICKY_MARGIN
            mapped[0] >= b[0] - mx && mapped[0] <= b[2] + mx && mapped[1] >= b[1] - my && mapped[1] <= b[3] + my
        }
        if (!keep) sticky[side] = half.keyAt(mapped[0], mapped[1])

        if (!TrackpadMapping.isResting(stickX[side], stickY[side])) {
            lastAimed[side] = sticky[side]
            lastAimedAt[side] = SystemClock.uptimeMillis()
        }
    }

    // Relative mode: integrate stick deflection into cursor velocity once per frame.
    private var ticking = false
    private var lastFrameNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { onFrame(it) }

    private fun startTicking() {
        if (ticking) return
        ticking = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun onFrame(now: Long) {
        val keepGoing = if (prefs.cursorMode == Prefs.CursorMode.CONSOLE) consoleTick() else relativeFrame(now)
        invalidate()
        if (keepGoing) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } else {
            ticking = false
        }
    }

    /** Relative mode: moves each cursor by its stick's velocity. Returns true while a stick is held. */
    private fun relativeFrame(now: Long): Boolean {
        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = now
        var moving = false
        for (s in 0..1) {
            val mag = hypot(stickX[s], stickY[s])
            if (mag <= DEADZONE) continue
            moving = true
            val n = ((mag - DEADZONE) / (1f - DEADZONE)).coerceIn(0f, 1f)
            val speed = prefs.cursorSpeed * n.pow(CURVE)
            cursorX[s] = (cursorX[s] + stickX[s] / mag * speed * dt).coerceIn(0f, Half.MAX_COORD)
            cursorY[s] = (cursorY[s] + stickY[s] / mag * speed * dt * halfAspect).coerceIn(0f, Half.MAX_COORD)
        }
        return moving
    }

    fun stop() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        ticking = false
        handler.removeCallbacks(clearFlash)
    }

    // Many controllers report triggers and the D-pad both as axes and as buttons. Whichever
    // arrives first wins; the other source is ignored if it follows within DEDUPE_MS.
    private val lastFire = HashMap<Int, Pair<Long, Int>>()

    private fun firstOf(id: Int, src: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val last = lastFire[id]
        if (last != null && last.second != src && now - last.first < DEDUPE_MS) return false
        lastFire[id] = now to src
        return true
    }

    private fun axisTrigger(side: Int, v: Float) {
        if (!triggerDown[side] && v > TRIGGER_PRESS) {
            triggerDown[side] = true
            trigger(side, SRC_AXIS)
        } else if (triggerDown[side] && v < TRIGGER_RELEASE) {
            triggerDown[side] = false
        }
    }

    /** A trigger press (button or axis). Types, unless shoulders and triggers are swapped. */
    private fun trigger(side: Int, src: Int) {
        if (!firstOf(TRIGGER_ID_BASE + side, src)) return
        if (prefs.swapShoulders) modifier(side) else typeSide(side)
    }

    /** A shoulder press. Shift / symbols, unless shoulders and triggers are swapped. */
    private fun shoulder(side: Int) {
        if (prefs.swapShoulders) typeSide(side) else modifier(side)
    }

    private fun modifier(side: Int) = pressType(if (side == 0) KeyType.SHIFT else KeyType.LAYER)

    /** Legend names for the typing buttons and the shift / symbols buttons. */
    val typeButtons get() = if (prefs.swapShoulders) "L1 / R1" else "L2 / R2"
    val shiftButton get() = if (prefs.swapShoulders) "L2" else "L1"
    val layerButton get() = if (prefs.swapShoulders) "R2" else "R1"

    private fun arrow(keyCode: Int, src: Int, repeat: Boolean) {
        if (repeat || firstOf(keyCode, src)) ime.sendArrow(keyCode)
    }

    companion object {
        private val HOME_X = floatArrayOf(0.7f, 0.3f) // 'f' and 'j'
        private val HOME_KEYS = arrayOf("f", "j")
        private const val DEADZONE = 0.2f
        private const val CURVE = 1.6f
        private const val STICKY_MARGIN = 0.2f
        private const val RELEASE_GRACE_MS = 90L
        private const val TRIGGER_PRESS = 0.55f
        private const val TRIGGER_RELEASE = 0.25f
        private const val DEDUPE_MS = 150L
        private const val DOUBLE_TAP_MS = 400L
        private const val FLASH_MS = 140L
        private const val SRC_KEY = 1
        private const val SRC_AXIS = 2
        private const val TRIGGER_ID_BASE = 10_000
    }
}
