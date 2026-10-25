// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.app.Application
import android.content.Context
import androidx.lifecycle.*
import android.util.Log
import com.metallic.chiaki.common.LogManager
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.getDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.metallic.chiaki.session.StreamSession
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.*
import com.metallic.chiaki.session.StreamInput

class StreamViewModel(val application: Application, val connectInfo: ConnectInfo): ViewModel()
{
	val preferences = Preferences(application)
	val logManager = LogManager(application)

	private var _session: StreamSession? = null
	val input = StreamInput(application, preferences)
	val session = StreamSession(connectInfo, logManager, preferences.logVerbose, input) { host ->
		viewModelScope.launch(Dispatchers.IO) {
			val database = getDatabase(application)
			val registeredHost = RegisteredHost(host)
			database.registeredHostDao().deleteByMac(registeredHost.serverMac)
			database.registeredHostDao().insert(registeredHost)
		}
	}

	init
	{
		if(connectInfo.isPsn)
		{
			try { PsnAccount.get(application).ensureCaBundle() }
			catch(e: Exception) { Log.e("StreamViewModel", "Could not prepare CA certificates for PSN", e) }
		}
	}

	private var _onScreenControlsEnabled = MutableLiveData<Boolean>(preferences.onScreenControlsEnabled)
	val onScreenControlsEnabled: LiveData<Boolean> get() = _onScreenControlsEnabled

	private val _controllerConnected = MutableLiveData(false)
	fun setControllerConnected(connected: Boolean) { _controllerConnected.value = connected }

	/** Touch controls are shown when enabled, and hidden while a game controller is connected */
	val onScreenControlsVisible: LiveData<Boolean> = MediatorLiveData<Boolean>().also { visible ->
		fun update() { visible.value = (_onScreenControlsEnabled.value ?: true) &&
				!(preferences.hideTouchWithController && _controllerConnected.value == true) }
		visible.addSource(_onScreenControlsEnabled) { update() }
		visible.addSource(_controllerConnected) { update() }
	}

	private var _touchpadOnlyEnabled = MutableLiveData<Boolean>(preferences.touchpadOnlyEnabled)
	val touchpadOnlyEnabled: LiveData<Boolean> get() = _touchpadOnlyEnabled

	override fun onCleared()
	{
		super.onCleared()
		_session?.shutdown()
	}

	fun setOnScreenControlsEnabled(enabled: Boolean)
	{
		preferences.onScreenControlsEnabled = enabled
		_onScreenControlsEnabled.value = enabled
	}

	fun setTouchpadOnlyEnabled(enabled: Boolean)
	{
		preferences.touchpadOnlyEnabled = enabled
		_touchpadOnlyEnabled.value = enabled
	}
}