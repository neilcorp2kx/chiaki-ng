// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.content.Context
import androidx.annotation.StringRes
import com.metallic.chiaki.R

/**
 * Groups the session's status messages (StreamSession.status) into a few connection steps.
 * The status text comes from the native session (chiaki-jni.c) and StreamActivity.
 */
class ConnectProgress(context: Context, val psn: Boolean)
{
	class Step(@StringRes val active: Int, @StringRes val done: Int)

	val steps = if(psn)
		listOf(
			Step(R.string.connect_step_psn_sign_in, R.string.connect_step_psn_sign_in_done),
			Step(R.string.connect_step_psn_session, R.string.connect_step_psn_session_done),
			Step(R.string.connect_step_psn_ask, R.string.connect_step_psn_ask_done),
			Step(R.string.connect_step_psn_connect, R.string.connect_step_psn_connect_done),
			Step(R.string.connect_step_picture, R.string.connect_step_picture_done))
	else
		listOf(
			Step(R.string.connect_step_lan_find, R.string.connect_step_lan_find_done),
			Step(R.string.connect_step_lan_session, R.string.connect_step_lan_session_done),
			Step(R.string.connect_step_picture, R.string.connect_step_picture_done))

	/** Start of a status message, its step, and whether the message adds detail to the step's own name */
	private class Rule(val prefix: String, val step: Int, val detail: Boolean)

	private val rules = if(psn)
		listOf(
			Rule(context.getString(R.string.status_connecting_psn).trimEnd('…'), 0, false),
			Rule("Checking the network", 1, true),
			Rule("Creating a PSN session", 1, true),
			Rule("Asking the console", 2, false),
			Rule("Connecting to the console through PSN", 3, false),
			Rule("Registering", 3, true),
			Rule("Registered", 3, true),
			Rule("Requesting a Remote Play session", 3, true),
			Rule("The console accepted", 3, true),
			Rule("Opening the data connection", 3, true),
			Rule("Measuring the network", 4, true),
			Rule("Starting the video stream", 4, true),
			Rule("Waiting for the first picture", 4, true))
	else
		listOf(
			Rule(context.getString(R.string.status_connecting_lan).trimEnd('…'), 0, false),
			Rule("Registering", 1, true),
			Rule("Registered", 1, true),
			Rule("Requesting a Remote Play session", 1, true),
			Rule("The console accepted", 1, true),
			Rule("Measuring the network", 2, true),
			Rule("Starting the video stream", 2, true),
			Rule("Waiting for the first picture", 2, true))

	var current = 0
		private set

	/** More about the current step, if the status says more than the step's name */
	var detail: String? = null
		private set

	fun reset()
	{
		current = 0
		detail = null
	}

	fun update(status: String?)
	{
		if(status.isNullOrBlank())
			return
		val lines = status.lines().map { it.trim() }.filter { it.isNotEmpty() }
		val rule = rules.firstOrNull { status.startsWith(it.prefix) }
		if(rule == null)
		{
			// For example the PSN retry message: same step, say what is happening
			detail = lines.joinToString(" ")
			return
		}
		current = rule.step
		detail = if(rule.detail) lines.joinToString(" ") else lines.drop(1).joinToString(" ").ifEmpty { null }
	}

	/** 0 to 1, the current step counted as half done */
	val fraction get() = (current + 0.5f) / steps.size
}
