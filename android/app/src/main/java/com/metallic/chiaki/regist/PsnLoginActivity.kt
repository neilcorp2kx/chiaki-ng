// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.regist

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Signs in to PSN in a WebView and returns the PSN Account ID (8 bytes, base64),
 * so it does not have to be looked up by hand. Same flow as the desktop GUI
 * (gui/src/psnaccountid.cpp). The tokens are only used to read the account ID
 * and are not stored.
 */
class PsnLoginActivity: AppCompatActivity()
{
	companion object
	{
		const val EXTRA_ACCOUNT_ID = "psn_account_id"

		private const val TAG = "PsnLogin"

		// Values from gui/include/psnaccountid.h
		private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
		private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
		private const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"
		private const val SCOPE = "psn:clientapp referenceDataService:countryConfig.read pushNotification:webSocket.desktop.connect sessionManager:remotePlaySession.system.update"
		private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
		private val LOGIN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
				"?service_entity=urn:service-entity:psn&response_type=code" +
				"&client_id=$CLIENT_ID" +
				"&redirect_uri=$REDIRECT_URI" +
				"&scope=${Uri.encode(SCOPE)}" +
				"&request_locale=en_US&ui=pr&service_logo=ps&layout_type=popup&smcid=remoteplay&prompt=always&PlatformPrivacyWs1=minimal&"
	}

	private lateinit var webView: WebView
	private lateinit var progressBar: ProgressBar
	private var codeHandled = false

	@SuppressLint("SetJavaScriptEnabled")
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		title = getString(R.string.title_psn_login)

		clearWebSession()

		webView = WebView(this)
		webView.settings.javaScriptEnabled = true
		webView.settings.domStorageEnabled = true
		// Some sign-in pages refuse embedded browsers that announce themselves as a WebView.
		webView.settings.userAgentString = webView.settings.userAgentString.replace("; wv", "")
		webView.webViewClient = object: WebViewClient() {
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
				handleUrl(request.url)

			override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?)
			{
				if(!handleUrl(Uri.parse(url)))
					progressBar.visibility = View.VISIBLE
			}

			override fun onPageFinished(view: WebView, url: String)
			{
				Log.i(TAG, "Loaded ${Uri.parse(url).host}${Uri.parse(url).path}")
				if(!codeHandled)
					progressBar.visibility = View.GONE
			}

			override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError)
			{
				if(request.isForMainFrame)
					Log.e(TAG, "Load error ${error.errorCode} ${error.description} for ${request.url.host}")
			}

			override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse)
			{
				if(request.isForMainFrame)
					Log.e(TAG, "HTTP ${response.statusCode} for ${request.url.host}${request.url.path}")
			}
		}
		webView.webChromeClient = object: WebChromeClient() {
			override fun onConsoleMessage(message: ConsoleMessage): Boolean
			{
				if(message.messageLevel() == ConsoleMessage.MessageLevel.ERROR)
					Log.w(TAG, "Page error: ${message.message().take(200)}")
				return true
			}
		}

		progressBar = ProgressBar(this).also { it.isIndeterminate = true }
		val root = FrameLayout(this)
		root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
		root.addView(progressBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
		setContentView(root)

		webView.loadUrl(LOGIN_URL)
	}

	override fun onDestroy()
	{
		webView.destroy()
		clearWebSession()
		super.onDestroy()
	}

	/** Returns true when the URL is the sign-in redirect and was taken over */
	private fun handleUrl(url: Uri): Boolean
	{
		if(!url.toString().startsWith(REDIRECT_URI))
			return false
		if(codeHandled)
			return true
		codeHandled = true
		webView.stopLoading()
		webView.visibility = View.INVISIBLE
		progressBar.visibility = View.VISIBLE

		val code = url.getQueryParameter("code")
		if(code.isNullOrEmpty())
		{
			showError(url.getQueryParameter("error_description") ?: url.getQueryParameter("error") ?: getString(R.string.psn_login_error_no_code))
			return true
		}

		lifecycleScope.launch {
			try
			{
				val accountId = withContext(Dispatchers.IO) { fetchAccountId(code) }
				setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCOUNT_ID, accountId))
				finish()
			}
			catch(e: Exception)
			{
				Log.e(TAG, "Failed to get PSN Account ID", e)
				showError(e.message ?: e.toString())
			}
		}
		return true
	}

	private fun fetchAccountId(code: String): String
	{
		val body = "grant_type=authorization_code" +
				"&code=" + URLEncoder.encode(code, "UTF-8") +
				"&scope=" + URLEncoder.encode(SCOPE, "UTF-8") +
				"&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, "UTF-8")
		val token = request(TOKEN_URL, body).getString("access_token")
		val userId = request("$TOKEN_URL/$token", null).getString("user_id")
		return accountIdFromUserId(userId)
	}

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
			if(status !in 200..299)
				throw IOException(getString(R.string.psn_login_error_http, status, text.take(200)))
			return JSONObject(text)
		}
		finally
		{
			connection.disconnect()
		}
	}

	private fun showError(message: String)
	{
		if(isFinishing)
			return
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.psn_login_error_title)
			.setMessage(message)
			.setPositiveButton(android.R.string.ok) { _, _ -> finish() }
			.setOnCancelListener { finish() }
			.show()
	}

	private fun clearWebSession()
	{
		CookieManager.getInstance().removeAllCookies(null)
		WebStorage.getInstance().deleteAllData()
	}
}

/** PSN user_id (decimal) to the 8-byte little-endian Account ID in base64, like the desktop GUI */
fun accountIdFromUserId(userId: String): String
{
	val id = userId.trim().toULong()
	val bytes = ByteArray(8) { i -> (id shr (8 * i)).toByte() }
	return Base64.encodeToString(bytes, Base64.NO_WRAP)
}
