package dev.stickboard

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hosts the keyboard on a screen other than the one the IME window is on, as an overlay that
 * never takes focus, so the app keeps its focused text field and keeps routing input to us.
 */
class SecondaryScreen(private val ime: Context, private val kc: KeyboardController, private val prefs: Prefs) :
    KeyboardView.DragHost {

    private var wm: WindowManager? = null
    private var root: View? = null
    private var preview: TextView? = null
    var displayId = -1
        private set

    val isShowing get() = root != null

    /** Returns false if the overlay could not be added (for example, permission missing). */
    fun show(display: Display): Boolean {
        if (root != null && displayId == display.displayId) {
            updateLayout()
            applyStyle()
            return true
        }
        hide()
        val displayCtx = ime.createDisplayContext(display)
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayCtx.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayCtx
        }
        val wm = ctx.getSystemService(WindowManager::class.java)
        val view = buildViews(ctx)
        try {
            wm.addView(view, layoutParams(ctx))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't show keyboard on display ${display.displayId}", e)
            return false
        }
        this.wm = wm
        root = view
        displayId = display.displayId
        applyStyle()
        return true
    }

    fun hide() {
        val view = root ?: return
        try {
            wm?.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "removeView failed", e)
        }
        root = null
        preview = null
        wm = null
        displayId = -1
    }

    private fun buildViews(ctx: Context): View {
        val dp = ctx.resources.displayMetrics.density
        val preview = TextView(ctx).apply {
            setTextColor(0xFFEEF0F4.toInt())
            setHintTextColor(0xFF7A808A.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
            setBackgroundColor(0xFF23262C.toInt())
        }
        this.preview = preview
        // No background here: KeyboardView paints its own, so the middle can be left see-through.
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val keyboard = KeyboardView(ctx, kc).also { it.dragHost = this@SecondaryScreen }
            addView(keyboard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    private fun screenHeight(ctx: Context): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.height()
        } else {
            ctx.resources.displayMetrics.heightPixels
        }

    private fun heightFor(ctx: Context): Int {
        val pct = prefs.secondaryHeightPct.coerceIn(30, 100)
        return if (pct >= 100) WindowManager.LayoutParams.MATCH_PARENT
        else screenHeight(ctx) * pct / 100
    }

    /** How far the window can move down from the top of the screen. */
    private fun freeSpace(ctx: Context, height: Int): Int =
        if (height == WindowManager.LayoutParams.MATCH_PARENT) 0 else (screenHeight(ctx) - height).coerceAtLeast(0)

    private fun yFor(ctx: Context, height: Int): Int =
        (freeSpace(ctx, height) * prefs.secondaryPosition.coerceIn(0f, 1f)).toInt()

    private fun layoutParams(ctx: Context) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        heightFor(ctx),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP
        y = yFor(ctx, height)
        title = "StickBoard"
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    /** Applies settings that can change while the overlay stays up. */
    private fun applyStyle() {
        preview?.visibility = if (prefs.showPreview) View.VISIBLE else View.GONE
        root?.invalidate()
    }

    private fun updateLayout() {
        val view = root ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val h = heightFor(view.context)
        val y = yFor(view.context, h)
        if (lp.height != h || lp.y != y) {
            lp.height = h
            lp.y = y
            wm?.updateViewLayout(view, lp)
        }
    }

    // ---- dragging (KeyboardView.DragHost) ----

    private var dragStartY = 0

    override fun canDrag(): Boolean {
        val view = root ?: return false
        return freeSpace(view.context, heightFor(view.context)) > 0
    }

    override fun onDragStart() {
        dragStartY = (root?.layoutParams as? WindowManager.LayoutParams)?.y ?: 0
    }

    override fun onDragMove(dy: Float) {
        val view = root ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val y = (dragStartY + dy).toInt().coerceIn(0, freeSpace(view.context, lp.height))
        if (y != lp.y) {
            lp.y = y
            wm?.updateViewLayout(view, lp)
        }
    }

    override fun onDragEnd() {
        val view = root ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val free = freeSpace(view.context, lp.height)
        if (free > 0) prefs.secondaryPosition = lp.y.toFloat() / free
    }

    /** Shows the text around the caret, since the field itself is on the other screen. */
    fun setPreview(before: CharSequence, after: CharSequence, hint: CharSequence?) {
        val p = preview ?: return
        if (before.isEmpty() && after.isEmpty()) {
            p.text = ""
            p.hint = hint ?: ""
        } else {
            p.text = buildString {
                append(before)
                append('▏')
                append(after)
            }
        }
    }

    companion object {
        private const val TAG = "StickBoard"
    }
}
