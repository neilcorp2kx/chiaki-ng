// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.PsnAccount
import com.metallic.chiaki.lib.psnTlsSelfTest
import kotlin.concurrent.thread

/**
 * Debug tool: adb shell am start -n <package>/com.metallic.chiaki.stream.PsnTlsTestActivity --es host <host>
 * Result in logcat tag PsnTlsTest.
 */
class PsnTlsTestActivity: AppCompatActivity()
{
	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val text = TextView(this)
		setContentView(text)
		val hosts = (intent.getStringExtra("host") ?: "34-215-159-77-pushcl.np.communication.playstation.net,web.np.playstation.com").split(",")
		thread {
			PsnAccount.get(this).ensureCaBundle()
			val results = hosts.map { host -> "$host: " + if(psnTlsSelfTest(host)) "OK" else "FAILED" }
			results.forEach { Log.i("PsnTlsTest", it) }
			runOnUiThread { text.text = results.joinToString("\n") }
		}
	}
}
