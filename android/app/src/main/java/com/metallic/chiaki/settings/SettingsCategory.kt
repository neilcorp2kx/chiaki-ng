// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount

/** The pages of the Status light and Console home settings. Every setting has one place. */
enum class SettingsCategory(val key: String, @StringRes val title: Int, @DrawableRes val icon: Int, @XmlRes val xml: Int)
{
	CONSOLES("consoles", R.string.settings_category_consoles, R.drawable.ic_cat_consoles, R.xml.look_consoles),
	PSN("psn", R.string.settings_category_psn, R.drawable.ic_cat_psn, R.xml.look_psn),
	STREAM("stream", R.string.settings_category_stream, R.drawable.ic_cat_stream, R.xml.look_stream),
	PICTURE("picture", R.string.settings_category_picture, R.drawable.ic_cat_picture, R.xml.look_picture),
	CONTROLLER("controller", R.string.settings_category_controller, R.drawable.ic_cat_controller, R.xml.look_controller),
	LOOK("look", R.string.settings_category_look, R.drawable.ic_cat_look, R.xml.look_look),
	HELP("help", R.string.settings_category_help, R.drawable.ic_cat_help, R.xml.look_help);

	/** What the page is set to now, shown under its name on narrow screens */
	fun state(context: Context, preferences: Preferences, registeredCount: Int?): String? = when(this)
	{
		CONSOLES -> registeredCount?.let { context.getString(R.string.settings_state_consoles, it) }
		PSN -> context.getString(if(PsnAccount.get(context).isSignedIn) R.string.settings_state_psn_in else R.string.settings_state_psn_out)
		STREAM -> context.getString(R.string.settings_state_stream,
			context.getString(preferences.resolution.title), context.getString(preferences.remoteResolution.title))
		PICTURE ->
			if(preferences.deband == Preferences.Deband.OFF && preferences.sharpen == Preferences.Sharpen.OFF)
				context.getString(R.string.settings_state_picture_off)
			else
				context.getString(R.string.settings_state_picture,
					context.getString(preferences.deband.title).lowercase(), context.getString(preferences.sharpen.title).lowercase())
		CONTROLLER -> context.getString(R.string.settings_state_controller,
			onOff(context, preferences.rumbleEnabled), onOff(context, preferences.motionEnabled))
		LOOK -> context.getString(preferences.appStyle.title)
		HELP -> context.getString(R.string.settings_state_help)
	}

	companion object
	{
		fun fromKey(key: String?) = values().firstOrNull { it.key == key }

		private fun onOff(context: Context, on: Boolean) =
			context.getString(if(on) R.string.settings_state_on else R.string.settings_state_off)
	}
}
