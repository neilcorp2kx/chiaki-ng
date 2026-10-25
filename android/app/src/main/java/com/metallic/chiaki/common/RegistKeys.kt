// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.util.Log

/**
 * The Remote Play keys of registered consoles (rp_regist_key, rp_key) are stored encrypted with
 * a key in the Android Keystore, like the PSN tokens. The app works with them in plain form;
 * RegisteredHostDao seals them on the way into the database and opens them on the way out.
 * Keys stored before this (plain, 16 bytes) still open, and are sealed at the next start.
 */
object RegistKeys
{
	private const val TAG = "RegistKeys"
	/** CHIAKI_SESSION_AUTH_SIZE and the rp_key size */
	private const val PLAIN_SIZE = 0x10
	private val cipher = KeystoreCipher("chiaki_regist_keys")

	/** Sealed data is longer than a plain key (format byte, IV, GCM tag) */
	private fun isSealed(bytes: ByteArray) = bytes.size > PLAIN_SIZE

	fun hasPlainKeys(host: RegisteredHost) = !isSealed(host.rpRegistKey) || !isSealed(host.rpKey)

	fun seal(host: RegisteredHost) = host.copy(
		rpRegistKey = if(isSealed(host.rpRegistKey)) host.rpRegistKey else cipher.encrypt(host.rpRegistKey),
		rpKey = if(isSealed(host.rpKey)) host.rpKey else cipher.encrypt(host.rpKey))

	/** Null if the keys can't be opened (for example the Keystore key is gone): the console must be registered again */
	fun open(host: RegisteredHost): RegisteredHost? = try
	{
		host.copy(
			rpRegistKey = if(isSealed(host.rpRegistKey)) cipher.decrypt(host.rpRegistKey) else host.rpRegistKey,
			rpKey = if(isSealed(host.rpKey)) cipher.decrypt(host.rpKey) else host.rpKey)
	}
	catch(e: Exception)
	{
		Log.w(TAG, "Could not open the Remote Play keys of ${host.serverNickname}, it needs registering again", e)
		null
	}
}
