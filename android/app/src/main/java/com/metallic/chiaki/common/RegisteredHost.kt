// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import androidx.room.*
import androidx.room.ColumnInfo.Companion.BLOB
import com.metallic.chiaki.lib.RegistHost
import com.metallic.chiaki.lib.Target
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Entity(tableName = "registered_host")
data class RegisteredHost(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	@ColumnInfo(name = "target") val target: Target,
	@ColumnInfo(name = "ap_ssid") val apSsid: String?,
	@ColumnInfo(name = "ap_bssid") val apBssid: String?,
	@ColumnInfo(name = "ap_key") val apKey: String?,
	@ColumnInfo(name = "ap_name") val apName: String?,
	@ColumnInfo(name = "server_mac") val serverMac: MacAddress,
	@ColumnInfo(name = "server_nickname") val serverNickname: String?,
	@ColumnInfo(name = "rp_regist_key", typeAffinity = BLOB) val rpRegistKey: ByteArray, // CHIAKI_SESSION_AUTH_SIZE
	@ColumnInfo(name = "rp_key_type") val rpKeyType: Int,
	@ColumnInfo(name = "rp_key", typeAffinity = BLOB) val rpKey: ByteArray // 0x10
)
{
	constructor(registHost: RegistHost) : this(
		target = registHost.target,
		apSsid = registHost.apSsid,
		apBssid = registHost.apBssid,
		apKey = registHost.apKey,
		apName = registHost.apName,
		serverMac = MacAddress(registHost.serverMac),
		serverNickname = registHost.serverNickname,
		rpRegistKey = registHost.rpRegistKey,
		rpKeyType = registHost.rpKeyType.toInt(),
		rpKey = registHost.rpKey
	)

	override fun equals(other: Any?): Boolean
	{
		if(this === other) return true
		if(javaClass != other?.javaClass) return false

		other as RegisteredHost

		if(id != other.id) return false
		if(target != other.target) return false
		if(apSsid != other.apSsid) return false
		if(apBssid != other.apBssid) return false
		if(apKey != other.apKey) return false
		if(apName != other.apName) return false
		if(serverMac != other.serverMac) return false
		if(serverNickname != other.serverNickname) return false
		if(!rpRegistKey.contentEquals(other.rpRegistKey)) return false
		if(rpKeyType != other.rpKeyType) return false
		if(!rpKey.contentEquals(other.rpKey)) return false

		return true
	}

	override fun hashCode(): Int
	{
		var result = id.hashCode()
		result = 31 * result + target.hashCode()
		result = 31 * result + (apSsid?.hashCode() ?: 0)
		result = 31 * result + (apBssid?.hashCode() ?: 0)
		result = 31 * result + (apKey?.hashCode() ?: 0)
		result = 31 * result + (apName?.hashCode() ?: 0)
		result = 31 * result + serverMac.hashCode()
		result = 31 * result + (serverNickname?.hashCode() ?: 0)
		result = 31 * result + rpRegistKey.contentHashCode()
		result = 31 * result + rpKeyType
		result = 31 * result + rpKey.contentHashCode()
		return result
	}
}

/**
 * Registered consoles. Their Remote Play keys are sealed in the database (RegistKeys); the
 * functions without "Sealed" in the name give and take plain keys.
 */
@Dao
abstract class RegisteredHostDao
{
	@Query("SELECT * FROM registered_host")
	abstract fun getAllSealed(): Flow<List<RegisteredHost>>

	fun getAll(): Flow<List<RegisteredHost>> = getAllSealed().map { hosts -> hosts.mapNotNull { RegistKeys.open(it) } }

	@Query("SELECT * FROM registered_host WHERE server_mac == :mac LIMIT 1")
	abstract suspend fun getByMacSealed(mac: MacAddress): RegisteredHost?

	suspend fun getByMac(mac: MacAddress): RegisteredHost? =
		getByMacSealed(mac)?.let { withContext(Dispatchers.IO) { RegistKeys.open(it) } }

	@Query("DELETE FROM registered_host WHERE server_mac == :mac")
	abstract suspend fun deleteByMac(mac: MacAddress)

	@Delete
	abstract suspend fun delete(host: RegisteredHost)

	@Query("SELECT COUNT(*) FROM registered_host")
	abstract fun count(): Flow<Int>

	@Insert
	abstract suspend fun insertSealed(host: RegisteredHost): Long

	suspend fun insert(host: RegisteredHost): Long = insertSealed(withContext(Dispatchers.IO) { RegistKeys.seal(host) })

	@Query("SELECT * FROM registered_host")
	abstract suspend fun getAllSealedNow(): List<RegisteredHost>

	@Update
	abstract suspend fun updateSealed(host: RegisteredHost)

	/** Seals keys stored in plain form by versions before the encryption */
	suspend fun sealPlainKeys()
	{
		for(host in getAllSealedNow().filter { RegistKeys.hasPlainKeys(it) })
			updateSealed(withContext(Dispatchers.IO) { RegistKeys.seal(host) })
	}
}
