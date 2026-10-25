// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.util.Log
import com.metallic.chiaki.lib.QuitReason

/**
 * Reconnects by itself when a stream that was playing drops, and right away when the phone moves
 * to another network (Wi-Fi to mobile data and back), instead of waiting for the old connection
 * to time out. Not when the console ended the session on purpose. A few attempts with growing
 * pauses; without any network it waits for one.
 */
class AutoReconnect(
	context: Context,
	private val handler: Handler,
	/** The stream is playing now */
	private val isPlaying: () -> Boolean,
	/** Connect again; networkChanged: the phone moved to another network */
	private val reconnect: (attempt: Int, maxAttempts: Int, networkChanged: Boolean) -> Unit,
	/** No network right now; called once while waiting for one */
	private val waitForNetwork: () -> Unit,
	/** No network came back in time */
	private val gaveUp: () -> Unit)
{
	companion object
	{
		private const val TAG = "AutoReconnect"
		const val MAX_ATTEMPTS = 4
		private val DELAYS_MS = longArrayOf(1000, 3000, 6000, 10000)
		private const val NETWORK_WAIT_MS = 60_000L

		// ChiakiQuitReason values where the console ended the session on purpose, or trying again cannot help
		private val NO_RETRY = setOf(
			4,  // SESSION_REQUEST_RP_IN_USE: another device is playing
			5,  // SESSION_REQUEST_RP_CRASH
			6,  // SESSION_REQUEST_RP_VERSION_MISMATCH
			11, // STREAM_CONNECTION_REMOTE_DISCONNECTED
			12, // STREAM_CONNECTION_REMOTE_SHUTDOWN
			13) // PSN_REGIST_FAILED
	}

	private val connectivity = context.getSystemService(ConnectivityManager::class.java)
	private var defaultNetwork: Network? = null
	private var playingNetwork: Network? = null
	private var everPlayed = false
	private var attempt = 0
	private var networkChanged = false
	private var waitingForNetwork = false

	/** Reconnecting now: the connecting screen shows, not an error */
	var active = false
		private set

	private val attemptRunnable = Runnable { attemptNow() }
	private val giveUpRunnable = Runnable {
		if(active && waitingForNetwork)
		{
			cancel()
			gaveUp()
		}
	}

	private val callback = object: ConnectivityManager.NetworkCallback()
	{
		override fun onAvailable(network: Network) { handler.post { defaultNetworkChanged(network) } }
		override fun onLost(network: Network) { handler.post { if(defaultNetwork == network) defaultNetwork = null } }
	}

	fun start()
	{
		try { connectivity?.registerDefaultNetworkCallback(callback) }
		catch(e: Exception) { Log.w(TAG, "Could not watch the network", e) }
	}

	fun stop()
	{
		cancel()
		try { connectivity?.unregisterNetworkCallback(callback) } catch(_: Exception) {}
	}

	/** The user left or paused the stream */
	fun cancel()
	{
		handler.removeCallbacks(attemptRunnable)
		handler.removeCallbacks(giveUpRunnable)
		active = false
		attempt = 0
		waitingForNetwork = false
	}

	fun onPlaying()
	{
		everPlayed = true
		active = false
		attempt = 0
		waitingForNetwork = false
		playingNetwork = defaultNetwork
	}

	/** The stream ended with this reason. True if reconnecting, so no error to show. */
	fun onQuit(reason: QuitReason): Boolean
	{
		if(!everPlayed || !reason.isError || reason.value in NO_RETRY || attempt >= MAX_ATTEMPTS)
		{
			cancel()
			return false
		}
		if(!active)
			networkChanged = false
		active = true
		handler.removeCallbacks(attemptRunnable)
		handler.postDelayed(attemptRunnable, DELAYS_MS[attempt.coerceAtMost(DELAYS_MS.size - 1)])
		return true
	}

	private fun defaultNetworkChanged(network: Network)
	{
		Log.i(TAG, "Default network: $network (was $defaultNetwork, playing on $playingNetwork)")
		defaultNetwork = network
		// Playing started before the first network report: this is the one it plays on.
		if(isPlaying() && playingNetwork == null)
		{
			playingNetwork = network
			return
		}
		if(active && waitingForNetwork)
		{
			attemptNow()
			return
		}
		// The connection belongs to the old network; it would only time out.
		if(!active && isPlaying() && playingNetwork != null && network != playingNetwork)
		{
			Log.i(TAG, "Default network changed while playing, reconnecting")
			active = true
			attempt = 0
			networkChanged = true
			handler.removeCallbacks(attemptRunnable)
			handler.post(attemptRunnable)
		}
	}

	private fun attemptNow()
	{
		if(!active)
			return
		if(defaultNetwork == null)
		{
			if(!waitingForNetwork)
			{
				waitingForNetwork = true
				waitForNetwork()
				handler.postDelayed(giveUpRunnable, NETWORK_WAIT_MS)
			}
			return
		}
		waitingForNetwork = false
		handler.removeCallbacks(giveUpRunnable)
		attempt++
		reconnect(attempt, MAX_ATTEMPTS, networkChanged)
	}
}
