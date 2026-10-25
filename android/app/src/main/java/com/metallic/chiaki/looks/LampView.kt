// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.Checkable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import kotlin.math.ceil
import kotlin.math.max

/**
 * On and off as a light, the switch of the Status light and Console home settings:
 * lit means on. A small light strip in Status light, a light dot in Console home.
 */
class LampView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): View(context, attrs), Checkable
{
	private val density = resources.displayMetrics.density
	private val style = Preferences(context).appStyle
	private val strip = style != AppStyle.CONSOLE_HOME

	private val onText = context.getString(R.string.settings_lamp_on)
	private val offText = context.getString(R.string.settings_lamp_off)

	private val litColor = ContextCompat.getColor(context, if(strip) R.color.sl_frost else R.color.ch_lumen)
	private val glowColor = if(strip) litColor else 0xFFAACDFF.toInt()
	private val unlitColor = ContextCompat.getColor(context, if(strip) R.color.sl_haze else R.color.ch_mist)

	private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
		textSize = 15f * resources.displayMetrics.scaledDensity
		typeface = ResourcesCompat.getFont(context, if(strip) R.font.archivo_regular else R.font.mukta_regular)
	}
	private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	private val lightWidth = (if(strip) 20f else 10f) * density
	private val lightHeight = (if(strip) 6f else 10f) * density
	private val gap = 10f * density
	private val glowPad = 6f * density

	private var checkedState = false

	override fun isChecked() = checkedState

	override fun setChecked(checked: Boolean)
	{
		if(checkedState == checked)
			return
		checkedState = checked
		contentDescription = if(checked) onText else offText
		invalidate()
	}

	override fun toggle() { isChecked = !checkedState }

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int)
	{
		val textWidth = max(textPaint.measureText(onText), textPaint.measureText(offText))
		val width = ceil(glowPad + lightWidth + gap + textWidth).toInt()
		val height = ceil(max(textPaint.fontSpacing, lightHeight + glowPad * 2) + 4 * density).toInt()
		setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
	}

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val cy = height / 2f
		val left = glowPad
		rect.set(left, cy - lightHeight / 2, left + lightWidth, cy + lightHeight / 2)
		val radius = lightHeight / 2
		if(checkedState)
		{
			val alphas = floatArrayOf(0.30f, 0.16f, 0.07f)
			for(i in alphas.indices)
			{
				val grow = (i + 1) * 2f * density
				lightPaint.style = Paint.Style.FILL
				lightPaint.color = ColorUtils.setAlphaComponent(glowColor, (255 * alphas[i]).toInt())
				canvas.drawRoundRect(rect.left - grow, rect.top - grow, rect.right + grow, rect.bottom + grow, radius + grow, radius + grow, lightPaint)
			}
			lightPaint.color = litColor
			canvas.drawRoundRect(rect, radius, radius, lightPaint)
		}
		else
		{
			lightPaint.style = Paint.Style.STROKE
			lightPaint.strokeWidth = 1.5f * density
			lightPaint.color = unlitColor
			val inset = lightPaint.strokeWidth / 2
			canvas.drawRoundRect(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset, radius, radius, lightPaint)
		}
		textPaint.color = if(checkedState) litColor else unlitColor
		val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
		canvas.drawText(if(checkedState) onText else offText, left + lightWidth + gap, baseline, textPaint)
	}

	init
	{
		contentDescription = offText
	}
}
