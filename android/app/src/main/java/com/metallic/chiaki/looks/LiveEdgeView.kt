// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.metallic.chiaki.R

/**
 * A thin light along the top edge of the picture while playing. Turns amber when the connection
 * struggles. In Console home a slow sheen passes along it now and then while the connection is good.
 */
class LiveEdgeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	companion object
	{
		private const val SHEEN_PASS_MS = 2400L
		private const val SHEEN_EVERY_MS = 9000L
	}

	private val animate = context.animationsEnabled()
	private val startTime = SystemClock.uptimeMillis()

	var style = AppStyle.STATUS_LIGHT
		set(value) { field = value; updateColors(false) }

	private val goodColor get() = ContextCompat.getColor(context, if(style == AppStyle.CONSOLE_HOME) R.color.ch_lumen else R.color.sl_frost)
	private val badColor get() = ContextCompat.getColor(context, R.color.sl_standby)

	private var color = goodColor
	private var colorAnimator: ValueAnimator? = null

	var struggling = false
		set(value)
		{
			if(field == value)
				return
			field = value
			updateColors(true)
		}

	private fun updateColors(animated: Boolean)
	{
		colorAnimator?.cancel()
		val target = if(struggling) badColor else goodColor
		if(!animated || !animate)
		{
			color = target
			invalidate()
			return
		}
		colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), color, target).apply {
			duration = 600
			addUpdateListener { color = it.animatedValue as Int; invalidate() }
			start()
		}
	}

	private val linePaint = Paint()
	private val sheenPaint = Paint()

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val w = width.toFloat()
		val h = height.toFloat()
		val sheen = style == AppStyle.CONSOLE_HOME
		linePaint.color = color
		linePaint.alpha = if(sheen && !struggling) 0x55 else 0xD0
		canvas.drawRect(0f, 0f, w, h, linePaint)
		if(!sheen || struggling || !animate)
			return

		val cycle = (SystemClock.uptimeMillis() - startTime) % SHEEN_EVERY_MS
		if(cycle < SHEEN_PASS_MS)
		{
			val p = cycle.toFloat() / SHEEN_PASS_MS
			val sheenWidth = w * 0.3f
			val x = -sheenWidth + (w + sheenWidth) * p
			sheenPaint.shader = LinearGradient(x, 0f, x + sheenWidth, 0f,
				intArrayOf(ColorUtils.setAlphaComponent(color, 0), ColorUtils.setAlphaComponent(color, 0xF0), ColorUtils.setAlphaComponent(color, 0)),
				null, Shader.TileMode.CLAMP)
			canvas.drawRect(x, 0f, x + sheenWidth, h, sheenPaint)
			postInvalidateDelayed(33)
		}
		else
			postInvalidateDelayed(SHEEN_EVERY_MS - cycle)
	}

	init
	{
		importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
	}
}
