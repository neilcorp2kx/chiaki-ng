// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.metallic.chiaki.R
import kotlin.math.hypot

/**
 * Arranging the on-screen controls: drag a control to move it, pinch (or Smaller/Larger) to
 * resize it. Done keeps the arrangement, Reset puts everything back, cancel() restores what was
 * there before. Covers the stream, so no input reaches the console while arranging.
 */
class ControlsEditorView(
	context: Context,
	private val targets: Map<String, View>,
	initial: Map<String, ControlsLayout.Placement>,
	private val onFinished: (placements: Map<String, ControlsLayout.Placement>?) -> Unit
): FrameLayout(context)
{
	private val density = resources.displayMetrics.density
	private val original = initial.toMap()
	private val placements = initial.toMutableMap()

	private var selected: String? = null
	private var dragStartX = 0f
	private var dragStartY = 0f
	private var dragStartPlacement = ControlsLayout.Placement()
	private var pinchStartSpan = 0f
	private var pinchStartScale = 1f

	private val dimPaint = Paint().apply { color = 0x66000000 }
	private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.5f * density
		color = 0xB3FFFFFF.toInt()
		pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
	}
	private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 3f * density
		color = 0xFFFFFFFF.toInt()
	}
	private val selectedFill = Paint().apply { color = 0x26FFFFFF }
	private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		color = 0xFFFFFFFF.toInt()
		textSize = 12f * resources.displayMetrics.scaledDensity
		setShadowLayer(4f * density, 0f, 0f, 0xFF000000.toInt())
	}

	private val labels = mapOf(
		"dpad" to R.string.controls_dpad, "face" to R.string.controls_face, "lstick" to R.string.controls_left_stick,
		"rstick" to R.string.controls_right_stick, "touchpad" to R.string.controls_touchpad, "menu" to R.string.controls_menu,
		"share" to R.string.controls_share, "options" to R.string.controls_options, "ps" to R.string.controls_ps)

	private val toolbar = LinearLayout(context).apply {
		orientation = LinearLayout.VERTICAL
		gravity = Gravity.CENTER_HORIZONTAL
		val pad = (14 * density).toInt()
		setPadding(pad, pad, pad, pad)
		background = GradientDrawable().apply {
			cornerRadius = 18 * density
			setColor(0xE6101828.toInt())
			setStroke((1 * density).toInt(), 0x40FFFFFF)
		}
	}
	private val hint = TextView(context).apply {
		setText(R.string.controls_arrange_hint)
		setTextColor(0xFFE8EEFA.toInt())
		textSize = 14f
		gravity = Gravity.CENTER
	}
	private val smallerButton = button(R.string.controls_smaller) { resizeSelected(-0.1f) }
	private val largerButton = button(R.string.controls_larger) { resizeSelected(0.1f) }

	init
	{
		setWillNotDraw(false)
		isClickable = true
		val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
		row.addView(smallerButton)
		row.addView(largerButton)
		row.addView(button(R.string.controls_reset) { reset() })
		row.addView(button(R.string.action_done) { onFinished(placements.toMap()) })
		toolbar.addView(hint, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
		toolbar.addView(row, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
			topMargin = (10 * density).toInt()
		})
		addView(toolbar, LayoutParams((420 * density).toInt(), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
		updateButtons()
	}

	private fun button(text: Int, action: () -> Unit) = TextView(context).apply {
		setText(text)
		setTextColor(0xFFFFFFFF.toInt())
		textSize = 15f
		gravity = Gravity.CENTER
		minWidth = (72 * density).toInt()
		minHeight = (44 * density).toInt()
		val pad = (12 * density).toInt()
		setPadding(pad, 0, pad, 0)
		background = GradientDrawable().apply {
			cornerRadius = 12 * density
			setColor(0x26FFFFFF)
		}
		isClickable = true
		isFocusable = true
		setOnClickListener { action() }
		layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
			marginStart = (4 * density).toInt()
			marginEnd = (4 * density).toInt()
		}
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		// At most 420 dp wide, and narrower on a narrow screen so the hint wraps instead of being cut
		val width = minOf((420 * density).toInt(), w - (32 * density).toInt())
		if(toolbar.layoutParams.width != width)
			post { toolbar.layoutParams = toolbar.layoutParams.apply { this.width = width } }
	}

	/** Back: leave everything as it was before arranging */
	fun cancel()
	{
		targets.forEach { (name, view) -> ControlsLayout.apply(view, original[name]) }
		onFinished(null)
	}

	private fun reset()
	{
		placements.clear()
		targets.values.forEach { ControlsLayout.apply(it, null) }
		invalidate()
	}

	private fun resizeSelected(delta: Float)
	{
		val name = selected ?: return
		val view = targets[name] ?: return
		val p = placements[name] ?: ControlsLayout.Placement()
		placements[name] = p.copy(scale = (p.scale + delta).coerceIn(ControlsLayout.MIN_SCALE, ControlsLayout.MAX_SCALE))
		ControlsLayout.apply(view, placements[name])
		invalidate()
	}

	private fun updateButtons()
	{
		val enabled = selected != null
		smallerButton.isEnabled = enabled
		largerButton.isEnabled = enabled
		smallerButton.alpha = if(enabled) 1f else 0.4f
		largerButton.alpha = if(enabled) 1f else 0.4f
	}

	private val location = IntArray(2)
	private val ownLocation = IntArray(2)

	/** A control's bounds on this view, as it is drawn (moved and scaled) */
	private fun boundsOf(view: View): RectF
	{
		getLocationInWindow(ownLocation)
		view.getLocationInWindow(location)
		val left = (location[0] - ownLocation[0]).toFloat()
		val top = (location[1] - ownLocation[1]).toFloat()
		return RectF(left, top, left + view.width * view.scaleX, top + view.height * view.scaleY)
	}

	private fun visibleTargets() = targets.filterValues { it.isShown && it.width > 0 && it.height > 0 }

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
		val radius = 10 * density
		for((name, view) in visibleTargets())
		{
			val r = boundsOf(view)
			if(name == selected)
			{
				canvas.drawRoundRect(r, radius, radius, selectedFill)
				canvas.drawRoundRect(r, radius, radius, selectedPaint)
			}
			else
				canvas.drawRoundRect(r, radius, radius, outlinePaint)
			val label = labels[name]?.let { context.getString(it) } ?: name.uppercase()
			canvas.drawText(label, r.left + 6 * density, r.top + labelPaint.textSize + 4 * density, labelPaint)
		}
	}

	override fun onTouchEvent(event: MotionEvent): Boolean
	{
		when(event.actionMasked)
		{
			MotionEvent.ACTION_DOWN ->
			{
				// The smallest control under the finger, so buttons win over the large stick areas
				selected = visibleTargets()
					.map { (name, view) -> name to boundsOf(view) }
					.filter { (_, r) -> r.contains(event.x, event.y) }
					.minByOrNull { (_, r) -> r.width() * r.height() }
					?.first
				dragStartX = event.x
				dragStartY = event.y
				dragStartPlacement = selected?.let { placements[it] } ?: ControlsLayout.Placement()
				updateButtons()
				invalidate()
			}
			MotionEvent.ACTION_POINTER_DOWN ->
			{
				if(event.pointerCount == 2)
				{
					pinchStartSpan = span(event)
					pinchStartScale = selected?.let { placements[it]?.scale } ?: 1f
				}
			}
			MotionEvent.ACTION_MOVE ->
			{
				val name = selected ?: return true
				val view = targets[name] ?: return true
				val parent = view.parent as? View ?: return true
				if(parent.width == 0 || parent.height == 0)
					return true
				val current = placements[name] ?: ControlsLayout.Placement()
				placements[name] = if(event.pointerCount >= 2 && pinchStartSpan > 0f)
					current.copy(scale = (pinchStartScale * span(event) / pinchStartSpan).coerceIn(ControlsLayout.MIN_SCALE, ControlsLayout.MAX_SCALE))
				else
				{
					// Keep the control's center on the screen
					val dx = dragStartPlacement.dx + (event.x - dragStartX) / parent.width
					val dy = dragStartPlacement.dy + (event.y - dragStartY) / parent.height
					val centerX = view.left + view.width / 2f
					val centerY = view.top + view.height / 2f
					current.copy(
						dx = dx.coerceIn(-centerX / parent.width, (parent.width - centerX) / parent.width),
						dy = dy.coerceIn(-centerY / parent.height, (parent.height - centerY) / parent.height))
				}
				ControlsLayout.apply(view, placements[name])
				invalidate()
			}
			MotionEvent.ACTION_POINTER_UP ->
			{
				// Continue dragging with the remaining finger from where things are now
				pinchStartSpan = 0f
				val remaining = if(event.actionIndex == 0) 1 else 0
				dragStartX = event.getX(remaining)
				dragStartY = event.getY(remaining)
				dragStartPlacement = selected?.let { placements[it] } ?: ControlsLayout.Placement()
			}
		}
		return true
	}

	private fun span(event: MotionEvent) = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
}
