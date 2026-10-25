// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.content.Context
import android.graphics.Canvas
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
import kotlin.math.min
import kotlin.math.sin

/**
 * Console home connecting animation: points of light stream into a breathing ring and ripples
 * go out from it. Draws a still ring when Android's animations are off.
 */
class OrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	private val density = resources.displayMetrics.density
	private val animate = context.animationsEnabled()
	private val startTime = SystemClock.uptimeMillis()

	private val glow = ContextCompat.getColor(context, R.color.ch_glow)
	private val lumen = ContextCompat.getColor(context, R.color.ch_lumen)

	private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 2f * density
		color = lumen
	}
	private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		style = Paint.Style.STROKE
		strokeWidth = 1.5f * density
		color = lumen
	}
	private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lumen }

	private var radius = 1f

	/** At most this tall, so on tall screens the ring stays close to the text below it */
	private val maxHeight = (300 * density).toInt()

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
	{
		super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		if(measuredHeight > maxHeight)
			setMeasuredDimension(measuredWidth, maxHeight)
	}

	override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int)
	{
		super.onSizeChanged(w, h, oldw, oldh)
		radius = min(w, h) * 0.2f
		corePaint.shader = RadialGradient(0f, 0f, radius * 1.7f,
			intArrayOf(ColorUtils.setAlphaComponent(glow, 0xA0), ColorUtils.setAlphaComponent(glow, 0x38), ColorUtils.setAlphaComponent(glow, 0)),
			floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		if(width <= 0 || height <= 0)
			return
		val t = if(animate) (SystemClock.uptimeMillis() - startTime) / 1000f else 0.6f
		val breathe = 0.5f + 0.5f * sin(t * 2f * PI.toFloat() / 2.4f)
		val cx = width / 2f
		val cy = height / 2f

		canvas.save()
		canvas.translate(cx, cy)
		val coreScale = 0.9f + 0.12f * breathe
		canvas.scale(coreScale, coreScale)
		canvas.drawCircle(0f, 0f, radius * 1.7f, corePaint)
		canvas.restore()

		if(animate)
		{
			for(k in 0 until 2)
			{
				val phase = ((t / 2.8f) + k * 0.5f) % 1f
				ripplePaint.alpha = (255 * 0.38f * (1f - phase)).toInt()
				canvas.drawCircle(cx, cy, radius * (1f + 1.3f * phase), ripplePaint)
			}
		}

		ringPaint.alpha = (255 * (0.75f + 0.25f * breathe)).toInt()
		canvas.drawCircle(cx, cy, radius * (0.96f + 0.05f * breathe), ringPaint)

		val count = 10
		for(i in 0 until count)
		{
			val phase = if(animate) ((t / 2.2f) + i.toFloat() / count) % 1f else (i.toFloat() / count)
			val eased = 1f - (1f - phase) * (1f - phase)
			val angle = i * 2f * PI.toFloat() / count + t * 0.15f
			val dist = radius * (2.6f - 1.6f * eased)
			pointPaint.alpha = (255 * 0.9f * sin(PI.toFloat() * phase)).toInt()
			canvas.drawCircle(cx + cos(angle) * dist, cy + sin(angle) * dist, 2.2f * density, pointPaint)
		}

		if(animate)
			postInvalidateOnAnimation()
	}

	init
	{
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}
}
