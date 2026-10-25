// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.main

import com.metallic.chiaki.common.ext.padForSystemBars
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import com.metallic.chiaki.lib.PsnDevice
import com.metallic.chiaki.regist.PsnLoginActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.*
import com.metallic.chiaki.common.ext.putRevealExtra
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityMainBinding
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.stream.StreamActivity

class MainActivity : AppCompatActivity()
{
	companion object
	{
		/** The desktop GUI waits up to 25 s for a console to wake */
		private const val WAKE_TIMEOUT_MS = 25_000L
	}

	private lateinit var viewModel: MainViewModel

	private lateinit var binding: ActivityMainBinding
	private var discoveryMenuItem: MenuItem? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		binding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(binding.root)
		binding.root.padForSystemBars()

		title = ""
		setSupportActionBar(binding.toolbar)

		binding.floatingActionButton.setOnClickListener {
			expandFloatingActionButton(!binding.floatingActionButton.isExpanded)
		}
		binding.floatingActionButtonDialBackground.setOnClickListener {
			expandFloatingActionButton(false)
		}

		binding.addManualButton.setOnClickListener { addManualConsole() }
		binding.addManualLabelButton.setOnClickListener { addManualConsole() }

		binding.registerButton.setOnClickListener { showRegistration() }
		binding.registerLabelButton.setOnClickListener { showRegistration() }

		setupBackNavigation()

		viewModel = ViewModelProvider(this, viewModelFactory { MainViewModel(getDatabase(this), Preferences(this), PsnAccount.get(this)) })
			.get(MainViewModel::class.java)

		val recyclerViewAdapter = DisplayHostRecyclerViewAdapter(this::hostTriggered, this::wakeupHost, this::editHost, this::deleteHost)
		binding.hostsRecyclerView.adapter = recyclerViewAdapter
		binding.hostsRecyclerView.layoutManager = LinearLayoutManager(this)
		viewModel.displayHosts.observe(this, Observer {
			val top = binding.hostsRecyclerView.computeVerticalScrollOffset() == 0
			recyclerViewAdapter.hosts = it
			if(top)
				binding.hostsRecyclerView.scrollToPosition(0)
			updateEmptyInfo()
		})

		viewModel.psnError.observe(this, Observer { error ->
			if(error != null)
				Toast.makeText(this, getString(R.string.psn_consoles_error, error), Toast.LENGTH_LONG).show()
		})

		viewModel.discoveryActive.observe(this, Observer { active ->
			discoveryMenuItem?.let { updateDiscoveryMenuItem(it, active) }
			updateEmptyInfo()
		})
	}

	private fun updateEmptyInfo()
	{
		if(viewModel.displayHosts.value?.isEmpty() ?: true)
		{
			binding.emptyInfoLayout.visibility = View.VISIBLE
			val discoveryActive = viewModel.discoveryActive.value ?: false
			binding.emptyInfoImageView.setImageResource(if(discoveryActive) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
			binding.emptyInfoTextView.setText(if(discoveryActive) R.string.display_hosts_empty_discovery_on_info else R.string.display_hosts_empty_discovery_off_info)
		}
		else
			binding.emptyInfoLayout.visibility = View.GONE
	}

	private fun expandFloatingActionButton(expand: Boolean)
	{
		binding.floatingActionButton.isExpanded = expand
		binding.floatingActionButton.isActivated = binding.floatingActionButton.isExpanded
	}

	override fun onStart()
	{
		super.onStart()
		viewModel.discoveryManager.resume()
		viewModel.refreshPsnConsoles()
	}

	override fun onStop()
	{
		super.onStop()
		viewModel.discoveryManager.pause()
	}

	private fun setupBackNavigation()
	{
		onBackPressedDispatcher.addCallback(this) {
			if(binding.floatingActionButton.isExpanded)
			{
				expandFloatingActionButton(false)
			}
			else
			{
				isEnabled = false
				onBackPressedDispatcher.onBackPressed()
			}
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean
	{
		menuInflater.inflate(R.menu.main, menu)
		val discoveryItem = menu.findItem(R.id.action_discover)
		discoveryMenuItem = discoveryItem
		val discoveryActive = viewModel.discoveryActive.value ?: false
		updateDiscoveryMenuItem(discoveryItem, discoveryActive)
		return true
	}

	private fun updateDiscoveryMenuItem(item: MenuItem, active: Boolean)
	{
		item.isChecked = active
		item.setIcon(if(active) R.drawable.ic_discover_on else R.drawable.ic_discover_off)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean = when(item.itemId)
	{
		R.id.action_discover ->
		{
			viewModel.discoveryManager.active = !(viewModel.discoveryActive.value ?: false)
			true
		}

		R.id.action_settings ->
		{
			Intent(this, SettingsActivity::class.java).also {
				startActivity(it)
			}
			true
		}

		else -> super.onOptionsItemSelected(item)
	}

	private fun addManualConsole()
	{
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putRevealExtra(binding.addManualButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun showRegistration()
	{
		Intent(this, RegistActivity::class.java).also {
			it.putRevealExtra(binding.registerButton, binding.rootLayout)
			startActivity(it, ActivityOptions.makeSceneTransitionAnimation(this).toBundle())
		}
	}

	private fun connectPsn(device: PsnDevice, registeredHost: RegisteredHost?)
	{
		val psnAccount = PsnAccount.get(this)
		lifecycleScope.launch {
			val token = try
			{
				psnAccount.accessToken()
			}
			catch(e: PsnAccount.UnauthorizedException)
			{
				MaterialAlertDialogBuilder(this@MainActivity)
					.setMessage(R.string.psn_sign_in_needed)
					.setPositiveButton(R.string.action_psn_sign_in) { _, _ ->
						startActivity(Intent(this@MainActivity, PsnLoginActivity::class.java))
					}
					.setNegativeButton(android.R.string.cancel) { _, _ -> }
					.show()
				return@launch
			}
			catch(e: Exception)
			{
				Toast.makeText(this@MainActivity, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
				return@launch
			}
			// Every PSN connection registers over PSN, so no PIN is needed. Without a stored
			// registration, register first (autoRegist) to keep the keys for the local network.
			val connectInfo = ConnectInfo(
				ps5 = device.ps5,
				host = "",
				registKey = registeredHost?.rpRegistKey ?: ByteArray(0x10),
				morning = registeredHost?.rpKey ?: ByteArray(0x10),
				videoProfile = Preferences(this@MainActivity).remoteVideoProfile,
				duid = device.duid,
				psnToken = token,
				psnAccountId = psnAccount.accountId,
				autoRegist = registeredHost == null,
				enableKeyboard = Preferences(this@MainActivity).keyboardEnabled,
				hapticsRumblePercent = if(device.ps5) Preferences(this@MainActivity).hapticsRumblePercent else 0)
			Intent(this@MainActivity, StreamActivity::class.java).let {
				it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
				startActivity(it)
			}
		}
	}

	private fun hostTriggered(host: DisplayHost)
	{
		if(host is PsnDisplayHost)
		{
			connectPsn(host.device, host.registeredHost)
			return
		}
		val registeredHost = host.registeredHost
		if(registeredHost != null)
		{
			fun connect(address: String = host.host) {
				val preferences = Preferences(this)
				val connectInfo = ConnectInfo(host.isPS5, address, registeredHost.rpRegistKey, registeredHost.rpKey, preferences.videoProfile,
					enableKeyboard = preferences.keyboardEnabled,
					hapticsRumblePercent = if(host.isPS5) preferences.hapticsRumblePercent else 0)
				Intent(this, StreamActivity::class.java).let {
					it.putExtra(StreamActivity.EXTRA_CONNECT_INFO, connectInfo)
					startActivity(it)
				}
			}

			if(host is DiscoveredDisplayHost && host.discoveredHost.state == DiscoveryHost.State.STANDBY)
				wakeAndConnect(host, registeredHost) { address -> connect(address) }
			else
				connect()
		}
		else if(viewModel.psnDeviceNamed(host.name)?.takeIf { host.isPS5 } != null)
		{
			// Signed in to PSN and this console is on the account: register over PSN, no PIN.
			connectPsn(viewModel.psnDeviceNamed(host.name)!!, null)
		}
		else
		{
			Intent(this, RegistActivity::class.java).let {
				it.putExtra(RegistActivity.EXTRA_HOST, host.host)
				it.putExtra(RegistActivity.EXTRA_BROADCAST, false)
				if(host is ManualDisplayHost)
					it.putExtra(RegistActivity.EXTRA_ASSIGN_MANUAL_HOST_ID, host.manualHost.id)
				startActivity(it)
			}
		}
	}

	private var wakeJob: Job? = null

	/** Like the desktop GUI: wake the console, wait until discovery reports it ready, then connect */
	private fun wakeAndConnect(host: DiscoveredDisplayHost, registeredHost: RegisteredHost, connect: (address: String) -> Unit)
	{
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
		val name = host.name ?: host.host
		val consoleId = host.discoveredHost.hostId
		val dialog = MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.waking_console, name))
			.setNegativeButton(android.R.string.cancel) { _, _ -> wakeJob?.cancel() }
			.setCancelable(false)
			.show()
		wakeJob?.cancel()
		wakeJob = lifecycleScope.launch {
			val readyHost = withTimeoutOrNull(WAKE_TIMEOUT_MS) {
				viewModel.discoveryManager.discoveredHosts
					.map { hosts -> hosts.firstOrNull { it.hostId == consoleId && it.state == DiscoveryHost.State.READY } }
					.first { it != null }
			}
			dialog.dismiss()
			if(readyHost != null)
				connect(readyHost.hostAddr ?: host.host)
			else
				MaterialAlertDialogBuilder(this@MainActivity)
					.setMessage(getString(R.string.waking_console_failed, name))
					.setPositiveButton(R.string.action_connect_anyway) { _, _ -> connect(host.host) }
					.setNegativeButton(android.R.string.cancel) { _, _ -> }
					.show()
		}
	}

	private fun wakeupHost(host: DisplayHost)
	{
		val registeredHost = host.registeredHost ?: return
		viewModel.discoveryManager.sendWakeup(host.host, registeredHost.rpRegistKey, registeredHost.target.isPS5)
	}

	private fun editHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		Intent(this, EditManualConsoleActivity::class.java).also {
			it.putExtra(EditManualConsoleActivity.EXTRA_MANUAL_HOST_ID, host.manualHost.id)
			startActivity(it)
		}
	}

	private fun deleteHost(host: DisplayHost)
	{
		if(host !is ManualDisplayHost)
			return
		MaterialAlertDialogBuilder(this)
			.setMessage(getString(R.string.alert_message_delete_manual_host, host.manualHost.host))
			.setPositiveButton(R.string.action_delete) { _, _ ->
				viewModel.deleteManualHost(host.manualHost)
			}
			.setNegativeButton(R.string.action_keep) { _, _ -> }
			.create()
			.show()
	}
}
