// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.metallic.chiaki.R
import kotlin.math.max
import kotlin.random.Random

/**
 * Picture previews for the debanding and sharpening choices. These are illustrations of what each
 * level does, drawn here, not the stream's own filters.
 */
class FilterPreviewView(context: Context, private val sharpen: Boolean): View(context)
{
	private val density = resources.displayMetrics.density
	private val stops = intArrayOf(0xFF0E1B3A.toInt(), 0xFF2B4C8C.toInt(), 0xFF6E8FCB.toInt())
	private val positions = floatArrayOf(0f, 0.55f, 1f)

	/** 0 = off, 3 = high */
	var level = 0
		set(value) { field = value.coerceIn(0, 3); invalidate() }

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val clip = RectF()

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		if(width <= 0 || height <= 0)
			return
		val radius = 10f * density
		clip.set(0f, 0f, width.toFloat(), height.toFloat())
		val save = canvas.save()
		val path = android.graphics.Path().apply { addRoundRect(clip, radius, radius, android.graphics.Path.Direction.CW) }
		canvas.clipPath(path)
		if(sharpen)
			drawDetail(canvas)
		else
			drawGradient(canvas)
		canvas.restoreToCount(save)
	}

	private fun colorAt(t: Float): Int
	{
		val i = if(t < positions[1]) 0 else 1
		val f = ((t - positions[i]) / (positions[i + 1] - positions[i])).coerceIn(0f, 1f)
		return ColorUtils.blendARGB(stops[i], stops[i + 1], f)
	}

	private fun drawGradient(canvas: Canvas)
	{
		val w = width.toFloat()
		val h = height.toFloat()
		// Off shows the stripes compression leaves; stronger debanding smooths them away.
		val bands = when(level) { 0 -> 7; 1 -> 14; else -> 0 }
		paint.shader = null
		if(bands > 0)
		{
			for(b in 0 until bands)
			{
				paint.color = colorAt((b + 0.5f) / bands)
				canvas.drawRect(w * b / bands, 0f, w * (b + 1) / bands + 1, h, paint)
			}
		}
		else
		{
			paint.shader = LinearGradient(0f, 0f, w, 0f, stops, positions, Shader.TileMode.CLAMP)
			canvas.drawRect(0f, 0f, w, h, paint)
			paint.shader = null
		}
		if(level >= 2)
		{
			// Debanding adds a fine grain that hides what is left of the steps.
			val random = Random(3)
			paint.color = Color.WHITE
			val count = if(level == 3) 900 else 450
			for(i in 0 until count)
			{
				paint.alpha = 10 + random.nextInt(14)
				canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h, 0.6f * density, paint)
			}
		}
	}

	private var detailCache: Bitmap? = null

	private fun detail(w: Int, h: Int): Bitmap
	{
		detailCache?.takeIf { it.width == w && it.height == h }?.let { return it }
		val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
		val c = Canvas(bitmap)
		c.drawColor(0xFF2A3A56.toInt())
		val p = Paint(Paint.ANTI_ALIAS_FLAG)
		p.color = 0xFF5D7AA8.toInt()
		var x = -h.toFloat()
		while(x < w)
		{
			c.drawRect(x, 0f, x + 1.2f * density, h.toFloat(), p)
			x += 5f * density
		}
		p.color = 0xFFE8EEFA.toInt()
		p.strokeWidth = 1.2f * density
		for(i in 0 until 6)
		{
			val y = h * (0.2f + 0.12f * i)
			c.drawLine(w * 0.55f, y, w * 0.95f, y + h * 0.05f, p)
		}
		p.textSize = 15f * density
		p.typeface = ResourcesCompat.getFont(context, R.font.archivo_semibold)
		c.drawText("Remote Play 1080p", w * 0.05f, h * 0.45f, p)
		p.textSize = 10f * density
		c.drawText("Fine text and thin lines", w * 0.05f, h * 0.72f, p)
		detailCache = bitmap
		return bitmap
	}

	private fun drawDetail(canvas: Canvas)
	{
		val full = detail(width, height)
		// Off and low look soft, as a compressed stream does; medium and high get crisper.
		val scale = when(level) { 0 -> 0.42f; 1 -> 0.62f; else -> 1f }
		paint.isFilterBitmap = true
		paint.alpha = 255
		if(scale < 1f)
		{
			val small = Bitmap.createScaledBitmap(full, max(1, (width * scale).toInt()), max(1, (height * scale).toInt()), true)
			canvas.drawBitmap(small, null, Rect(0, 0, width, height), paint)
			small.recycle()
		}
		else
			canvas.drawBitmap(full, 0f, 0f, paint)
		if(level == 3)
		{
			// Strong sharpening brightens edges a little too far.
			paint.alpha = 70
			canvas.drawBitmap(full, 0.6f * density, 0f, paint)
			paint.alpha = 255
		}
	}
}

/** A small picture of a look's consoles screen, for the Look choice */
class LookPreviewView(context: Context): View(context)
{
	private val density = resources.displayMetrics.density

	var style = AppStyle.CLASSIC
		set(value) { field = value; invalidate() }

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val rect = RectF()

	private fun color(id: Int) = ContextCompat.getColor(context, id)

	override fun onDraw(canvas: Canvas)
	{
		super.onDraw(canvas)
		val w = width.toFloat()
		val h = height.toFloat()
		if(w <= 0f || h <= 0f)
			return
		val r = 10f * density
		val save = canvas.save()
		canvas.clipPath(android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW) })
		when(style)
		{
			AppStyle.CLASSIC -> drawClassic(canvas, w, h)
			AppStyle.STATUS_LIGHT -> drawStatusLight(canvas, w, h)
			AppStyle.CONSOLE_HOME -> drawConsoleHome(canvas, w, h)
		}
		canvas.restoreToCount(save)
	}

	private fun text(canvas: Canvas, s: String, x: Float, y: Float, size: Float, font: Int, color: Int)
	{
		paint.shader = null
		paint.textSize = size * density
		paint.typeface = ResourcesCompat.getFont(context, font)
		paint.color = color
		canvas.drawText(s, x, y, paint)
	}

	private fun drawClassic(canvas: Canvas, w: Float, h: Float)
	{
		paint.shader = null
		canvas.drawColor(0xFF2B2B2B.toInt())
		paint.color = color(R.color.primary)
		canvas.drawRect(0f, 0f, w, h * 0.22f, paint)
		paint.typeface = null
		paint.textSize = 11f * density
		paint.color = Color.WHITE
		canvas.drawText("chiaki-ng", w * 0.42f, h * 0.14f, paint)
		paint.color = 0xFF424242.toInt()
		rect.set(w * 0.06f, h * 0.3f, w * 0.94f, h * 0.86f)
		canvas.drawRoundRect(rect, 3 * density, 3 * density, paint)
		paint.color = 0xFFBDBDBD.toInt()
		canvas.drawText("PS5-962", w * 0.4f, h * 0.44f, paint)
		paint.color = color(R.color.accent)
		canvas.drawCircle(w * 0.86f, h * 0.84f, h * 0.09f, paint)
	}

	private fun drawStatusLight(canvas: Canvas, w: Float, h: Float)
	{
		paint.shader = null
		canvas.drawColor(color(R.color.sl_graphite))
		text(canvas, "Consoles", w * 0.05f, h * 0.2f, 12f, R.font.archivo_wide, color(R.color.sl_frost))
		val tileW = w * 0.42f
		for((i, lit) in listOf(R.color.sl_frost, R.color.sl_standby).withIndex())
		{
			val left = w * 0.05f + i * (tileW + w * 0.06f)
			paint.color = color(R.color.sl_plate)
			rect.set(left, h * 0.3f, left + tileW, h * 0.9f)
			canvas.drawRoundRect(rect, 6 * density, 6 * density, paint)
			paint.color = color(lit)
			rect.set(left + tileW * 0.08f, h * 0.36f, left + tileW * 0.92f, h * 0.36f + 3 * density)
			canvas.drawRoundRect(rect, 2 * density, 2 * density, paint)
			text(canvas, if(i == 0) "PS5-962" else "Living room", left + tileW * 0.08f, h * 0.58f, 9f, R.font.archivo_wide, color(R.color.sl_frost))
			text(canvas, if(i == 0) "Ready to play" else "Resting", left + tileW * 0.08f, h * 0.72f, 8f, R.font.archivo_regular, color(lit))
		}
	}

	private fun drawConsoleHome(canvas: Canvas, w: Float, h: Float)
	{
		paint.shader = LinearGradient(0f, 0f, 0f, h, color(R.color.ch_deep), color(R.color.ch_midnight), Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, paint)
		paint.shader = RadialGradient(w * 0.75f, h * 0.2f, w * 0.5f,
			ColorUtils.setAlphaComponent(color(R.color.ch_glow), 0x60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
		canvas.drawRect(0f, 0f, w, h, paint)
		paint.shader = null
		val small = h * 0.22f
		val big = h * 0.32f
		var x = w * 0.05f
		for(i in 0 until 4)
		{
			val s = if(i == 0) big else small
			paint.color = color(R.color.ch_card)
			rect.set(x, h * 0.1f, x + s, h * 0.1f + s)
			canvas.drawRoundRect(rect, s * 0.2f, s * 0.2f, paint)
			if(i == 0)
			{
				paint.style = Paint.Style.STROKE
				paint.strokeWidth = 1.5f * density
				paint.color = color(R.color.ch_lumen)
				canvas.drawRoundRect(rect, s * 0.2f, s * 0.2f, paint)
				paint.style = Paint.Style.FILL
			}
			x += s + w * 0.03f
		}
		text(canvas, "PS5-962", w * 0.05f, h * 0.66f, 16f, R.font.mukta_extralight, color(R.color.ch_lumen))
		paint.color = color(R.color.ch_lumen)
		rect.set(w * 0.05f, h * 0.74f, w * 0.05f + w * 0.16f, h * 0.74f + h * 0.13f)
		canvas.drawRoundRect(rect, h, h, paint)
		text(canvas, "Play", w * 0.085f, h * 0.835f, 7f, R.font.mukta_semibold, color(R.color.ch_midnight))
	}
}
