// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DiscoveredDisplayHost
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.common.MacAddress
import com.metallic.chiaki.common.ManualDisplayHost
import com.metallic.chiaki.common.ManualHost
import com.metallic.chiaki.common.PsnDisplayHost
import com.metallic.chiaki.common.RegisteredHost
import com.metallic.chiaki.common.ext.padForSystemBars
import com.metallic.chiaki.databinding.StreamOverlayCardsBinding
import com.metallic.chiaki.lib.DiscoveryHost
import com.metallic.chiaki.lib.PsnDevice
import com.metallic.chiaki.lib.Target
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.metallic.chiaki.common.getDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.stream.StreamActivity
import com.metallic.chiaki.settings.SettingsActivity
import com.metallic.chiaki.regist.RegistActivity
import com.metallic.chiaki.manualconsole.EditManualConsoleActivity

/**
 * Debug builds only: the looks' screens with sample consoles, for screenshots without a console.
 * Extras: look (status_light, console_home), screen (home, empty, connect, stream, realstream, settings [category], regist, manual),
 * psn (connect over PSN, default true), step (freeze the connect screen at this status), controller.
 */
class ThemePreviewActivity: AppCompatActivity()
{
	private val handler = Handler(Looper.getMainLooper())
	private var home: HomeScreen? = null

	override fun onCreate(savedInstanceState: Bundle?)
	{
		val style = AppStyle.fromValue(intent.getStringExtra("look") ?: AppStyle.CONSOLE_HOME.value)
			.let { if(it.isClassic) AppStyle.CONSOLE_HOME else it }
		val screen = intent.getStringExtra("screen") ?: "home"
		if(screen == "keystest")
		{
			super.onCreate(savedInstanceState)
			runKeysTest()
			return
		}
		if(screen == "settings" || screen == "regist" || screen == "manual")
		{
			// The real screens, in the look stored in the preferences
			super.onCreate(savedInstanceState)
			startActivity(when(screen)
			{
				"settings" -> Intent(this, SettingsActivity::class.java).also { i ->
					intent.getStringExtra("category")?.let { i.putExtra(SettingsActivity.EXTRA_CATEGORY, it) }
				}
				"regist" -> Intent(this, RegistActivity::class.java)
				else -> Intent(this, EditManualConsoleActivity::class.java)
			})
			finish()
			return
		}
		if(screen == "realstream")
		{
			// The real stream screen, connecting to an unreachable test address (RFC 5737)
			super.onCreate(savedInstanceState)
			val info = ConnectInfo(true, "192.0.2.1", ByteArray(0x10), ByteArray(0x10), Preferences(this).videoProfile)
			startActivity(Intent(this, StreamActivity::class.java)
				.putExtra(StreamActivity.EXTRA_CONNECT_INFO, info)
				.putExtra(StreamActivity.EXTRA_CONSOLE_NAME, "Test console")
				.putExtra(StreamActivity.EXTRA_DEBUG_PRETEND_CONNECTED, intent.getBooleanExtra("pretend", false)))
			finish()
			return
		}
		setTheme(if(screen == "connect" || screen == "stream") style.streamTheme else style.mainTheme)
		super.onCreate(savedInstanceState)
		when(screen)
		{
			"connect" -> showConnect(style)
			"stream" -> showStream(style)
			else -> showHome(style, if(screen == "empty") emptyList() else sampleHosts())
		}
	}

	private fun showHome(style: AppStyle, hosts: List<DisplayHost>)
	{
		val toast = { text: String -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
		val home = HomeScreen.create(style, this, HomeActions(
			play = { toast("Play ${it.name}") },
			options = { host, _ -> toast("Options for ${host.name}") },
			addConsole = { toast("Add a console") },
			toggleDiscovery = { toast("Find on network") },
			openSettings = { toast("Settings") },
			openStreamSettings = { toast("Stream settings") },
			registersOverPsn = { it.name == "Bedroom PS5" }))
		this.home = home
		setContentView(home.root)
		home.contentView.padForSystemBars()
		home.setHosts(hosts)
		home.setDiscoveryActive(true)
		home.setPsnSignedIn(true)
		home.setControllerConnected(intent.getBooleanExtra("controller", false))
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
		home?.onKeyDown(keyCode) == true || super.onKeyDown(keyCode, event)

	private fun showConnect(style: AppStyle)
	{
		val container = FrameLayout(this)
		val screen = ConnectScreen.create(style, this, container) ?: return
		container.addView(screen.root)
		setContentView(container)
		val psn = intent.getBooleanExtra("psn", true)
		val progress = ConnectProgress(this, psn)
		val statuses = if(psn)
			listOf(
				getString(R.string.status_connecting_psn),
				"Checking the network…",
				"Creating a PSN session…",
				"Asking the console to start Remote Play through PSN…\nThis can take up to 30 seconds if it is in rest mode.",
				"Connecting to the console through PSN…",
				"Requesting a Remote Play session…",
				"Opening the data connection through PSN…",
				"Measuring the network…",
				"Waiting for the first picture…")
		else
			listOf(
				getString(R.string.status_connecting_lan),
				"Requesting a Remote Play session…",
				"The console accepted the connection…",
				"Measuring the network…",
				"Waiting for the first picture…")
		val name = if(psn) "PS5-962" else "Living room PS5"
		val step = intent.getIntExtra("step", -1)
		if(step >= 0)
		{
			statuses.take(step + 1).forEach { progress.update(it) }
			screen.show(name, progress)
			return
		}
		var i = 0
		val tick = object: Runnable
		{
			override fun run()
			{
				if(i == 0)
					progress.reset()
				progress.update(statuses[i])
				screen.show(name, progress)
				i = (i + 1) % statuses.size
				handler.postDelayed(this, 1800)
			}
		}
		handler.post(tick)
	}

	private fun showStream(style: AppStyle)
	{
		val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
		val picture = FrameLayout(this).apply {
			background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2B4A6F.toInt(), 0xFF6A8CA8.toInt(), 0xFFC9B48A.toInt()))
		}
		val edge = LiveEdgeView(this).apply { this.style = style }
		picture.addView(edge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (2 * resources.displayMetrics.density).toInt(), Gravity.TOP))
		root.addView(picture, FrameLayout.LayoutParams(0, 0, Gravity.CENTER))
		root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			val w = v.width
			val h = (w * 9f / 16f).toInt().coerceAtMost(v.height)
			val lp = picture.layoutParams
			if(lp.width != w || lp.height != h)
				v.post { lp.width = w; lp.height = h; picture.layoutParams = lp }
		}
		val stats = TextView(this)
		root.addView(stats, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
		StreamLook.styleStats(style, stats)
		stats.text = StreamLook.formatStats(style, 1080, 60.0, 10.7, 0.0, 78.0, true, "GameSir-X5 Lite · 0 motor(s)", null,
			getString(R.string.stream_stats_route_psn), getString(R.string.stream_stats_route_lan))
		val overlay = StreamOverlayCardsBinding.inflate(layoutInflater, root, false)
		root.addView(overlay.root)
		setContentView(root)
		val cards = (0 until overlay.cardsLayout.childCount).map { overlay.cardsLayout.getChildAt(it) }
		if(style == AppStyle.CONSOLE_HOME)
			handler.postDelayed({ StreamLook.animateCardsIn(cards) }, 400)
		edge.struggling = intent.getBooleanExtra("struggling", false)
		overlay.root.visibility = View.VISIBLE
	}

	/**
	 * Remote Play keys at rest: a console stored the old way (plain keys) is sealed by
	 * sealPlainKeys and still opens to the same keys; new consoles are stored sealed.
	 */
	private fun runKeysTest()
	{
		val text = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; setPadding(48, 96, 48, 48) }
		setContentView(text)
		lifecycleScope.launch(Dispatchers.IO) {
			val result = try
			{
				val dao = getDatabase(this@ThemePreviewActivity).registeredHostDao()
				val mac = MacAddress(0x00ABCDEF0123L)
				dao.deleteByMac(mac)
				val plainRegist = ByteArray(16) { it.toByte() }
				val plainKey = ByteArray(16) { (it + 100).toByte() }
				val host = RegisteredHost(target = Target.PS5_1, apSsid = null, apBssid = null, apKey = null, apName = null,
					serverMac = mac, serverNickname = "Key test", rpRegistKey = plainRegist, rpKeyType = 0, rpKey = plainKey)
				dao.insertSealed(host) // as older versions stored it
				val legacy = dao.getByMacSealed(mac)!!
				dao.sealPlainKeys()
				val sealed = dao.getByMacSealed(mac)!!
				val opened = dao.getByMac(mac)!!
				val listed = dao.getAll().first().first { it.serverMac == mac }
				dao.deleteByMac(mac)
				dao.insert(host)
				val newSealed = dao.getByMacSealed(mac)!!
				val newOpened = dao.getByMac(mac)!!
				dao.deleteByMac(mac)
				val checks = listOf(
					"legacy row is plain" to (legacy.rpRegistKey.size == 16 && legacy.rpKey.size == 16),
					"upgrade sealed both keys" to (sealed.rpRegistKey.size > 16 && sealed.rpKey.size > 16 &&
						!sealed.rpRegistKey.contentEquals(plainRegist)),
					"sealed keys open to the same keys" to (opened.rpRegistKey.contentEquals(plainRegist) && opened.rpKey.contentEquals(plainKey)),
					"list gives plain keys" to (listed.rpRegistKey.contentEquals(plainRegist) && listed.rpKey.contentEquals(plainKey)),
					"new console stored sealed" to (newSealed.rpRegistKey.size > 16 && newSealed.rpKey.size > 16),
					"new console opens" to (newOpened.rpRegistKey.contentEquals(plainRegist) && newOpened.rpKey.contentEquals(plainKey)))
				checks.joinToString("\n") { (name, ok) -> "${if(ok) "PASS" else "FAIL"} $name" } +
					"\nKEYS TEST ${if(checks.all { it.second }) "PASSED" else "FAILED"}"
			}
			catch(e: Exception)
			{
				"KEYS TEST FAILED: $e"
			}
			Log.i("KeysTest", result.replace("\n", " | "))
			withContext(Dispatchers.Main) { text.text = result }
		}
	}

	override fun onDestroy()
	{
		super.onDestroy()
		handler.removeCallbacksAndMessages(null)
	}

	private fun registered(name: String, mac: Long) = RegisteredHost(
		target = Target.PS5_1, apSsid = null, apBssid = null, apKey = null, apName = null,
		serverMac = MacAddress(mac), serverNickname = name,
		rpRegistKey = ByteArray(0x10), rpKeyType = 0, rpKey = ByteArray(0x10))

	private fun discovered(state: DiscoveryHost.State, addr: String, name: String, id: String, app: String? = null) = DiscoveryHost(
		state = state, hostRequestPort = 997.toUShort(), hostAddr = addr, systemVersion = "09000000",
		deviceDiscoveryProtocolVersion = "00030010", hostName = name, hostType = "PS5", hostId = id,
		runningAppTitleid = app?.let { "PPSA01325" }, runningAppName = app)

	private fun sampleHosts(): List<DisplayHost> = listOf(
		PsnDisplayHost(registered("PS5-962", 0x0011223344L), PsnDevice("PS5-962", "0000000700410080", true, true, true)),
		DiscoveredDisplayHost(registered("Living room PS5", 0x0055667788L), discovered(DiscoveryHost.State.STANDBY, "192.168.0.89", "Living room PS5", "AABBCCDDEEFF")),
		DiscoveredDisplayHost(null, discovered(DiscoveryHost.State.READY, "192.168.0.120", "Bedroom PS5", "112233445566", "Astro Bot")),
		ManualDisplayHost(null, ManualHost(id = 1, host = "ps5.example.net", registeredHost = null)))
}
