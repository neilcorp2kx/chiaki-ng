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
import com.metallic.chiaki.looks.applyAppStyle
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.databinding.ActivitySettingsLooksBinding
import com.metallic.chiaki.databinding.LookSettingsCategoryBinding

interface TitleFragment
{
	fun getTitle(resources: Resources): String
}

class SettingsActivity: AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback
{
	companion object
	{
		/** Open this page (SettingsCategory.key) in the Status light and Console home looks */
		const val EXTRA_CATEGORY = "category"
		private const val STATE_CATEGORY = "category"
	}

	private lateinit var binding: ActivitySettingsBinding
	private var looks: ActivitySettingsLooksBinding? = null
	private var category: SettingsCategory? = null
	private val categoryViews = mutableMapOf<SettingsCategory, LookSettingsCategoryBinding>()
	private var registeredCount: Int? = null

	/** Categories and page side by side; otherwise one at a time (folded) */
	private val wide get() = resources.configuration.screenWidthDp >= 600

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val style = applyAppStyle()
		super.onCreate(savedInstanceState)
		if(style.isClassic)
			setupClassic()
		else
			setupLooks(savedInstanceState)
	}

	private fun setupClassic()
	{
		binding = ActivitySettingsBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.padForSystemBars()
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

	private fun setupLooks(savedInstanceState: Bundle?)
	{
		val b = ActivitySettingsLooksBinding.inflate(layoutInflater)
		looks = b
		setContentView(b.root)
		b.contentLayout.padForSystemBars()

		for(c in SettingsCategory.values())
		{
			val item = LookSettingsCategoryBinding.inflate(layoutInflater, b.categoriesLayout, false)
			item.iconImageView.setImageResource(c.icon)
			item.titleTextView.setText(c.title)
			item.root.setOnClickListener { openCategory(c) }
			b.categoriesLayout.addView(item.root)
			categoryViews[c] = item
		}
		b.railLayout.layoutParams = b.railLayout.layoutParams.apply {
			width = if(wide) (300 * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
		}
		b.backButton.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

		val saved = SettingsCategory.fromKey(savedInstanceState?.getString(STATE_CATEGORY))
		val requested = SettingsCategory.fromKey(intent.getStringExtra(EXTRA_CATEGORY))
		when
		{
			// The fragment manager restores the open page itself.
			saved != null -> { category = saved; showPane(true) }
			requested != null -> openCategory(requested)
			wide -> openCategory(SettingsCategory.CONSOLES)
			else -> showPane(false)
		}

		supportFragmentManager.addOnBackStackChangedListener { updatePaneHeader() }
		onBackPressedDispatcher.addCallback(this) {
			when
			{
				supportFragmentManager.backStackEntryCount > 0 -> supportFragmentManager.popBackStack()
				!wide && category != null && requested == null ->
				{
					category = null
					showPane(false)
				}
				else ->
				{
					isEnabled = false
					onBackPressedDispatcher.onBackPressed()
				}
			}
		}

		ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(this), Preferences(this)) })
			.get(SettingsViewModel::class.java)
			.registeredHostsCount.observe(this) {
				registeredCount = it
				updateStates()
			}
		updatePaneHeader()
	}

	private fun openCategory(c: SettingsCategory)
	{
		category = c
		supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
		replaceFragment(SettingsFragment.newInstance(c.xml, getString(c.title)), false)
		showPane(true)
		if(!wide)
			looks?.backButton?.requestFocus()
	}

	private fun showPane(show: Boolean)
	{
		val b = looks ?: return
		if(wide)
		{
			b.railLayout.isVisible = true
			b.railDivider.isVisible = true
			b.paneLayout.isVisible = true
		}
		else
		{
			b.railLayout.isVisible = !show
			b.railDivider.isVisible = false
			b.paneLayout.isVisible = show
		}
		categoryViews.forEach { (c, item) -> item.root.isActivated = c == category && wide }
		updatePaneHeader()
	}

	private fun updatePaneHeader()
	{
		val b = looks ?: return
		val titleFragment = supportFragmentManager.findFragmentById(R.id.settingsFragment) as? TitleFragment
		b.paneTitleTextView.text = titleFragment?.getTitle(resources) ?: category?.let { getString(it.title) } ?: ""
		b.backButton.isVisible = !wide || supportFragmentManager.backStackEntryCount > 0
	}

	private fun updateStates()
	{
		val b = looks ?: return
		val preferences = Preferences(this)
		for((c, item) in categoryViews)
		{
			val state = c.state(this, preferences, registeredCount)
			item.stateTextView.text = state
			item.stateTextView.isVisible = !wide && state != null
		}
		b.railSubtitleTextView.setText(if(PsnAccount.get(this).isSignedIn) R.string.home_psn_signed_in else R.string.home_psn_signed_out)
	}

	override fun onResume()
	{
		super.onResume()
		updateStates()
	}

	override fun onSaveInstanceState(outState: Bundle)
	{
		super.onSaveInstanceState(outState)
		category?.let { outState.putString(STATE_CATEGORY, it.key) }
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
			.let { if(addToBackStack) it.commit() else it.commitNow() }
	}
}