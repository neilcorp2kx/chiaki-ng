// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.metallic.chiaki.lib.PsnDevice
import com.metallic.chiaki.lib.psnListDevices
import com.metallic.chiaki.lib.setPsnCaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

/**
 * The signed-in PSN account, used for the Account ID and for Remote Play over PSN.
 * Same OAuth flow as the desktop GUI (gui/src/psnaccountid.cpp, gui/src/psntoken.cpp).
 *
 * Tokens are stored in noBackupFilesDir, so they are not part of Android backups
 * and not part of the settings export.
 */
class PsnAccount private constructor(private val context: Context)
{
	companion object
	{
		private const val TAG = "PsnAccount"

		// Values from gui/include/psnaccountid.h
		const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"

		/** Refresh this long before the access token expires */
		private const val EXPIRY_MARGIN_MS = 60_000L

		private const val FILE_NAME = "psn_account.json"
		private const val CA_FILE_NAME = "psn_cacerts.pem"
		private val CA_DIRS = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")

		@Volatile private var instance: PsnAccount? = null
		fun get(context: Context) = instance ?: synchronized(this) {
			instance ?: PsnAccount(context.applicationContext).also { instance = it }
		}

		/**
		 * Login URL with a fresh client duid, like chiaki_holepunch_generate_client_device_uid().
		 * Tokens for Remote Play over PSN must be created with a duid.
		 */
		fun loginUrl(): String
		{
			val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
			val duid = "0000000700410080" + random.joinToString("") { "%02x".format(it) }
			return "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
					"?service_entity=urn:service-entity:psn&response_type=code" +
					"&client_id=$CLIENT_ID" +
					"&redirect_uri=$REDIRECT_URI" +
					"&scope=${Uri.encode(SCOPE)}" +
					"&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal" +
					"&duid=$duid&"
		}

		/** PSN user_id (decimal) to the 8-byte little-endian Account ID, like the desktop GUI */
		fun accountIdFromUserId(userId: String): ByteArray
		{
			val id = userId.trim().toULong()
			return ByteArray(8) { i -> (id shr (8 * i)).toByte() }
		}
	}

	class UnauthorizedException(message: String): IOException(message)

	private data class Tokens(val accessToken: String, val refreshToken: String, val expiresAtMs: Long, val accountId: String)

	private val file get() = File(context.noBackupFilesDir, FILE_NAME)
	private val mutex = Mutex()
	@Volatile private var tokens: Tokens? = load()

	val isSignedIn get() = tokens != null

	/** Base64 Account ID of the signed-in account */
	val accountIdBase64 get() = tokens?.accountId

	val accountId: ByteArray? get() = tokens?.accountId?.let { Base64.decode(it, Base64.DEFAULT) }

	/** Exchanges the code from the sign-in redirect, stores the tokens and returns the base64 Account ID */
	suspend fun signIn(code: String): String = withContext(Dispatchers.IO) {
		mutex.withLock {
			val token = tokenRequest("grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8"))
			val accessToken = token.getString("access_token")
			val userId = request("$TOKEN_URL/$accessToken", null).getString("user_id")
			val accountId = Base64.encodeToString(accountIdFromUserId(userId), Base64.NO_WRAP)
			save(Tokens(accessToken, token.getString("refresh_token"), expiresAt(token), accountId))
			accountId
		}
	}

	fun signOut()
	{
		tokens = null
		file.delete()
	}

	/** A valid access token, refreshed if needed. Throws UnauthorizedException when sign-in is needed again. */
	suspend fun accessToken(): String = withContext(Dispatchers.IO) {
		mutex.withLock {
			val current = tokens ?: throw UnauthorizedException("Not signed in to PSN")
			if(System.currentTimeMillis() + EXPIRY_MARGIN_MS < current.expiresAtMs)
				return@withLock current.accessToken
			val token = try
			{
				tokenRequest("grant_type=refresh_token&refresh_token=" + URLEncoder.encode(current.refreshToken, "UTF-8"))
			}
			catch(e: UnauthorizedException)
			{
				signOut()
				throw e
			}
			// The refresh token rotates, always keep the new one.
			val refreshed = Tokens(token.getString("access_token"), token.optString("refresh_token", current.refreshToken), expiresAt(token), current.accountId)
			save(refreshed)
			refreshed.accessToken
		}
	}

	/** PS5 consoles of the account with Remote Play enabled */
	suspend fun listConsoles(): List<PsnDevice> = withContext(Dispatchers.IO) {
		val token = accessToken()
		ensureCaBundle()
		// Keep the log only on failure, as a session log the user can share from Settings.
		val tmpLog = File(context.cacheDir, "psn_list_devices.log")
		val devices = psnListDevices(token, tmpLog.absolutePath)
		if(devices == null)
		{
			try { tmpLog.copyTo(LogManager(context).createNewFile().file, overwrite = true) }
			catch(e: IOException) { }
			throw IOException("Could not get the console list from PSN (details in Settings → Session Logs)")
		}
		tmpLog.delete()
		devices.filter { it.remotePlayEnabled }
	}

	/**
	 * libcurl in chiaki-lib has no default trust store on Android. Build a PEM bundle
	 * from the system CA certificates (the hashed files are PEM) and pass it to the library.
	 */
	fun ensureCaBundle()
	{
		val bundle = File(context.noBackupFilesDir, CA_FILE_NAME)
		synchronized(this) {
			if(!bundle.exists())
			{
				val dir = CA_DIRS.map { File(it) }.firstOrNull { it.isDirectory && !it.listFiles().isNullOrEmpty() }
					?: throw IOException("No system CA certificates found")
				val tmp = File(bundle.path + ".tmp")
				tmp.bufferedWriter().use { out ->
					dir.listFiles()!!.sortedBy { it.name }.forEach { cert ->
						try
						{
							val text = cert.readText()
							val start = text.indexOf("-----BEGIN CERTIFICATE-----")
							val end = text.indexOf("-----END CERTIFICATE-----")
							if(start >= 0 && end > start)
								out.write(text.substring(start, end + "-----END CERTIFICATE-----".length) + "\n")
						}
						catch(e: IOException) { }
					}
				}
				tmp.renameTo(bundle)
			}
			setPsnCaInfo(bundle.absolutePath)
		}
	}

	private fun expiresAt(token: JSONObject) = System.currentTimeMillis() + token.optLong("expires_in", 3600) * 1000

	private fun tokenRequest(grant: String) = request(TOKEN_URL, grant +
			"&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
			"&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8"))

	private fun request(url: String, formBody: String?): JSONObject
	{
		val connection = URL(url).openConnection() as HttpURLConnection
		try
		{
			connection.connectTimeout = 15000
			connection.readTimeout = 15000
			val credentials = Base64.encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray(), Base64.NO_WRAP)
			connection.setRequestProperty("Authorization", "Basic $credentials")
			if(formBody != null)
			{
				connection.requestMethod = "POST"
				connection.doOutput = true
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
				connection.outputStream.use { it.write(formBody.toByteArray()) }
			}
			else
				connection.setRequestProperty("Accept", "application/json")
			val status = connection.responseCode
			val stream = if(status in 200..299) connection.inputStream else connection.errorStream
			val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
			if(status == 400 || status == 401)
				throw UnauthorizedException("PSN sign-in expired (HTTP $status), please sign in again")
			if(status !in 200..299)
				throw IOException("PSN returned HTTP $status: ${text.take(200)}")
			return JSONObject(text)
		}
		finally
		{
			connection.disconnect()
		}
	}

	private fun load(): Tokens? = try
	{
		if(!file.exists()) null else JSONObject(file.readText()).let {
			Tokens(it.getString("access_token"), it.getString("refresh_token"), it.getLong("expires_at_ms"), it.getString("account_id"))
		}
	}
	catch(e: Exception)
	{
		Log.w(TAG, "Could not read stored PSN account", e)
		null
	}

	private fun save(tokens: Tokens)
	{
		val json = JSONObject()
			.put("access_token", tokens.accessToken)
			.put("refresh_token", tokens.refreshToken)
			.put("expires_at_ms", tokens.expiresAtMs)
			.put("account_id", tokens.accountId)
		val tmp = File(file.path + ".tmp")
		tmp.writeText(json.toString())
		tmp.renameTo(file)
		this.tokens = tokens
	}
}
