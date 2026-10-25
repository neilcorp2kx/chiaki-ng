// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.settings

import android.app.Activity
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.preference.*
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.regist.PsnLoginActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.common.exportAndShareAllSettings
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.common.getDatabase
import com.metallic.chiaki.common.importSettingsFromUri

class DataStore(val preferences: Preferences): PreferenceDataStore()
{
	override fun getBoolean(key: String?, defValue: Boolean) = when(key)
	{
		preferences.logVerboseKey -> preferences.logVerbose
		preferences.swapCrossMoonKey -> preferences.swapCrossMoon
		preferences.rumbleEnabledKey -> preferences.rumbleEnabled
		preferences.motionEnabledKey -> preferences.motionEnabled
		preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled
		preferences.videoFilterStatsKey -> preferences.videoFilterStats
		preferences.keyboardEnabledKey -> preferences.keyboardEnabled
		preferences.controllerMotionKey -> preferences.controllerMotion
		preferences.hideTouchWithControllerKey -> preferences.hideTouchWithController
		else -> defValue
	}

	override fun putBoolean(key: String?, value: Boolean)
	{
		when(key)
		{
			preferences.logVerboseKey -> preferences.logVerbose = value
			preferences.swapCrossMoonKey -> preferences.swapCrossMoon = value
			preferences.rumbleEnabledKey -> preferences.rumbleEnabled = value
			preferences.motionEnabledKey -> preferences.motionEnabled = value
			preferences.buttonHapticEnabledKey -> preferences.buttonHapticEnabled = value
			preferences.videoFilterStatsKey -> preferences.videoFilterStats = value
			preferences.keyboardEnabledKey -> preferences.keyboardEnabled = value
			preferences.controllerMotionKey -> preferences.controllerMotion = value
			preferences.hideTouchWithControllerKey -> preferences.hideTouchWithController = value
		}
	}

	override fun getString(key: String, defValue: String?) = when(key)
	{
		preferences.resolutionKey -> preferences.resolution.value
		preferences.fpsKey -> preferences.fps.value
		preferences.bitrateKey -> preferences.bitrate?.toString() ?: ""
		preferences.remoteResolutionKey -> preferences.remoteResolution.value
		preferences.remoteBitrateKey -> preferences.remoteBitrate?.toString() ?: ""
		preferences.codecKey -> preferences.codec.value
		preferences.debandKey -> preferences.deband.value
		preferences.leaveActionKey -> preferences.leaveAction.value
		preferences.hapticsRumbleKey -> preferences.hapticsRumble.value
		preferences.sharpenKey -> preferences.sharpen.value
		else -> defValue
	}

	override fun putString(key: String, value: String?)
	{
		when(key)
		{
			preferences.resolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.resolution = resolution
			}
			preferences.fpsKey ->
			{
				val fps = Preferences.FPS.values().firstOrNull { it.value == value } ?: return
				preferences.fps = fps
			}
			preferences.bitrateKey -> preferences.bitrate = value?.toIntOrNull()
			preferences.remoteResolutionKey ->
			{
				val resolution = Preferences.Resolution.values().firstOrNull { it.value == value } ?: return
				preferences.remoteResolution = resolution
			}
			preferences.remoteBitrateKey -> preferences.remoteBitrate = value?.toIntOrNull()
			preferences.codecKey ->
			{
				val codec = Preferences.Codec.values().firstOrNull { it.value == value } ?: return
				preferences.codec = codec
			}
			preferences.hapticsRumbleKey ->
			{
				val haptics = Preferences.HapticsRumble.values().firstOrNull { it.value == value } ?: return
				preferences.hapticsRumble = haptics
			}
			preferences.leaveActionKey ->
			{
				val action = Preferences.LeaveAction.values().firstOrNull { it.value == value } ?: return
				preferences.leaveAction = action
			}
			preferences.debandKey ->
			{
				val deband = Preferences.Deband.values().firstOrNull { it.value == value } ?: return
				preferences.deband = deband
			}
			preferences.sharpenKey ->
			{
				val sharpen = Preferences.Sharpen.values().firstOrNull { it.value == value } ?: return
				preferences.sharpen = sharpen
			}
		}
	}
}

class SettingsFragment: PreferenceFragmentCompat(), TitleFragment
{
	override fun onResume()
	{
		super.onResume()
		updatePsnAccountSummary()
	}

	private fun updatePsnAccountSummary()
	{
		val context = context ?: return
		val account = PsnAccount.get(context)
		preferenceScreen?.findPreference<Preference>("psn_account")?.summary =
			if(account.isSignedIn)
				getString(R.string.preferences_psn_account_signed_in, account.accountIdBase64)
			else
				getString(R.string.preferences_psn_account_signed_out)
	}

	private fun psnAccountClicked()
	{
		val context = context ?: return
		val account = PsnAccount.get(context)
		if(!account.isSignedIn)
		{
			startActivity(Intent(context, PsnLoginActivity::class.java))
			return
		}
		MaterialAlertDialogBuilder(context)
			.setMessage(R.string.alert_message_psn_sign_out)
			.setPositiveButton(R.string.action_psn_sign_out) { _, _ ->
				account.signOut()
				updatePsnAccountSummary()
			}
			.setNegativeButton(android.R.string.cancel) { _, _ -> }
			.show()
	}

	companion object
	{
		private const val PICK_SETTINGS_JSON_REQUEST = 1
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)
	{
		val context = context ?: return

		val viewModel = ViewModelProvider(this, viewModelFactory { SettingsViewModel(getDatabase(context), Preferences(context)) })
			.get(SettingsViewModel::class.java)

		val preferences = viewModel.preferences
		preferenceManager.preferenceDataStore = DataStore(preferences)
		setPreferencesFromResource(R.xml.preferences, rootKey)

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_fps_key))?.let {
			it.entryValues = Preferences.fpsAll.map { fps -> fps.value }.toTypedArray()
			it.entries = Preferences.fpsAll.map { fps -> getString(fps.title) }.toTypedArray()
		}

		val bitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_bitrate_key))
		val bitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.bitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
		}
		bitratePreference?.let {
			it.summaryProvider = bitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.bitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.bitrate?.toString() ?: "")
			}
		}
		viewModel.bitrateAuto.observe(this, Observer {
			bitratePreference?.summaryProvider = bitrateSummaryProvider
		})

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_remote_resolution_key))?.let {
			it.entryValues = Preferences.resolutionAll.map { res -> res.value }.toTypedArray()
			it.entries = Preferences.resolutionAll.map { res -> getString(res.title) }.toTypedArray()
		}

		val remoteBitratePreference = preferenceScreen.findPreference<EditTextPreference>(getString(R.string.preferences_remote_bitrate_key))
		val remoteBitrateSummaryProvider = Preference.SummaryProvider<EditTextPreference> {
			preferences.remoteBitrate?.toString() ?: getString(R.string.preferences_bitrate_auto, preferences.remoteBitrateAuto)
		}
		remoteBitratePreference?.let {
			it.summaryProvider = remoteBitrateSummaryProvider
			it.setOnBindEditTextListener { editText ->
				editText.hint = getString(R.string.preferences_bitrate_auto, preferences.remoteBitrateAuto)
				editText.inputType = InputType.TYPE_CLASS_NUMBER
				editText.setText(preferences.remoteBitrate?.toString() ?: "")
			}
		}
		lifecycleScope.launch {
			preferences.remoteBitrateAutoFlow.collect {
				remoteBitratePreference?.summaryProvider = remoteBitrateSummaryProvider
			}
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_codec_key))?.let {
			it.entryValues = Preferences.codecAll.map { codec -> codec.value }.toTypedArray()
			it.entries = Preferences.codecAll.map { codec -> getString(codec.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_haptics_rumble_key))?.let {
			it.entryValues = Preferences.hapticsRumbleAll.map { h -> h.value }.toTypedArray()
			it.entries = Preferences.hapticsRumbleAll.map { h -> getString(h.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_leave_action_key))?.let {
			it.entryValues = Preferences.leaveActionAll.map { action -> action.value }.toTypedArray()
			it.entries = Preferences.leaveActionAll.map { action -> getString(action.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_deband_key))?.let {
			it.entryValues = Preferences.debandAll.map { deband -> deband.value }.toTypedArray()
			it.entries = Preferences.debandAll.map { deband -> getString(deband.title) }.toTypedArray()
		}

		preferenceScreen.findPreference<ListPreference>(getString(R.string.preferences_sharpen_key))?.let {
			it.entryValues = Preferences.sharpenAll.map { sharpen -> sharpen.value }.toTypedArray()
			it.entries = Preferences.sharpenAll.map { sharpen -> getString(sharpen.title) }.toTypedArray()
		}

		val registeredHostsPreference = preferenceScreen.findPreference<Preference>("registered_hosts")
		viewModel.registeredHostsCount.observe(this, Observer {
			registeredHostsPreference?.summary = getString(R.string.preferences_registered_hosts_summary, it)
		})

		preferenceScreen.findPreference<Preference>("psn_account")?.setOnPreferenceClickListener { psnAccountClicked(); true }

		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_export_settings_key))?.setOnPreferenceClickListener { exportSettings(); true }
		preferenceScreen.findPreference<Preference>(getString(R.string.preferences_import_settings_key))?.setOnPreferenceClickListener { importSettings(); true }
	}

	override fun getTitle(resources: Resources): String = resources.getString(R.string.title_settings)

	private fun exportSettings()
	{
		val activity = activity ?: return
		exportAndShareAllSettings(activity, lifecycleScope)
	}

	private fun importSettings()
	{
		val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
			addCategory(Intent.CATEGORY_OPENABLE)
			type = "application/json"
		}
		startActivityForResult(intent, PICK_SETTINGS_JSON_REQUEST)
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)
	{
		if(requestCode == PICK_SETTINGS_JSON_REQUEST && resultCode == Activity.RESULT_OK)
		{
			val activity = activity ?: return
			data?.data?.also {
				importSettingsFromUri(activity, it, lifecycleScope)
			}
		}
	}
}
