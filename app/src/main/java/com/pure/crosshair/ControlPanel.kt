package com.pure.crosshair

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import com.pure.crosshair.databinding.OverlayPanelBinding

/**
 * The tuning panel that opens from the floating button.
 *
 * It is its own window rather than an activity so the app underneath keeps running and
 * stays visible while the crosshair is being lined up.
 */
class ControlPanel(
    private val context: Context,
    private val prefs: Prefs,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** A value changed and the crosshair needs redrawing. */
        fun onConfigChanged()

        /** Move mode was switched on or off. */
        fun onMoveModeChanged(enabled: Boolean)

        /** The user asked to shut the overlay down. */
        fun onStopRequested()
    }

    private val wm = context.windowManager()
    private var binding: OverlayPanelBinding? = null

    /** Guards against slider listeners firing while we push values in programmatically. */
    private var syncing = false

    var moveMode = false
        private set

    val isShowing: Boolean get() = binding != null

    fun toggle() = if (isShowing) hide() else show()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        val themed = ContextThemeWrapper(context, R.style.Theme_Crosshair_Overlay)
        val b = OverlayPanelBinding.inflate(LayoutInflater.from(themed))
        binding = b

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            CrosshairLayer.APP_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            windowAnimations = 0
        }

        buildPicker(b, themed)
        wireControls(b)
        syncFromPrefs()

        // Tap anywhere outside the card, or press back, to close.
        b.panelRoot.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                hide()
                true
            } else {
                false
            }
        }
        b.panelRoot.isFocusableInTouchMode = true
        b.panelRoot.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                hide()
                true
            } else {
                false
            }
        }

        val added = runCatching { wm.addView(b.panelRoot, lp) }.isSuccess
        if (!added) {
            binding = null
            return
        }
        b.panelRoot.requestFocus()

        b.panelCard.post {
            b.panelCard.translationY = b.panelCard.height.toFloat()
            b.panelCard.alpha = 0f
            b.panelCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(200)
                .start()
        }

        scrollPickerToSelection(b)
    }

    fun hide() {
        val b = binding ?: return
        binding = null
        if (moveMode) setMoveMode(false, b)
        runCatching { wm.removeViewImmediate(b.panelRoot) }
    }

    /** Called while the crosshair is dragged so the X/Y sliders keep up. */
    fun syncPosition() {
        val b = binding ?: return
        val screen = context.screenSize()
        syncing = true
        b.seekX.progress = (prefs.offsetX + screen.x / 2).coerceIn(0, screen.x)
        b.seekY.progress = (prefs.offsetY + screen.y / 2).coerceIn(0, screen.y)
        syncing = false
        b.valueX.text = signed(prefs.offsetX)
        b.valueY.text = signed(prefs.offsetY)
    }

    fun syncFromPrefs() {
        val b = binding ?: return
        val screen = context.screenSize()

        syncing = true
        b.seekSize.max = Prefs.MAX_SIZE - Prefs.MIN_SIZE
        b.seekSize.progress = prefs.sizeDp - Prefs.MIN_SIZE

        b.seekOpacity.max = 100 - Prefs.MIN_OPACITY
        b.seekOpacity.progress = prefs.opacity - Prefs.MIN_OPACITY

        b.seekX.max = screen.x
        b.seekX.progress = (prefs.offsetX + screen.x / 2).coerceIn(0, screen.x)

        b.seekY.max = screen.y
        b.seekY.progress = (prefs.offsetY + screen.y / 2).coerceIn(0, screen.y)
        syncing = false

        b.valueSize.text = context.getString(R.string.value_dp, prefs.sizeDp)
        b.valueOpacity.text = context.getString(R.string.value_percent, prefs.opacity)
        b.valueX.text = signed(prefs.offsetX)
        b.valueY.text = signed(prefs.offsetY)

        b.btnVisible.isSelected = prefs.visible
        b.btnVisible.setText(if (prefs.visible) R.string.crosshair_on else R.string.crosshair_off)
        b.btnMove.isSelected = moveMode

        updatePickerSelection(b)
        updateOpacityWarning(b)
    }

    /**
     * Android 12 blocks touches that pass through an overlay more than 80% opaque, to stop
     * tapjacking. Accessibility overlays are exempt, so max priority mode is the way to get a
     * solid crosshair that still lets taps through. Warn rather than cap: on Android 11 and
     * below, and in max priority mode, 100% is completely fine.
     */
    private fun updateOpacityWarning(b: OverlayPanelBinding) {
        val trusted = prefs.maxPriority && Bridge.maxPriorityConnected
        val blocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            prefs.opacity > MAX_PASS_THROUGH_OPACITY &&
            !trusted
        b.opacityWarning.visibility = if (blocked) View.VISIBLE else View.GONE
    }

    // ---------------------------------------------------------------- internals

    private fun buildPicker(b: OverlayPanelBinding, themed: Context) {
        val cell = context.dp(48)
        val gap = context.dp(6)
        val pad = context.dp(6)

        for (i in 0 until Catalog.size) {
            val thumb = ImageView(themed).apply {
                layoutParams = LinearLayout.LayoutParams(cell, cell).also { it.marginEnd = gap }
                setPadding(pad, pad, pad, pad)
                setImageResource(Catalog.at(i))
                setBackgroundResource(R.drawable.bg_thumb)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = context.getString(R.string.crosshair_n, i + 1)
                setOnClickListener {
                    prefs.index = i
                    updatePickerSelection(b)
                    callbacks.onConfigChanged()
                }
            }
            b.pickerRow.addView(thumb)
        }
    }

    private fun updatePickerSelection(b: OverlayPanelBinding) {
        for (i in 0 until b.pickerRow.childCount) {
            b.pickerRow.getChildAt(i).isSelected = (i == prefs.index)
        }
    }

    private fun scrollPickerToSelection(b: OverlayPanelBinding) {
        b.pickerScroll.post {
            val child = b.pickerRow.getChildAt(prefs.index) ?: return@post
            b.pickerScroll.smoothScrollTo(
                child.left - b.pickerScroll.width / 2 + child.width / 2,
                0
            )
        }
    }

    private fun wireControls(b: OverlayPanelBinding) {
        b.panelClose.setOnClickListener { hide() }

        b.btnVisible.setOnClickListener {
            prefs.visible = !prefs.visible
            b.btnVisible.isSelected = prefs.visible
            b.btnVisible.setText(if (prefs.visible) R.string.crosshair_on else R.string.crosshair_off)
            callbacks.onConfigChanged()
        }

        b.btnMove.setOnClickListener { setMoveMode(!moveMode, b) }

        b.btnCenter.setOnClickListener {
            prefs.resetPlacement()
            syncFromPrefs()
            callbacks.onConfigChanged()
        }

        b.btnStop.setOnClickListener { callbacks.onStopRequested() }

        b.seekSize.setOnSeekBarChangeListener(onChange { value ->
            prefs.sizeDp = Prefs.MIN_SIZE + value
            b.valueSize.text = context.getString(R.string.value_dp, prefs.sizeDp)
        })

        b.seekOpacity.setOnSeekBarChangeListener(onChange { value ->
            prefs.opacity = Prefs.MIN_OPACITY + value
            b.valueOpacity.text = context.getString(R.string.value_percent, prefs.opacity)
            updateOpacityWarning(b)
        })

        b.seekX.setOnSeekBarChangeListener(onChange { value ->
            prefs.offsetX = value - context.screenSize().x / 2
            b.valueX.text = signed(prefs.offsetX)
        })

        b.seekY.setOnSeekBarChangeListener(onChange { value ->
            prefs.offsetY = value - context.screenSize().y / 2
            b.valueY.text = signed(prefs.offsetY)
        })

        b.stepXMinus.setOnClickListener { nudgeX(b, -1) }
        b.stepXPlus.setOnClickListener { nudgeX(b, 1) }
        b.stepYMinus.setOnClickListener { nudgeY(b, -1) }
        b.stepYPlus.setOnClickListener { nudgeY(b, 1) }
    }

    private fun nudgeX(b: OverlayPanelBinding, delta: Int) {
        val half = context.screenSize().x / 2
        prefs.offsetX = (prefs.offsetX + delta).coerceIn(-half, half)
        syncing = true
        b.seekX.progress = prefs.offsetX + half
        syncing = false
        b.valueX.text = signed(prefs.offsetX)
        callbacks.onConfigChanged()
    }

    private fun nudgeY(b: OverlayPanelBinding, delta: Int) {
        val half = context.screenSize().y / 2
        prefs.offsetY = (prefs.offsetY + delta).coerceIn(-half, half)
        syncing = true
        b.seekY.progress = prefs.offsetY + half
        syncing = false
        b.valueY.text = signed(prefs.offsetY)
        callbacks.onConfigChanged()
    }

    private fun setMoveMode(enabled: Boolean, b: OverlayPanelBinding) {
        moveMode = enabled
        b.btnMove.isSelected = enabled
        b.btnMove.setText(if (enabled) R.string.move_done else R.string.move)
        b.moveHint.visibility = if (enabled) View.VISIBLE else View.GONE
        callbacks.onMoveModeChanged(enabled)
    }

    private fun onChange(apply: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (syncing) return
            apply(progress)
            callbacks.onConfigChanged()
        }

        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) = Unit
    }

    private fun signed(value: Int) = if (value > 0) "+$value" else value.toString()

    companion object {
        /** Matches the platform default for InputManager#getMaximumObscuringOpacityForTouch. */
        private const val MAX_PASS_THROUGH_OPACITY = 80
    }
}
