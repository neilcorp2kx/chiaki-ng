// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences

/** The app's look. Classic is the original design and stays the default. */
enum class AppStyle(
	val value: String,
	@StringRes val title: Int,
	@StyleRes val mainTheme: Int,
	@StyleRes val streamTheme: Int,
	/** Register, add by address and their progress screens */
	@StyleRes val formTheme: Int)
{
	CLASSIC("classic", R.string.preferences_app_style_classic, R.style.AppTheme, R.style.StreamTheme, R.style.MageTheme),
	STATUS_LIGHT("status_light", R.string.preferences_app_style_status_light, R.style.StatusLightTheme, R.style.StreamTheme_StatusLight, R.style.MageTheme_StatusLight),
	CONSOLE_HOME("console_home", R.string.preferences_app_style_console_home, R.style.ConsoleHomeTheme, R.style.StreamTheme_ConsoleHome, R.style.MageTheme_ConsoleHome);

	val isClassic get() = this == CLASSIC

	companion object
	{
		val default = CLASSIC
		fun fromValue(value: String?) = values().firstOrNull { it.value == value } ?: default
	}
}

/** Which kind of screen an activity is, for its theme in each look */
enum class LookScreen { MAIN, STREAM, FORM }

/** Sets the theme of the chosen look. Call before super.onCreate(). Classic keeps the manifest's theme. */
fun Activity.applyAppStyle(screen: LookScreen = LookScreen.MAIN): AppStyle
{
	val style = Preferences(this).appStyle
	if(!style.isClassic)
		setTheme(when(screen)
		{
			LookScreen.MAIN -> style.mainTheme
			LookScreen.STREAM -> style.streamTheme
			LookScreen.FORM -> style.formTheme
		})
	return style
}

/** False when the user turned animations off in Android (developer options or accessibility) */
fun Context.animationsEnabled(): Boolean =
	if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		ValueAnimator.areAnimatorsEnabled()
	else
		Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
