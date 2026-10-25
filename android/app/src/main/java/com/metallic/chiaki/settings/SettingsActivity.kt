// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import com.metallic.chiaki.common.ext.padForSystemBars
import android.content.res.Resources
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.databinding.ActivitySettingsBinding
import androidx.core.content.res.ResourcesCompat
import com.metallic.chiaki.looks.AppStyle
import com.metallic.chiaki.looks.applyAppStyle

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	private lateinit var binding: ActivitySettingsBinding

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val style = applyAppStyle()
		super.onCreate(savedInstanceState)
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.padForSystemBars()
		when(style)
		{
			AppStyle.STATUS_LIGHT -> binding.titleTextView.typeface = ResourcesCompat.getFont(this, R.font.archivo_wide)
			AppStyle.CONSOLE_HOME -> binding.titleTextView.typeface = ResourcesCompat.getFont(this, R.font.mukta_light)
			AppStyle.CLASSIC -> {}
		}
		title = ""
		setSupportActionBar(binding.toolbar)

		val rootFragment = SettingsFragment()
		replaceFragment(rootFragment, false)
		supportFragmentManager.addOnBackStackChangedListener {
			val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment ?: return@addOnBackStackChangedListener
			binding.titleTextView.text = titleFragment.getTitle(resources)
		}
		binding.titleTextView.text = rootFragment.getTitle(resources)
	}

	override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference) = when(pref.fragment)
	{
		SettingsRegisteredHostsFragment::class.java.canonicalName -> {
			replaceFragment(SettingsRegisteredHostsFragment(), true)
			true
		}
		else -> false
	}

	private fun replaceFragment(fragment: Fragment, addToBackStack: Boolean)
	{
		supportFragmentManager.beginTransaction()
			.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
			.replace(R.id.settingsFragment, fragment)
			.also {
				if(addToBackStack)
					it.addToBackStack(null)
			}
			.commit()
	}
}