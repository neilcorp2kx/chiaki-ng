// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.metallic.chiaki.R

/** Stream screen details of the Status light and Console home looks */
object StreamLook
{
	private const val GAP = "\u2003\u2003"
	/** Keeps a value and its unit on one line; lines only break between groups */
	private const val NBSP = "\u00A0"

	/** Stats sit in the band above the picture, centered, in the look's own face */
	fun styleStats(style: AppStyle, view: TextView)
	{
		val context = view.context
		val density = context.resources.displayMetrics.density
		view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply {
			width = FrameLayout.LayoutParams.MATCH_PARENT
			gravity = Gravity.TOP
			setMargins(0, 0, 0, 0)
		}
		view.gravity = Gravity.CENTER_HORIZONTAL
		view.setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
		view.textSize = 13f
		// The layout sets monospace for Classic; the looks use their own face, with tabular figures.
		view.typeface = ResourcesCompat.getFont(context, if(style == AppStyle.CONSOLE_HOME) R.font.mukta else R.font.archivo)
		view.fontFeatureSettings = "tnum"
		when(style)
		{
			AppStyle.CONSOLE_HOME ->
			{
				view.background = null
				view.setTextColor(ContextCompat.getColor(context, R.color.ch_lumen))
				view.setShadowLayer(6 * density, 0f, 0f, ContextCompat.getColor(context, R.color.ch_midnight))
			}
			else ->
			{
				view.setBackgroundResource(R.drawable.sl_band)
				view.setTextColor(ContextCompat.getColor(context, R.color.sl_frost))
			}
		}
	}

	fun formatStats(
		style: AppStyle, height: Int, fps: Double, bitrateMbps: Double, packetLoss: Double, rttMs: Double, psn: Boolean,
		controller: String?, filterStats: String?, routePsn: String, routeLan: String): CharSequence
	{
		val bold = style == AppStyle.CONSOLE_HOME
		val b = SpannableStringBuilder()
		fun group(value: String, unit: String)
		{
			if(b.isNotEmpty())
				b.append(GAP)
			val start = b.length
			b.append(value)
			if(bold)
				b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			if(unit.isNotEmpty())
				b.append(NBSP).append(unit.replace(" ", NBSP))
		}
		group("${height}p", String.format("%.0f fps", fps))
		group(String.format("%.1f", bitrateMbps), "Mbit/s")
		group(String.format("%.1f%%", packetLoss * 100.0), "loss")
		group(String.format("%.0f${NBSP}ms", rttMs), if(psn) routePsn else routeLan)
		controller?.let { b.append("\n").append(it.replace(" · ", ", ")) }
		filterStats?.let { b.append("\n").append(it.replace(" · ", GAP)) }
		return b
	}

	/** The connection struggles: packets lost, or the picture well below its frame rate */
	fun struggling(packetLoss: Double, fps: Double, maxFps: Int) =
		packetLoss > 0.02 || (fps > 0.0 && fps < maxFps * 0.7)

	/** Console home: the cards rise into the band one after another */
	fun animateCardsIn(cards: List<View>)
	{
		if(cards.isEmpty() || !cards[0].context.animationsEnabled())
			return
		val rise = 24 * cards[0].resources.displayMetrics.density
		cards.forEachIndexed { i, card ->
			card.animate().cancel()
			card.translationY = rise
			card.alpha = 0f
			card.animate()
				.translationY(0f)
				.alpha(1f)
				.setStartDelay(i * 60L)
				.setDuration(260)
				.setInterpolator(DecelerateInterpolator())
				.start()
		}
	}
}
