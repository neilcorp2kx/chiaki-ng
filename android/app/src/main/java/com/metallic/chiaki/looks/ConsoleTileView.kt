// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.metallic.chiaki.R

/**
 * A tile in the Console home row. The chosen tile grows, gets a light edge and a glow,
 * and pushes the tiles after it along, like games on a console's home screen.
 */
class ConsoleTileView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs)
{
	private val density = resources.displayMetrics.density
	private val animate = context.animationsEnabled()

	val smallSize = (92 * density).toInt()
	val bigSize = (132 * density).toInt()
	val glowPad = (12 * density).toInt()

	var glyph: Drawable? = null
		set(value) { field = value?.mutate(); invalidate() }

	/** Null for the add tile */
	var light: ConsoleLight? = null
		set(value) { field = value; invalidate() }

	private var amount = 0f
	private var animator: ValueAnimator? = null

	var chosen = false
		set(value)
		{
			if(field == value)
				return
			field = value
			animator?.cancel()
			val target = if(value) 1f else 0f
			if(!animate)
			{
				amount = target
				requestLayout()
				invalidate()
				return
			}
			animator = ValueAnimator.ofFloat(amount, target).apply {
				duration = 220
				interpolator = DecelerateInterpolator()
				addUpdateListener { amount = it.animatedValue as Float; requestLayout(); invalidate() }
				start()
			}
		}

	private val card = ContextCompat.getColor(context, R.color.ch_card)
	private val lumen = ContextCompat.getColor(context, R.color.ch_lumen)
	private val glow = ContextCompat.getColor(context, R.color.ch_glow)
	private val standby = ContextCompat.getColor(context, R.color.ch_standby)
	private val mist = ContextCompat.getColor(context, R.color.ch_mist)

	private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
	private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
	{
		val size = (smallSize + (bigSize - smallSize) * amount).toInt() + glowPad * 2
		setMeasuredDimension(size, size)
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val pad = glowPad.toFloat()
		rect.set(pad, pad, width - pad, height - pad)
		val radius = rect.width() * 0.2f

		if(amount > 0f)
		{
			val glowColor = if(light == ConsoleLight.STANDBY) standby else glow
			val alphas = floatArrayOf(0.30f, 0.16f, 0.08f)
			for(i in alphas.indices)
			{
				val grow = (i + 1) * 3.5f * density
				glowPaint.color = ColorUtils.setAlphaComponent(glowColor, (255 * alphas[i] * amount).toInt())
				canvas.drawRoundRect(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow, radius + grow, radius + grow, glowPaint)
			}
		}

		cardPaint.color = card
		canvas.drawRoundRect(rect, radius, radius, cardPaint)

		glyph?.let {
			val g = (rect.width() * 0.46f).toInt()
			val left = (rect.centerX() - g / 2f).toInt()
			val top = (rect.centerY() - g / 2f - rect.height() * 0.04f).toInt()
			it.setBounds(left, top, left + g, top + g)
			it.alpha = (255 * (0.78f + 0.22f * amount)).toInt()
			it.draw(canvas)
		}

		light?.let {
			val lw = rect.width() * 0.32f
			val lh = 3f * density
			val ly = rect.bottom - rect.height() * 0.12f
			when(it)
			{
				ConsoleLight.ON -> { lightPaint.style = Paint.Style.FILL; lightPaint.color = lumen }
				ConsoleLight.STANDBY -> { lightPaint.style = Paint.Style.FILL; lightPaint.color = standby }
				ConsoleLight.OFF -> { lightPaint.style = Paint.Style.STROKE; lightPaint.strokeWidth = density; lightPaint.color = mist }
			}
			canvas.drawRoundRect(rect.centerX() - lw / 2, ly - lh / 2, rect.centerX() + lw / 2, ly + lh / 2, lh / 2, lh / 2, lightPaint)
		}

		if(amount > 0f)
		{
			ringPaint.strokeWidth = 3f * density
			ringPaint.color = ColorUtils.setAlphaComponent(lumen, (255 * amount).toInt())
			val inset = ringPaint.strokeWidth / 2f
			canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, radius - inset, radius - inset, ringPaint)
		}
		else if(isFocused)
		{
			ringPaint.strokeWidth = 2f * density
			ringPaint.color = lumen
			canvas.drawRoundRect(rect, radius, radius, ringPaint)
		}
	}

	override fun drawableStateChanged()
	{
		super.drawableStateChanged()
		invalidate()
	}

	init
	{
		isFocusable = true
		isClickable = true
		// The tile draws its own focus ring
		if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
			defaultFocusHighlightEnabled = false
	}
}
