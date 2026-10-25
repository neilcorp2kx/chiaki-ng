// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.StreamConnectConsoleHomeBinding
import com.metallic.chiaki.databinding.StreamConnectStatusLightBinding

/** The full-screen connecting view of a look other than Classic */
abstract class ConnectScreen(protected val context: Context)
{
	abstract val root: View
	abstract fun show(name: String?, progress: ConnectProgress)

	companion object
	{
		fun create(style: AppStyle, context: Context, parent: ViewGroup): ConnectScreen? = when(style)
		{
			AppStyle.CLASSIC -> null
			AppStyle.STATUS_LIGHT -> StatusLightConnectScreen(context, parent)
			AppStyle.CONSOLE_HOME -> ConsoleHomeConnectScreen(context, parent)
		}
	}
}

private class StatusLightConnectScreen(context: Context, parent: ViewGroup): ConnectScreen(context)
{
	private val binding = StreamConnectStatusLightBinding.inflate(LayoutInflater.from(context), parent, false)
	override val root: View get() = binding.root
	private val density = context.resources.displayMetrics.density

	private class Row(val light: LightStripView, val label: TextView, val detail: TextView, val layout: View)
	private var rows: List<Row> = emptyList()
	private var builtFor: ConnectProgress? = null

	private fun build(progress: ConnectProgress)
	{
		binding.stepsLayout.removeAllViews()
		rows = progress.steps.map {
			val row = LinearLayout(context).apply {
				orientation = LinearLayout.HORIZONTAL
				gravity = Gravity.TOP
				setPadding(0, (5 * density).toInt(), 0, (5 * density).toInt())
			}
			val light = LightStripView(context).apply {
				standbyColor = ContextCompat.getColor(context, R.color.sl_frost)
			}
			row.addView(light, LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt()).apply {
				marginEnd = (8 * density).toInt()
			})
			val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
			val label = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
			val detail = TextView(context).apply {
				setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
				setTextColor(ContextCompat.getColor(context, R.color.sl_haze))
				typeface = ResourcesCompat.getFont(context, R.font.archivo_regular)
				maxWidth = (440 * density).toInt()
			}
			texts.addView(label)
			texts.addView(detail)
			row.addView(texts)
			binding.stepsLayout.addView(row)
			Row(light, label, detail, row)
		}
		builtFor = progress
	}

	override fun show(name: String?, progress: ConnectProgress)
	{
		if(builtFor !== progress)
			build(progress)
		binding.nameTextView.text = name ?: ""
		binding.nameTextView.isVisible = name != null
		binding.strip.setProgress(progress.fraction)
		val regular = ResourcesCompat.getFont(context, R.font.archivo_regular)
		val semibold = ResourcesCompat.getFont(context, R.font.archivo_semibold)
		rows.forEachIndexed { i, row ->
			val step = progress.steps[i]
			when
			{
				i < progress.current ->
				{
					row.light.light = ConsoleLight.ON
					row.label.setText(step.done)
					row.label.typeface = regular
					row.label.setTextColor(ContextCompat.getColor(context, R.color.sl_frost))
					row.detail.isVisible = false
				}
				i == progress.current ->
				{
					// Pulses white, like the strip of a console that is about to wake
					row.light.light = ConsoleLight.STANDBY
					row.label.setText(step.active)
					row.label.typeface = semibold
					row.label.setTextColor(ContextCompat.getColor(context, R.color.sl_frost))
					row.detail.text = progress.detail
					row.detail.isVisible = progress.detail != null
				}
				else ->
				{
					row.light.light = ConsoleLight.OFF
					row.label.setText(step.active)
					row.label.typeface = regular
					row.label.setTextColor(ContextCompat.getColor(context, R.color.sl_haze))
					row.detail.isVisible = false
				}
			}
		}
	}
}

private class ConsoleHomeConnectScreen(context: Context, parent: ViewGroup): ConnectScreen(context)
{
	private val binding = StreamConnectConsoleHomeBinding.inflate(LayoutInflater.from(context), parent, false)
	override val root: View get() = binding.root

	init
	{
		binding.trackView.onColor = ContextCompat.getColor(context, R.color.ch_lumen)
		binding.trackView.trackColor = ContextCompat.getColor(context, R.color.ch_mist)
	}

	override fun show(name: String?, progress: ConnectProgress)
	{
		binding.nameTextView.text = name ?: ""
		binding.nameTextView.isVisible = name != null
		binding.stepTextView.setText(progress.steps[progress.current].active)
		binding.detailTextView.text = progress.detail ?: ""
		binding.trackView.setProgress(progress.fraction)
	}
}
