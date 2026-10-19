// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.metallic.chiaki.common.*
import com.metallic.chiaki.discovery.DiscoveryManager
import com.metallic.chiaki.discovery.serverMac
import kotlinx.coroutines.Dispatchers
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.metallic.chiaki.lib.PsnDevice
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainViewModel(val database: AppDatabase, val preferences: Preferences, val psnAccount: PsnAccount): ViewModel()
{
	private val psnDevices = MutableStateFlow<List<PsnDevice>>(emptyList())
	private val _psnError = MutableLiveData<String?>(null)
	/** Last error while listing the PSN consoles, null if none */
	val psnError: LiveData<String?> get() = _psnError
	private var psnRefreshJob: Job? = null

	fun refreshPsnConsoles()
	{
		if(!psnAccount.isSignedIn)
		{
			psnDevices.value = emptyList()
			return
		}
		if(psnRefreshJob?.isActive == true)
			return
		psnRefreshJob = viewModelScope.launch {
			try
			{
				psnDevices.value = psnAccount.listConsoles()
				_psnError.value = null
			}
			catch(e: Exception)
			{
				Log.w("MainViewModel", "Could not list PSN consoles", e)
				if(!psnAccount.isSignedIn)
					psnDevices.value = emptyList()
				_psnError.value = e.message ?: e.toString()
			}
		}
	}

	/** The PSN console with this name, if signed in */
	fun psnDeviceNamed(name: String?) = name?.let { n -> psnDevices.value.firstOrNull { it.name == n } }

	val discoveryManager = DiscoveryManager().also {
		it.active = preferences.discoveryEnabled
		viewModelScope.launch {
			it.discoveryActive.collect { active ->
				preferences.discoveryEnabled = active
			}
		}
	}

	val displayHosts by lazy {
		combine(
			database.manualHostDao().getAll(),
			database.registeredHostDao().getAll(),
			discoveryManager.discoveredHosts,
			psnDevices
		) { manualHosts, registeredHosts, discoveredHosts, psnDevices ->
			val macRegisteredHosts = registeredHosts.associateBy { it.serverMac }
			val idRegisteredHosts = registeredHosts.associateBy { it.id }
			val nicknameRegisteredHosts = registeredHosts.associateBy { it.serverNickname }
			val discoveredNames = discoveredHosts.mapNotNull { it.hostName }.toSet()
			discoveredHosts.map {
				DiscoveredDisplayHost(it.serverMac?.let { mac -> macRegisteredHosts[mac] }, it)
			} +
			// Like the desktop GUI: a console found on the local network is used directly instead of over PSN.
			psnDevices.filter { it.name !in discoveredNames }.map {
				PsnDisplayHost(nicknameRegisteredHosts[it.name], it)
			} +
			manualHosts.map {
				ManualDisplayHost(it.registeredHost?.let { id -> idRegisteredHosts[id] }, it)
			}
		}.asLiveData()
	}

	val discoveryActive by lazy {
		discoveryManager.discoveryActive.asLiveData()
	}

	fun deleteManualHost(manualHost: ManualHost)
	{
		viewModelScope.launch(Dispatchers.IO) {
			try {
				database.manualHostDao().delete(manualHost)
			} catch(_: Exception) {}
		}
	}

	override fun onCleared()
	{
		super.onCleared()
		discoveryManager.dispose()
	}
}
