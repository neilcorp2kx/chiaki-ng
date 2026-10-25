// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.content.Context
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.PsnDisplayHost
import com.metallic.chiaki.lib.DiscoveryHost

/** The console's own power light: white when on, amber in rest mode, unlit when not reachable */
enum class ConsoleLight { ON, STANDBY, OFF }

/** What the Status light and Console home looks show for a console, in the user's terms */
data class ConsoleStatus(
	val key: String,
	val name: String,
	val light: ConsoleLight,
	val state: String,
	val route: String,
	val app: String?,
	/** Label of the main action; the action itself is MainActivity.hostTriggered */
	val action: String,
	val canWake: Boolean,
	val canEdit: Boolean)
{
	val hasOptions get() = canWake || canEdit

	companion object
	{
		/** @param registersOverPsn the console is not registered but is on the signed-in PSN account */
		fun of(context: Context, host: DisplayHost, registersOverPsn: Boolean): ConsoleStatus
		{
			val name = host.name?.takeIf { it.isNotBlank() } ?: host.host.ifBlank { context.getString(R.string.console_unnamed) }
			val registered = host.isRegistered
			var app: String? = null
			val light: ConsoleLight
			val state: String
			val route: String
			val action: Int
			when(host)
			{
				is PsnDisplayHost ->
				{
					val wake = host.device.networkWakeupEnabled
					light = if(wake) ConsoleLight.ON else ConsoleLight.OFF
					state = context.getString(if(wake) R.string.console_state_psn else R.string.console_state_psn_no_wake)
					route = context.getString(if(wake) R.string.console_route_psn else R.string.console_route_psn_no_wake)
					// Every PSN connection registers over PSN when needed, without a PIN.
					action = if(registered) R.string.action_play else R.string.action_register_and_play
				}
				is DiscoveredDisplayHost ->
				{
					val discovered = host.discoveredHost.state
					light = when(discovered)
					{
						DiscoveryHost.State.READY -> ConsoleLight.ON
						DiscoveryHost.State.STANDBY -> ConsoleLight.STANDBY
						else -> ConsoleLight.OFF
					}
					app = host.discoveredHost.runningAppName?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.console_app, it) }
					if(registered)
					{
						state = context.getString(when(discovered)
						{
							DiscoveryHost.State.READY -> R.string.console_state_ready
							DiscoveryHost.State.STANDBY -> R.string.console_state_resting
							else -> R.string.console_state_not_found
						})
						route = context.getString(R.string.console_route_lan, host.host)
						action = if(discovered == DiscoveryHost.State.STANDBY) R.string.action_wake_and_play else R.string.action_play
					}
					else
					{
						state = context.getString(R.string.console_state_unregistered)
						route = context.getString(if(registersOverPsn) R.string.console_route_unregistered_psn else R.string.console_route_unregistered)
						action = if(registersOverPsn) R.string.action_register_and_play else R.string.action_register_short
					}
				}
				is ManualDisplayHost ->
				{
					light = ConsoleLight.OFF
					state = context.getString(if(registered) R.string.console_state_not_found else R.string.console_state_unregistered)
					route = context.getString(R.string.console_route_manual, host.host)
					action = if(registered) R.string.action_connect_short else R.string.action_register_short
				}
			}
			val key = when(host)
			{
				is PsnDisplayHost -> "psn:${host.device.duid}"
				is DiscoveredDisplayHost -> "lan:${host.id ?: host.host}"
				is ManualDisplayHost -> "manual:${host.manualHost.id}"
			}
			return ConsoleStatus(
				key = key,
				name = name,
				light = light,
				state = state,
				route = route,
				app = app,
				action = context.getString(action),
				canWake = registered && host !is PsnDisplayHost,
				canEdit = host is ManualDisplayHost)
		}
	}
}
