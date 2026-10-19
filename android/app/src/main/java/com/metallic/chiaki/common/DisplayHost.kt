// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.PsnDevice

sealed class DisplayHost
{
	abstract val registeredHost: RegisteredHost?
	abstract val host: String
	abstract val name: String?
	abstract val id: String?
	abstract val isPS5: Boolean

	val isRegistered get() = registeredHost != null
}

class DiscoveredDisplayHost(
	override val registeredHost: RegisteredHost?,
	val discoveredHost: DiscoveryHost
): DisplayHost()
{
	override val host get() = discoveredHost.hostAddr ?: ""
	override val name get() = discoveredHost.hostName ?: registeredHost?.serverNickname
	override val id get() = discoveredHost.hostId ?: registeredHost?.serverMac?.toString()
	override val isPS5 get() = discoveredHost.isPS5

	override fun equals(other: Any?): Boolean =
		if(other !is DiscoveredDisplayHost)
			false
		else
			other.discoveredHost == discoveredHost && other.registeredHost == registeredHost

	override fun hashCode() = 31 * (registeredHost?.hashCode() ?: 0) + discoveredHost.hashCode()

	override fun toString() = "DiscoveredDisplayHost{${registeredHost}, ${discoveredHost}}"
}

/** A console of the signed-in PSN account, reached through Remote Play over PSN */
class PsnDisplayHost(
	override val registeredHost: RegisteredHost?,
	val device: PsnDevice
): DisplayHost()
{
	override val host get() = ""
	override val name get() = device.name
	override val id get() = registeredHost?.serverMac?.toString()
	override val isPS5 get() = device.ps5

	override fun equals(other: Any?): Boolean =
		other is PsnDisplayHost && other.device == device && other.registeredHost == registeredHost

	override fun hashCode() = 31 * (registeredHost?.hashCode() ?: 0) + device.hashCode()

	override fun toString() = "PsnDisplayHost{${registeredHost}, ${device}}"
}

class ManualDisplayHost(
	override val registeredHost: RegisteredHost?,
	val manualHost: ManualHost
): DisplayHost()
{
	override val host get() = manualHost.host
	override val name get() = registeredHost?.serverNickname
	override val id get() = registeredHost?.serverMac?.toString()
	override val isPS5: Boolean get() = registeredHost?.target?.isPS5 ?: false

	override fun equals(other: Any?): Boolean =
		if(other !is ManualDisplayHost)
			false
		else
			other.manualHost == manualHost && other.registeredHost == registeredHost

	override fun hashCode() = 31 * (registeredHost?.hashCode() ?: 0) + manualHost.hashCode()

	override fun toString() = "ManualDisplayHost{${registeredHost}, ${manualHost}}"
}