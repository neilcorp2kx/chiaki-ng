// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.ListPreference
import androidx.preference.PreferenceViewHolder
import com.metallic.chiaki.R

/**
 * A choice with its description below the title and the chosen value on the right,
 * for the settings of the Status light and Console home looks.
 */
class ValueListPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null): ListPreference(context, attrs)
{
	init
	{
		widgetLayoutResource = R.layout.look_pref_value
	}

	override fun onBindViewHolder(holder: PreferenceViewHolder)
	{
		super.onBindViewHolder(holder)
		(holder.findViewById(R.id.prefValueTextView) as? TextView)?.text = entry ?: ""
	}
}
