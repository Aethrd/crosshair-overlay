package com.pure.crosshair

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import com.pure.crosshair.databinding.OverlayPanelBinding
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

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

        /** The user tapped the import tile. */
        fun onImportRequested()

        /** Move mode was switched on or off. */
        fun onMoveModeChanged(enabled: Boolean)

        /** The user asked to shut the overlay down. */
        fun onStopRequested()
    }

    private val wm = context.windowManager()
    private val library = Library(context)
    private var binding: OverlayPanelBinding? = null
    private var themed: Context? = null

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
        this.themed = themed
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

        buildPicker(b)
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
        b.seekSize.max = SIZE_STEPS
        b.seekSize.progress = sizeToProgress(prefs.sizeDp)

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

    /** Called after an import or delete so the strip reflects the library. */
    fun reloadLibrary() {
        val b = binding ?: return
        buildPicker(b)
        syncFromPrefs()
    }

    private fun buildPicker(b: OverlayPanelBinding) {
        val themed = this.themed ?: return
        val cell = context.dp(48)
        val gap = context.dp(6)
        val pad = context.dp(6)

        b.pickerRow.removeAllViews()

        // Import tile always sits first, so there is a way in even when the library is empty.
        val add = TextView(themed).apply {
            layoutParams = LinearLayout.LayoutParams(cell, cell).also { it.marginEnd = gap }
            text = "+"
            gravity = Gravity.CENTER
            textSize = 22f
            setTextColor(context.getColor(R.color.accent))
            setBackgroundResource(R.drawable.bg_thumb)
            contentDescription = context.getString(R.string.import_image)
            setOnClickListener { callbacks.onImportRequested() }
        }
        b.pickerRow.addView(add)

        val images = library.list()
        b.pickerEmpty.visibility = if (images.isEmpty()) View.VISIBLE else View.GONE

        for (file in images) {
            val bitmap = Library.decode(file, Library.THUMB_PX) ?: continue
            val thumb = ImageView(themed).apply {
                layoutParams = LinearLayout.LayoutParams(cell, cell).also { it.marginEnd = gap }
                setPadding(pad, pad, pad, pad)
                setImageBitmap(bitmap)
                setBackgroundResource(R.drawable.bg_thumb)
                scaleType = ImageView.ScaleType.FIT_CENTER
                isSelected = file.name == prefs.selected
                contentDescription = context.getString(R.string.imported_crosshair)
                setOnClickListener {
                    prefs.selected = file.name
                    updatePickerSelection(b)
                    callbacks.onConfigChanged()
                }
                setOnLongClickListener {
                    removeImage(file.name)
                    true
                }
            }
            b.pickerRow.addView(thumb)
        }
    }

    /** Long press deletes. The original file on the device is untouched. */
    private fun removeImage(name: String) {
        val fallback = library.neighbourOf(name)
        library.delete(name)
        if (prefs.selected == name) prefs.selected = fallback.orEmpty()
        Toast.makeText(context, R.string.crosshair_removed, Toast.LENGTH_SHORT).show()
        reloadLibrary()
        callbacks.onConfigChanged()
    }

    private fun updatePickerSelection(b: OverlayPanelBinding) {
        val images = library.list()
        // Child 0 is the import tile, so image i lives at child i + 1.
        for (i in images.indices) {
            b.pickerRow.getChildAt(i + 1)?.isSelected = images[i].name == prefs.selected
        }
    }

    private fun scrollPickerToSelection(b: OverlayPanelBinding) {
        b.pickerScroll.post {
            val index = library.list().indexOfFirst { it.name == prefs.selected }
            if (index < 0) return@post
            val child = b.pickerRow.getChildAt(index + 1) ?: return@post
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
            prefs.sizeDp = progressToSize(value)
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

        repeatWhileHeld(b.stepSizeMinus) { nudgeSize(b, -1) }
        repeatWhileHeld(b.stepSizePlus) { nudgeSize(b, 1) }
        repeatWhileHeld(b.stepXMinus) { nudgeX(b, -1) }
        repeatWhileHeld(b.stepXPlus) { nudgeX(b, 1) }
        repeatWhileHeld(b.stepYMinus) { nudgeY(b, -1) }
        repeatWhileHeld(b.stepYPlus) { nudgeY(b, 1) }
    }

    /**
     * The size range spans 4dp to 1200dp. Mapped linearly onto a slider a few hundred pixels
     * wide, one pixel of travel is several dp, which makes any specific value unreachable.
     * A logarithmic curve gives the small sizes most of the track, where the useful values are,
     * and compresses the rarely used top end.
     */
    private fun sizeToProgress(sizeDp: Int): Int {
        val min = Prefs.MIN_SIZE.toDouble()
        val ratio = Prefs.MAX_SIZE.toDouble() / min
        val clamped = sizeDp.coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
        return ((ln(clamped / min) / ln(ratio)) * SIZE_STEPS).roundToInt()
            .coerceIn(0, SIZE_STEPS)
    }

    private fun progressToSize(progress: Int): Int {
        val min = Prefs.MIN_SIZE.toDouble()
        val ratio = Prefs.MAX_SIZE.toDouble() / min
        val t = progress.toDouble() / SIZE_STEPS
        return (min * ratio.pow(t)).roundToInt().coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
    }

    /** Exactly one dp per press, so the slider gets you close and these land it. */
    private fun nudgeSize(b: OverlayPanelBinding, delta: Int) {
        prefs.sizeDp = (prefs.sizeDp + delta).coerceIn(Prefs.MIN_SIZE, Prefs.MAX_SIZE)
        syncing = true
        b.seekSize.progress = sizeToProgress(prefs.sizeDp)
        syncing = false
        b.valueSize.text = context.getString(R.string.value_dp, prefs.sizeDp)
        callbacks.onConfigChanged()
    }

    /**
     * Fires once on press, then repeats with an accelerating rate while held, so covering a
     * few hundred dp does not mean a few hundred taps.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeatWhileHeld(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    action()
                    val repeater = object : Runnable {
                        var delay = REPEAT_FIRST_MS
                        override fun run() {
                            action()
                            delay = (delay * REPEAT_DECAY).toLong().coerceAtLeast(REPEAT_MIN_MS)
                            handler.postDelayed(this, delay)
                        }
                    }
                    pending = repeater
                    handler.postDelayed(repeater, REPEAT_FIRST_MS)
                    v.isPressed = true
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pending?.let { handler.removeCallbacks(it) }
                    pending = null
                    v.isPressed = false
                    true
                }

                else -> true
            }
        }
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

        /** Slider resolution. Combined with the log curve this is well under 1dp per step
         *  through the range people actually use. */
        private const val SIZE_STEPS = 1000

        private const val REPEAT_FIRST_MS = 380L
        private const val REPEAT_MIN_MS = 35L
        private const val REPEAT_DECAY = 0.80f
    }
}
