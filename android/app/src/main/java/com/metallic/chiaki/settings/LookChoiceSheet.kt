// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.preference.ListPreference
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.LookChoiceOptionBinding
import com.metallic.chiaki.databinding.LookChoiceSheetBinding
import com.metallic.chiaki.looks.AppStyle
import com.metallic.chiaki.looks.FilterPreviewView
import com.metallic.chiaki.looks.LookPreviewView
import kotlin.math.max

/**
 * Choosing a value in the Status light and Console home settings: a panel from the side
 * (from the bottom on narrow screens) with the options and, where a setting changes the
 * picture, a preview. Focusing an option previews it; choosing it applies it.
 */
class LookChoiceSheet(private val context: Context, private val preference: ListPreference)
{
	fun show()
	{
		val dialog = Dialog(context, R.style.LookSheetDialog)
		val inflater = LayoutInflater.from(dialog.context)
		val binding = LookChoiceSheetBinding.inflate(inflater)
		val density = context.resources.displayMetrics.density
		val wide = context.resources.configuration.screenWidthDp >= 600

		binding.titleTextView.text = preference.title
		binding.descriptionTextView.text = preference.summary
		binding.descriptionTextView.isVisible = !preference.summary.isNullOrBlank()

		val key = preference.key
		val entries = preference.entries ?: emptyArray()
		val values = preference.entryValues ?: emptyArray()

		val preview: ((Int) -> Unit)? = when(key)
		{
			context.getString(R.string.preferences_deband_key), context.getString(R.string.preferences_sharpen_key) ->
			{
				val sharpen = key == context.getString(R.string.preferences_sharpen_key)
				val view = FilterPreviewView(dialog.context, sharpen)
				binding.previewFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
				binding.previewCaptionTextView.setText(if(sharpen) R.string.settings_preview_sharpen else R.string.settings_preview_deband)
				// Options go from off to the strongest level
				val show: (Int) -> Unit = { i -> view.level = i }
				show
			}
			context.getString(R.string.preferences_app_style_key) ->
			{
				val view = LookPreviewView(dialog.context)
				binding.previewFrame.layoutParams = binding.previewFrame.layoutParams.apply { height = (150 * density).toInt() }
				binding.previewFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
				binding.previewCaptionTextView.setText(R.string.settings_preview_look)
				val show: (Int) -> Unit = { i -> view.style = AppStyle.fromValue(values.getOrNull(i)?.toString()) }
				show
			}
			else -> null
		}
		binding.previewLayout.isVisible = preview != null

		val rows = mutableListOf<LookChoiceOptionBinding>()
		fun markInUse()
		{
			rows.forEachIndexed { i, row -> row.inUseTextView.isVisible = values.getOrNull(i)?.toString() == preference.value }
		}
		entries.forEachIndexed { i, entry ->
			val row = LookChoiceOptionBinding.inflate(inflater, binding.optionsLayout, false)
			row.labelTextView.text = entry
			row.root.setOnFocusChangeListener { _, hasFocus -> if(hasFocus) preview?.invoke(i) }
			row.root.setOnClickListener {
				preview?.invoke(i)
				val value = values[i].toString()
				val look = key == context.getString(R.string.preferences_app_style_key)
				// A new look recreates the screen, so close first.
				if(look)
					dialog.dismiss()
				if(value != preference.value && preference.callChangeListener(value))
					preference.value = value
				markInUse()
			}
			binding.optionsLayout.addView(row.root)
			rows += row
		}
		markInUse()
		val current = values.indexOfFirst { it.toString() == preference.value }.coerceAtLeast(0)
		preview?.invoke(current)
		binding.doneButton.setOnClickListener { dialog.dismiss() }

		// A little room around the panel so its rounded corners show
		val frame = FrameLayout(dialog.context).apply {
			val pad = (12 * density).toInt()
			setPadding(pad, pad, pad, pad)
			addView(binding.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				if(wide) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
		}
		dialog.setContentView(frame)
		dialog.window?.let { window ->
			val metrics = context.resources.displayMetrics
			if(wide)
			{
				window.setLayout(max((380 * density).toInt(), (metrics.widthPixels * 0.46f).toInt()), ViewGroup.LayoutParams.MATCH_PARENT)
				window.setGravity(Gravity.END)
				window.setWindowAnimations(R.style.LookSheetAnimation_End)
			}
			else
			{
				window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
				window.setGravity(Gravity.BOTTOM)
				window.setWindowAnimations(R.style.LookSheetAnimation_Bottom)
			}
		}
		dialog.setOnShowListener { rows.getOrNull(current)?.root?.requestFocus() }
		dialog.show()
	}
}
