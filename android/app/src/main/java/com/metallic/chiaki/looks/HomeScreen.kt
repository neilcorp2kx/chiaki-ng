// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import com.metallic.chiaki.common.DisplayHost

/** What the consoles screen can ask MainActivity to do. The same actions as the Classic look. */
class HomeActions(
	val play: (DisplayHost) -> Unit,
	val options: (DisplayHost, View) -> Unit,
	val addConsole: (View) -> Unit,
	val toggleDiscovery: () -> Unit,
	val openSettings: () -> Unit,
	/** The console is not registered, but on the signed-in PSN account, so it registers without a PIN */
	val registersOverPsn: (DisplayHost) -> Boolean)

/** The consoles screen of a look other than Classic */
abstract class HomeScreen(protected val activity: Activity, protected val actions: HomeActions)
{
	abstract val root: View

	/** The view to pad for the system bars. The rest may draw behind them. */
	abstract val contentView: View

	abstract fun setHosts(hosts: List<DisplayHost>)
	abstract fun setDiscoveryActive(active: Boolean)
	abstract fun setPsnSignedIn(signedIn: Boolean)
	abstract fun setControllerConnected(connected: Boolean)

	/** Controller buttons beyond confirm and back */
	abstract fun onKeyDown(keyCode: Int): Boolean

	protected fun statusOf(host: DisplayHost) = ConsoleStatus.of(activity, host, actions.registersOverPsn(host))

	companion object
	{
		fun create(style: AppStyle, activity: Activity, actions: HomeActions): HomeScreen = when(style)
		{
			AppStyle.CONSOLE_HOME -> ConsoleHomeScreen(activity, actions)
			else -> StatusLightHomeScreen(activity, actions)
		}

		fun isOptionsKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_BUTTON_Y || keyCode == KeyEvent.KEYCODE_BUTTON_X
		fun isAddKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BUTTON_START
	}
}
