package dev.stickboard

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Region
import android.hardware.display.DisplayManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout

class StickBoardIme : InputMethodService() {

    private lateinit var prefs: Prefs
    private lateinit var kc: KeyboardController
    private lateinit var secondary: SecondaryScreen
    private val handler = Handler(Looper.getMainLooper())

    private var container: FrameLayout? = null
    private var inlineView: KeyboardView? = null

    /** Key codes whose down event we consumed, so we also swallow their up event. */
    private val consumedKeys = HashSet<Int>()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplaysChanged()
        override fun onDisplayRemoved(displayId: Int) = onDisplaysChanged()
        override fun onDisplayChanged(displayId: Int) = onDisplaysChanged()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        kc = KeyboardController(this, prefs)
        secondary = SecondaryScreen(this, kc, prefs)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        prefs.onChange { if (isInputViewShown) updateNavBarColor() }
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        secondary.hide()
        kc.stop()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        inlineView = null
        val c = FrameLayout(this)
        // The IME window extends under the navigation bar; keep the keys above it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.setOnApplyWindowInsetsListener { v, insets ->
                val nav = insets.getInsets(WindowInsets.Type.navigationBars())
                v.setPadding(nav.left, 0, nav.right, nav.bottom)
                insets
            }
        }
        container = c
        return c
    }

    override fun onEvaluateFullscreenMode() = false

    private val gap = RectF()
    private val viewPos = IntArray(2)

    /** With a see-through middle, let touches between the halves reach the app underneath. */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = inlineView ?: return
        val decor = window?.window?.decorView ?: return
        if (!prefs.transparentMiddle || !view.isLaidOut) return
        view.gapRect(gap)
        view.getLocationInWindow(viewPos)
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, outInsets.visibleTopInsets, decor.width, decor.height)
        outInsets.touchableRegion.op(
            (viewPos[0] + gap.left).toInt(), (viewPos[1] + gap.top).toInt(),
            (viewPos[0] + gap.right).toInt(), (viewPos[1] + gap.bottom).toInt(),
            Region.Op.DIFFERENCE,
        )
    }

    /** Gamepads count as hardware keyboards on some devices; still show the on-screen keyboard. */
    override fun onEvaluateInputViewShown(): Boolean = super.onEvaluateInputViewShown() || hasGamepad()

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        kc.onStartInput(info)
        updatePlacement()
        updatePreview()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // The window may only now know which display it landed on.
        handler.post { if (isInputViewShown) updatePlacement() }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        secondary.hide()
        kc.stop()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        secondary.hide()
        kc.stop()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        kc.updateAutoCap()
        updatePreview()
    }

    private fun onDisplaysChanged() {
        if (isInputViewShown) updatePlacement()
    }

    // ---- placement ----

    private fun imeDisplayId(): Int {
        window?.window?.decorView?.display?.let { return it.displayId }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { display }.getOrNull()?.let { return it.displayId }
        }
        return Display.DEFAULT_DISPLAY
    }

    /**
     * Puts the keyboard on the configured screen. If that's the screen the app is on (or there is
     * only one screen), it goes in the normal IME window under the app. Otherwise the IME window
     * stays empty and the keyboard is drawn on the other screen.
     */
    private fun updatePlacement() {
        val target = DisplayPicker.keyboardDisplay(this, prefs)
        val imeDisplay = imeDisplayId()
        val canGoElsewhere = target != null && target.displayId != imeDisplay &&
            Settings.canDrawOverlays(this) && !editorHidesOverlays()
        kc.dualScreen = canGoElsewhere
        val elsewhere = canGoElsewhere && !keepUnderApp && secondary.show(target!!)
        if (!elsewhere) secondary.hide()
        setInline(!elsewhere)
        kc.invalidate()
        Log.i(TAG, "placement: ime display=$imeDisplay target=${target?.displayId} elsewhere=$elsewhere")
    }

    /** Set with the Select button: keep the keyboard under the app even when another screen is available. */
    private var keepUnderApp = false

    fun onSelectButton() {
        if (kc.dualScreen) {
            keepUnderApp = !keepUnderApp
            updatePlacement()
        } else {
            showPicker()
        }
    }

    /**
     * Apps that can hide other apps' overlays (Settings, the permission controller, sign-in
     * screens) would make a keyboard on the other screen invisible, so draw it under them instead.
     */
    private fun editorHidesOverlays(): Boolean {
        val pkg = currentInputEditorInfo?.packageName ?: return false
        return OVERLAY_HIDING_PERMISSIONS.any {
            packageManager.checkPermission(it, pkg) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun setInline(show: Boolean) {
        val c = container ?: return
        if (show) {
            val h = inlineHeight()
            val view = inlineView ?: KeyboardView(this, kc).also {
                inlineView = it
                c.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            }
            if (view.layoutParams.height != h) {
                view.layoutParams.height = h
                view.requestLayout()
            }
        } else {
            inlineView?.let { c.removeView(it) }
            inlineView = null
        }
        updateNavBarColor()
    }

    /**
     * With gesture navigation the IME window also covers the navigation bar strip (where Android
     * puts its hide-keyboard and switch-keyboard buttons), and paints it black by default. Under
     * the keyboard, paint it the keyboard's color so the keyboard reaches the screen edge. When the
     * keys are on the other screen, or the middle is see-through, keep it clear so the app's own
     * navigation bar shows instead of a black strip.
     */
    private fun updateNavBarColor() {
        val solid = inlineView != null && !prefs.transparentMiddle
        @Suppress("DEPRECATION")
        window.window?.navigationBarColor = if (solid) KeyboardView.BACKGROUND else Color.TRANSPARENT
    }

    private fun inlineHeight(): Int {
        val dm = resources.displayMetrics
        val byShare = dm.heightPixels * prefs.inlineHeightPct.coerceIn(25, 70) / 100
        val cap = (340 * dm.density).toInt()
        return minOf(byShare, cap)
    }

    private fun updatePreview() {
        if (!secondary.isShowing || !prefs.showPreview) return
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val variation = info?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: 0
        val cls = info?.inputType?.and(InputType.TYPE_MASK_CLASS) ?: 0
        val password = cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        fun clean(s: CharSequence?) = (s ?: "").toString().replace('\n', '⏎').let {
            if (password) "•".repeat(it.length) else it
        }
        val before = clean(ic.getTextBeforeCursor(PREVIEW_BEFORE, 0))
        val after = clean(ic.getTextAfterCursor(PREVIEW_AFTER, 0))
        secondary.setPreview(before, after, info?.hintText)
    }

    // ---- gamepad input ----

    private fun hasGamepad() = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true
    }

    private fun isFromGamepad(event: KeyEvent): Boolean {
        if (KeyEvent.isGamepadButton(event.keyCode)) return true
        if (event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK)) return true
        // D-pad keys from a gamepad sometimes only carry SOURCE_DPAD; check the device itself.
        return InputDevice.getDevice(event.deviceId)?.supportsSource(InputDevice.SOURCE_GAMEPAD) == true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isInputViewShown && isFromGamepad(event) && kc.onGamepadKeyDown(keyCode, event)) {
            consumedKeys += keyCode
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (consumedKeys.remove(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isInputViewShown && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            kc.onJoystickMotion(event)
            // Swallow stick motion so the app doesn't also scroll or turn it into D-pad presses.
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    // ---- editing, called by KeyboardController ----

    private val isRawKeyEditor get() =
        (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL

    fun commit(text: String) {
        val ic = currentInputConnection ?: return
        if (isRawKeyEditor && text.length == 1) sendKeyChar(text[0]) else ic.commitText(text, 1)
    }

    fun backspace() {
        val ic = currentInputConnection ?: return
        if (isRawKeyEditor) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            return
        }
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        val before = ic.getTextBeforeCursor(2, 0)
        when {
            before.isNullOrEmpty() -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            before.length == 2 && Character.isSurrogatePair(before[0], before[1]) -> ic.deleteSurroundingText(2, 0)
            else -> ic.deleteSurroundingText(1, 0)
        }
    }

    fun enter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 ||
            action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
        val multiline = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        when {
            !noAction -> ic.performEditorAction(action)
            multiline -> ic.commitText("\n", 1)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    fun sendArrow(keyCode: Int) = sendDownUpKeyEvents(keyCode)

    fun hideKeyboard() = requestHideSelf(0)

    fun showPicker() = getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    fun capsModeActive(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val ic = currentInputConnection ?: return false
        return ic.getCursorCapsMode(info.inputType) != 0
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
    }

    fun haptic() {
        if (!prefs.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
        } else {
            v.vibrate(effect)
        }
    }

    companion object {
        private const val TAG = "StickBoard"
        private const val PREVIEW_BEFORE = 40
        private const val PREVIEW_AFTER = 30
        private val OVERLAY_HIDING_PERMISSIONS = listOf(
            "android.permission.HIDE_NON_SYSTEM_OVERLAY_WINDOWS",
            "android.permission.HIDE_OVERLAY_WINDOWS",
        )
    }
}
