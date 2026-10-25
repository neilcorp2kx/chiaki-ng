// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a key that stays in the Android Keystore (it cannot be read out, also not
 * by the app). Output: [version][12-byte IV][ciphertext + GCM tag].
 */
class KeystoreCipher(private val alias: String)
{
	companion object
	{
		private const val KEYSTORE = "AndroidKeyStore"
		private const val TRANSFORMATION = "AES/GCM/NoPadding"
		private const val FORMAT_VERSION: Byte = 1
		private const val IV_SIZE = 12
		private const val TAG_BITS = 128
	}

	private fun key(): SecretKey
	{
		val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
		(keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
		generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
			.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
			.setKeySize(256)
			.build())
		return generator.generateKey()
	}

	fun encrypt(plain: ByteArray): ByteArray
	{
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks a random IV
		val iv = cipher.iv
		check(iv.size == IV_SIZE)
		return byteArrayOf(FORMAT_VERSION) + iv + cipher.doFinal(plain)
	}

	/** Throws if the data was not made by this key or was changed */
	fun decrypt(data: ByteArray): ByteArray
	{
		require(data.size > 1 + IV_SIZE && data[0] == FORMAT_VERSION) { "Unknown encrypted data format" }
		val cipher = Cipher.getInstance(TRANSFORMATION)
		cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 1, IV_SIZE))
		return cipher.doFinal(data, 1 + IV_SIZE, data.size - 1 - IV_SIZE)
	}
}
