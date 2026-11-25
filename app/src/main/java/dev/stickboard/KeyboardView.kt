package dev.stickboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View

/** Draws the two keyboard halves, the stick cursors and, when there is room, a controls legend between them. */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val kc: KeyboardController) : View(context) {

    /** Whoever can move this keyboard's window. Set it to show the drag handle. */
    interface DragHost {
        /** False when there is no room to move, for example at full screen height. */
        fun canDrag(): Boolean
        fun onDragStart()
        /** [dy] is the finger's movement since the drag started, in screen pixels. */
        fun onDragMove(dy: Float)
        fun onDragEnd()
    }

    var dragHost: DragHost? = null
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val halves = arrayOf(RectF(), RectF())
    private val legendRect = RectF()
    private val panelRect = RectF()
    private var showLegend = false
    private val keyRect = RectF()
    private val handleRect = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HINT_TEXT; textAlign = Paint.Align.RIGHT }
    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kc.attach(this)
    }

    override fun onDetachedFromWindow() {
        kc.detach(this)
        stopRepeat()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        laidOutWidthPct = -1
    }

    private var laidOutWidthPct = -1

    /**
     * Lays out the halves for the current size and width setting. The halves stay docked to the
     * left and right edges; the width setting sets how much of the screen they cover together.
     */
    private fun ensureLayout() {
        val pct = kc.keyboardWidthPct
        if (pct == laidOutWidthPct || width == 0) return
        laidOutWidthPct = pct
        val w = width
        val h = height
        val pad = 6 * dp
        val gap = maxOf(w * (100 - pct) / 100f, MIN_GAP * dp)
        val halfW = (w - 2 * pad - gap) / 2
        halves[0].set(pad, pad, pad + halfW, h - pad)
        halves[1].set(w - pad - halfW, pad, w - pad, h - pad)
        legendRect.set(halves[0].right, pad, halves[1].left, h - pad)
        showLegend = gap >= 100 * dp
        if (h > 0) kc.halfAspect = halfW / (h - 2 * pad)
    }

    private fun legendShown() = showLegend && kc.controllerPresent && !kc.transparentMiddle

    /**
     * Drag handle: half a key wide and one key tall, using the face size of a key in the five-key
     * rows. Centered in the gap, or at the top of it when the legend is there too.
     */
    private fun layoutHandle() {
        val hr = halves[0]
        val keyW = hr.width() / 5 - 2 * KEY_INSET * dp
        val keyH = hr.height() / kc.layout.left.rows.size - 2 * KEY_INSET * dp
        val w = keyW / 2
        val cx = (halves[0].right + halves[1].left) / 2
        val top = if (legendShown()) hr.top + KEY_INSET * dp else hr.centerY() - keyH / 2
        handleRect.set(cx - w / 2, top, cx + w / 2, top + keyH)
    }

    /** The space between the halves, in this view's coordinates. */
    fun gapRect(out: RectF) {
        ensureLayout()
        out.set(halves[0].right, 0f, halves[1].left, height.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        ensureLayout()
        if (kc.transparentMiddle) {
            // Only the halves get a backdrop; the gap shows whatever is behind the keyboard.
            fill.color = BG
            for (hr in halves) {
                panelRect.set(hr)
                panelRect.inset(-PANEL_PAD * dp, -PANEL_PAD * dp)
                canvas.drawRoundRect(panelRect, 12 * dp, 12 * dp, fill)
            }
        } else {
            canvas.drawColor(BG)
        }
        val layout = kc.layout
        val inset = KEY_INSET * dp
        val radius = 7 * dp
        // Key faces, then cursors, then labels, so a cursor never hides the letter it's on.
        for (side in 0..1) {
            val hr = halves[side]
            val hovered = kc.hoveredKey(side)
            layout.half(side).forEachKey { key, l, t, r, b ->
                setKeyRect(hr, l, t, r, b, inset)
                val pressed = isTouched(key) || kc.isFlashed(key)
                fill.color = when {
                    pressed -> PRESSED
                    key === hovered -> HOVER[side]
                    key.type == KeyType.SHIFT && kc.shift != KeyboardController.Shift.OFF -> ACTIVE_MOD
                    key.type != KeyType.CHAR -> SPECIAL
                    else -> KEY
                }
                canvas.drawRoundRect(keyRect, radius, radius, fill)
                if (key === hovered) {
                    stroke.color = CURSOR[side]
                    stroke.strokeWidth = 2.5f * dp
                    canvas.drawRoundRect(keyRect, radius, radius, stroke)
                }
            }
        }
        if (kc.cursorsVisible && kc.showCursorDots) {
            for (side in 0..1) {
                val hr = halves[side]
                val cx = hr.left + kc.cursorX[side] * hr.width()
                val cy = hr.top + kc.cursorY[side] * hr.height()
                // In trackpad mode a centered stick is like a lifted finger: show the cursor faintly.
                val resting = kc.cursorResting(side)
                fill.color = CURSOR[side]
                fill.alpha = if (resting) 60 else 150
                canvas.drawCircle(cx, cy, 7 * dp, fill)
                fill.alpha = 255
                stroke.color = 0xFFFFFFFF.toInt()
                stroke.alpha = if (resting) 90 else 255
                stroke.strokeWidth = 1.5f * dp
                canvas.drawCircle(cx, cy, 7 * dp, stroke)
                stroke.alpha = 255
            }
        }
        for (side in 0..1) {
            val hr = halves[side]
            layout.half(side).forEachKey { key, l, t, r, b ->
                setKeyRect(hr, l, t, r, b, inset)
                drawLabel(canvas, key)
            }
        }
        layoutHandle()
        if (legendShown()) drawLegend(canvas)
        if (handleVisible()) drawHandle(canvas)
    }

    private fun handleVisible() =
        dragHost?.canDrag() == true && handleRect.width() <= halves[1].left - halves[0].right

    private fun drawHandle(canvas: Canvas) {
        fill.color = if (dragPointer != NO_POINTER) ACTIVE_MOD else SPECIAL
        canvas.drawRoundRect(handleRect, 7 * dp, 7 * dp, fill)
        // Grip: two columns of three dots.
        fill.color = HINT_TEXT
        val r = minOf(2.2f * dp, handleRect.width() / 10)
        val dx = handleRect.width() / 5
        val dy = minOf(handleRect.height() / 6, 7 * dp)
        for (col in -1..1 step 2) for (row in -1..1) {
            canvas.drawCircle(handleRect.centerX() + col * dx / 2 * 1.4f, handleRect.centerY() + row * dy, r, fill)
        }
    }

    private fun setKeyRect(hr: RectF, l: Float, t: Float, r: Float, b: Float, inset: Float) {
        keyRect.set(
            hr.left + l * hr.width() + inset, hr.top + t * hr.height() + inset,
            hr.left + r * hr.width() - inset, hr.top + b * hr.height() - inset,
        )
    }

    private fun labelFor(key: Key): String {
        val shifted = kc.shift != KeyboardController.Shift.OFF
        return when (key.type) {
            KeyType.CHAR -> if (shifted) key.shifted ?: key.label.uppercase() else key.label
            KeyType.SHIFT -> if (kc.shift == KeyboardController.Shift.LOCK) "⇪" else "⇧"
            KeyType.LAYER -> if (kc.symbols) "ABC" else "?123"
            KeyType.ENTER -> kc.enterLabel
            else -> key.label
        }
    }

    private fun drawLabel(canvas: Canvas, key: Key) {
        val text = labelFor(key)
        val w = keyRect.width()
        val h = keyRect.height()
        var size = minOf(h * 0.42f, w * 0.55f, 30 * dp)
        if (text.length > 1) size *= 0.7f
        label.textSize = size
        val measured = label.measureText(text)
        if (measured > w * 0.85f) label.textSize = size * w * 0.85f / measured
        val baseline = keyRect.centerY() - (label.descent() + label.ascent()) / 2
        canvas.drawText(text, keyRect.centerX(), baseline, label)

        // Secondary character, shown small in the corner while shift is off.
        if (key.shifted != null && kc.shift == KeyboardController.Shift.OFF) {
            hint.textSize = size * 0.45f
            canvas.drawText(key.shifted, keyRect.right - 4 * dp, keyRect.top + hint.textSize + 2 * dp, hint)
        }
    }

    private fun drawLegend(canvas: Canvas) {
        val legend = listOf(
            kc.typeButtons to "type",
            "A" to "type",
            "X" to "⌫",
            "Y" to "space",
            kc.shiftButton to "shift",
            kc.layerButton to if (kc.symbols) "ABC" else "?123",
            "Start" to "enter",
            "D-pad" to "caret",
            "B" to "hide",
            "Select" to if (kc.dualScreen) "move" else "switch",
        )
        // Leave the top of the gap to the drag handle when there is one.
        val areaTop = if (handleVisible()) handleRect.bottom + 4 * dp else legendRect.top
        val areaH = legendRect.bottom - areaTop
        val rowH = minOf(areaH / legend.size, 26 * dp)
        val top = areaTop + areaH / 2 - rowH * legend.size / 2
        val mid = legendRect.centerX() + 4 * dp
        legendPaint.textSize = rowH * 0.5f
        legend.forEachIndexed { i, (button, action) ->
            val y = top + rowH * i + rowH * 0.68f
            legendPaint.color = LEGEND_BUTTON
            legendPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(button, mid - 6 * dp, y, legendPaint)
            legendPaint.color = HINT_TEXT
            legendPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(action, mid + 2 * dp, y, legendPaint)
        }
    }

    // ---- touch ----

    private val touches = SparseArray<Key>()
    private val handler = Handler(Looper.getMainLooper())
    private var repeating: Key? = null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = repeating ?: return
            kc.press(key, haptic = false)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private fun isTouched(key: Key): Boolean {
        for (i in 0 until touches.size()) if (touches.valueAt(i) === key) return true
        return false
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (side in 0..1) {
            val hr = halves[side]
            if (hr.contains(x, y)) {
                return kc.layout.half(side).keyAt((x - hr.left) / hr.width(), (y - hr.top) / hr.height())
            }
        }
        return null
    }

    private fun stopRepeat() {
        repeating = null
        handler.removeCallbacks(repeatRunnable)
    }

    private var dragPointer = NO_POINTER
    private var dragStartRawY = 0f

    /** Handles the drag pointer, if [e] concerns it. Returns true when the event was used. */
    private fun handleDrag(e: MotionEvent): Boolean {
        val i = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                layoutHandle()
                if (dragPointer != NO_POINTER || !handleVisible()) return false
                val slop = 8 * dp
                val x = e.getX(i)
                val y = e.getY(i)
                if (x < handleRect.left - slop || x > handleRect.right + slop ||
                    y < handleRect.top - slop || y > handleRect.bottom + slop
                ) return false
                dragPointer = e.getPointerId(i)
                dragStartRawY = e.getRawY(i)
                dragHost?.onDragStart()
                kc.hapticOnly()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(dragPointer)
                if (idx >= 0) dragHost?.onDragMove(e.getRawY(idx) - dragStartRawY)
                return false // other pointers may be sliding across keys
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val ending = e.actionMasked == MotionEvent.ACTION_CANCEL || e.getPointerId(i) == dragPointer
                if (!ending || dragPointer == NO_POINTER) return false
                dragPointer = NO_POINTER
                dragHost?.onDragEnd()
                invalidate()
                return e.actionMasked != MotionEvent.ACTION_CANCEL
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        ensureLayout()
        if (handleDrag(e)) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                kc.onTouchStart()
                val i = e.actionIndex
                val key = keyAt(e.getX(i), e.getY(i))
                touches.put(e.getPointerId(i), key)
                if (key != null) {
                    if (fireOnDown(key)) {
                        kc.press(key)
                        if (key.type == KeyType.BACKSPACE) {
                            repeating = key
                            handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                        }
                    } else {
                        kc.hapticOnly()
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding a finger moves the press to the key it ends on.
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == dragPointer) continue
                    val old = touches.get(id)
                    val now = keyAt(e.getX(i), e.getY(i))
                    if (old !== now && old?.let { fireOnDown(it) } != true) {
                        touches.put(id, now)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                val key = touches.get(id)
                touches.remove(id)
                if (key != null && !fireOnDown(key)) kc.press(key, haptic = false)
                if (key === repeating) stopRepeat()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                touches.clear()
                stopRepeat()
                invalidate()
            }
        }
        return true
    }

    /** Backspace and modifiers act as soon as they're touched; everything else types on release. */
    private fun fireOnDown(key: Key) = key.type == KeyType.BACKSPACE || key.isModifier

    companion object {
        private const val REPEAT_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L

        const val BACKGROUND = 0xFF15171B.toInt()
        private const val BG = BACKGROUND
        private const val PANEL_PAD = 5f
        private const val KEY_INSET = 2.5f
        private const val MIN_GAP = 12f
        private const val NO_POINTER = -1
        private const val KEY = 0xFF2C2F36.toInt()
        private const val SPECIAL = 0xFF3A3E47.toInt()
        private const val ACTIVE_MOD = 0xFF4D7CFE.toInt()
        private const val PRESSED = 0xFF6B7280.toInt()
        private const val TEXT = 0xFFEEF0F4.toInt()
        private const val HINT_TEXT = 0xFF9AA0AA.toInt()
        private const val LEGEND_BUTTON = 0xFFD8DCE3.toInt()
        private val CURSOR = intArrayOf(0xFF3FD0FF.toInt(), 0xFFFFB03F.toInt())
        private val HOVER = intArrayOf(0xFF1F4E5E.toInt(), 0xFF5E461F.toInt())
    }
}
