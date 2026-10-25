// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.metallic.chiaki.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Console home backdrop: slow light drifting over deep blue, with points of light and faint rings.
 * Warm (amber) light when the chosen console is resting. About 30 frames per second while visible,
 * nothing while hidden, and a still frame when Android's animations are off.
 */
class AmbientBackgroundView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	private val density = resources.displayMetrics.density
	private val animate = context.animationsEnabled()
	private val startTime = SystemClock.uptimeMillis()

	private val midnight = ContextCompat.getColor(context, R.color.ch_midnight)
	private val deep = ContextCompat.getColor(context, R.color.ch_deep)
	private val glow = ContextCompat.getColor(context, R.color.ch_glow)
	private val standby = ContextCompat.getColor(context, R.color.ch_standby)
	private val lumen = ContextCompat.getColor(context, R.color.ch_lumen)

	private var warmth = 0f
	private var warmAnimator: ValueAnimator? = null

	var warm = false
		set(value)
		{
			if(field == value)
				return
			field = value
			warmAnimator?.cancel()
			val target = if(value) 1f else 0f
			if(!animate)
			{
				warmth = target
				invalidate()
				return
			}
			warmAnimator = ValueAnimator.ofFloat(warmth, target).apply {
				duration = 700
				addUpdateListener { warmth = it.animatedValue as Float; invalidate() }
				start()
			}
		}

	private val basePaint = Paint()
	private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val warmBlobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.2f * density
	}
	private var blobRadius = 1f

	private class Mote(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float)
	private val random = Random(7)
	private val dots = List(26) {
		Mote(random.nextFloat(), random.nextFloat(), 1f + random.nextFloat() * 1.6f, 0.006f + random.nextFloat() * 0.014f, random.nextFloat())
	}
	private val rings = List(4) {
		Mote(random.nextFloat(), random.nextFloat(), 10f + random.nextFloat() * 14f, 0.004f + random.nextFloat() * 0.006f, random.nextFloat())
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		basePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), deep, midnight, Shader.TileMode.CLAMP)
		blobRadius = max(w, h) * 0.55f
		blobPaint.shader = RadialGradient(0f, 0f, blobRadius,
			intArrayOf(ColorUtils.setAlphaComponent(glow, 0x70), ColorUtils.setAlphaComponent(glow, 0x22), ColorUtils.setAlphaComponent(glow, 0)),
			floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
		warmBlobPaint.shader = RadialGradient(0f, 0f, blobRadius,
			intArrayOf(ColorUtils.setAlphaComponent(standby, 0x60), ColorUtils.setAlphaComponent(standby, 0x1C), ColorUtils.setAlphaComponent(standby, 0)),
			floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val w = width.toFloat()
		val h = height.toFloat()
		if(w <= 0f || h <= 0f)
			return
		// A fixed moment for the still frame
		val t = if(animate) (SystemClock.uptimeMillis() - startTime) / 1000f else 7f

		canvas.drawRect(0f, 0f, w, h, basePaint)

		drawBlob(canvas, w * (0.24f + 0.12f * sin(t * 2f * PI.toFloat() / 23f)), h * (0.28f + 0.10f * cos(t * 2f * PI.toFloat() / 29f)), 1f)
		drawBlob(canvas, w * (0.80f + 0.10f * cos(t * 2f * PI.toFloat() / 31f)), h * (0.72f + 0.12f * sin(t * 2f * PI.toFloat() / 37f)), 0.8f)

		for(d in dots)
		{
			val y = ((d.y - t * d.speed) % 1f + 1f) % 1f
			val x = d.x + 0.01f * sin(t * 0.6f + d.phase * 6f)
			val twinkle = 0.5f + 0.5f * sin(t * 1.3f + d.phase * 12f)
			dotPaint.color = lumen
			dotPaint.alpha = (255 * (0.12f + 0.45f * twinkle)).toInt()
			canvas.drawCircle(x * w, y * h, d.size * density, dotPaint)
		}

		for(r in rings)
		{
			val x = r.x + 0.04f * sin(t * r.speed * 9f + r.phase * 6f)
			val y = ((r.y - t * r.speed * 0.5f) % 1f + 1f) % 1f
			ringPaint.color = lumen
			ringPaint.alpha = 0x1C
			canvas.drawCircle(x * w, y * h, r.size * density, ringPaint)
		}

		if(animate)
			postInvalidateDelayed(33)
	}

	private fun drawBlob(canvas: Canvas, x: Float, y: Float, scale: Float)
	{
		canvas.save()
		canvas.translate(x, y)
		canvas.scale(scale, scale)
		if(warmth < 1f)
		{
			blobPaint.alpha = (255 * (1f - warmth)).toInt()
			canvas.drawCircle(0f, 0f, blobRadius, blobPaint)
		}
		if(warmth > 0f)
		{
			warmBlobPaint.alpha = (255 * warmth).toInt()
			canvas.drawCircle(0f, 0f, blobRadius, warmBlobPaint)
		}
		canvas.restore()
	}

	init
	{
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}
}
