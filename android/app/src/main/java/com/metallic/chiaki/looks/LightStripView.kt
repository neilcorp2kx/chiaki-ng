// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.metallic.chiaki.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A light strip like the console's power light. Shows a ConsoleLight, or with progress set,
 * fills up like a progress bar. Vertical when taller than wide (fills from the bottom).
 * The view's padding-free bounds include room for the glow around the strip.
 */
class LightStripView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	private val density = resources.displayMetrics.density
	private val glowPad = 6f * density
	private val animate = context.animationsEnabled()
	private val startTime = SystemClock.uptimeMillis()

	var onColor = ContextCompat.getColor(context, R.color.sl_frost)
		set(value) { field = value; invalidate() }
	var standbyColor = ContextCompat.getColor(context, R.color.sl_standby)
		set(value) { field = value; invalidate() }
	var trackColor = ContextCompat.getColor(context, R.color.sl_haze)
		set(value) { field = value; invalidate() }

	var light = ConsoleLight.OFF
		set(value)
		{
			if(field == value)
				return
			field = value
			invalidate()
		}

	private var shownProgress = 0f
	private var progressAnimator: ValueAnimator? = null

	/** 0 to 1 to show progress instead of the console light, null for the light */
	var progress: Float? = null
		private set

	fun setProgress(value: Float?, animated: Boolean = true)
	{
		progressAnimator?.cancel()
		progress = value?.coerceIn(0f, 1f)
		val target = progress ?: return invalidate()
		if(!animated || !animate)
		{
			shownProgress = target
			invalidate()
			return
		}
		progressAnimator = ValueAnimator.ofFloat(shownProgress, target).apply {
			duration = 450
			interpolator = DecelerateInterpolator()
			addUpdateListener { shownProgress = it.animatedValue as Float; invalidate() }
			start()
		}
	}

	/** Pulses gently, like the light of a console going to rest */
	var pulse = true

	private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.5f * density
	}
	private val rect = RectF()
	private val glowRect = RectF()

	private val vertical get() = height > width

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val bar = RectF(glowPad, glowPad, width - glowPad, height - glowPad)
		if(bar.width() <= 0 || bar.height() <= 0)
			return
		val radius = min(bar.width(), bar.height()) / 2f
		val progress = progress
		if(progress != null)
		{
			drawTrack(canvas, bar, radius, 0.55f)
			val p = shownProgress
			if(p > 0f)
			{
				if(vertical)
					rect.set(bar.left, bar.bottom - bar.height() * p, bar.right, bar.bottom)
				else
					rect.set(bar.left, bar.top, bar.left + bar.width() * p, bar.bottom)
				// Keep a round cap even for tiny progress.
				if(vertical && rect.height() < radius * 2)
					rect.top = rect.bottom - radius * 2
				if(!vertical && rect.width() < radius * 2)
					rect.right = rect.left + radius * 2
				drawLit(canvas, rect, radius, onColor, 1f)
			}
			return
		}
		when(light)
		{
			ConsoleLight.ON -> drawLit(canvas, bar, radius, onColor, 1f)
			ConsoleLight.STANDBY ->
			{
				val alpha = if(animate && pulse)
				{
					val t = (SystemClock.uptimeMillis() - startTime) / 2600.0
					(0.62 + 0.38 * (0.5 + 0.5 * sin(2 * PI * t))).toFloat()
				}
				else
					1f
				drawLit(canvas, bar, radius, standbyColor, alpha)
				if(animate && pulse)
					postInvalidateDelayed(40)
			}
			ConsoleLight.OFF -> drawTrack(canvas, bar, radius, 1f)
		}
	}

	private fun drawTrack(canvas: Canvas, bar: RectF, radius: Float, alpha: Float)
	{
		trackPaint.color = trackColor
		trackPaint.alpha = (255 * alpha).toInt()
		val inset = trackPaint.strokeWidth / 2f
		rect.set(bar.left + inset, bar.top + inset, bar.right - inset, bar.bottom - inset)
		canvas.drawRoundRect(rect, radius, radius, trackPaint)
	}

	private fun drawLit(canvas: Canvas, bar: RectF, radius: Float, color: Int, alpha: Float)
	{
		// Glow: a few widening, fading copies. Cheap, and works without a software layer.
		val glowAlphas = floatArrayOf(0.22f, 0.12f, 0.06f)
		for(i in glowAlphas.indices)
		{
			val grow = (i + 1) * 2f * density
			glowRect.set(bar.left - grow, bar.top - grow, bar.right + grow, bar.bottom + grow)
			glowPaint.color = color
			glowPaint.alpha = (255 * glowAlphas[i] * alpha).toInt()
			canvas.drawRoundRect(glowRect, radius + grow, radius + grow, glowPaint)
		}
		fillPaint.color = color
		fillPaint.alpha = (255 * alpha).toInt()
		canvas.drawRoundRect(bar, radius, radius, fillPaint)
	}

	init
	{
		setWillNotDraw(false)
		if(isInEditMode)
			light = ConsoleLight.ON
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}
}
