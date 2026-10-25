// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.session

import android.content.Context
import android.graphics.Color
import android.hardware.input.InputManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.InputDevice
import kotlin.math.max

/**
 * Rumble and lights for the connected game controller, with the phone as fallback.
 * Uses the per-device vibrators and lights of Android 12+ (InputDevice.getVibratorManager(),
 * InputDevice.getLightsManager()). Older Android or controllers without them use the phone.
 */
class ControllerFeedback(private val context: Context)
{
	companion object
	{
		private const val TAG = "ControllerFeedback"
		private const val RUMBLE_MS = 1000L
	}

	private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
	private var device: InputDevice? = null
	private var lightsSession: LightsManager.LightsSession? = null

	private val deviceListener = object: InputManager.InputDeviceListener {
		override fun onInputDeviceAdded(deviceId: Int) = selectDevice()
		override fun onInputDeviceRemoved(deviceId: Int) = selectDevice()
		override fun onInputDeviceChanged(deviceId: Int) = selectDevice()
	}

	init
	{
		inputManager.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
		selectDevice()
	}

	/** The controller, if one is connected (physical gamepad or joystick) */
	val controller: InputDevice? get() = device

	private fun selectDevice()
	{
		val found = InputDevice.getDeviceIds().toList()
			.mapNotNull { InputDevice.getDevice(it) }
			.firstOrNull { !it.isVirtual && (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD }
		if(found?.id != device?.id)
		{
			lightsSession?.close()
			lightsSession = null
			device = found
			Log.i(TAG, "Controller: ${found?.name ?: "none"}")
		}
	}

	private val controllerVibrators: List<Vibrator> get()
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return emptyList()
		val manager = device?.vibratorManager ?: return emptyList()
		return manager.vibratorIds.map { manager.getVibrator(it) }
	}

	val controllerHasRumble get() = controllerVibrators.isNotEmpty()

	/** left = strong motor, right = weak motor, 0-255 each */
	fun rumble(left: Int, right: Int)
	{
		val vibrators = controllerVibrators
		// The first vibrator is the left (strong) motor, as in Android's controller sample.
		when(vibrators.size)
		{
			0 -> {}
			1 -> { vibrate(vibrators[0], max(left, right)); return }
			else -> { vibrate(vibrators[0], left); vibrate(vibrators[1], right); return }
		}
		@Suppress("DEPRECATION")
		val phone = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
		vibrate(phone, (left + right) / 2)
	}

	private fun vibrate(vibrator: Vibrator, amplitude: Int)
	{
		vibrator.cancel()
		if(amplitude <= 0)
			return
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
			vibrator.vibrate(VibrationEffect.createOneShot(RUMBLE_MS,
				if(vibrator.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE))
		else
			@Suppress("DEPRECATION")
			vibrator.vibrate(RUMBLE_MS)
	}

	fun stopRumble() = rumble(0, 0)

	/** Lightbar color, if the controller has an RGB light */
	fun setLightbar(red: Int, green: Int, blue: Int) = requestLights { light, builder ->
		if(light.type == Light.LIGHT_TYPE_INPUT && light.hasRgbControl())
			builder.addLight(light, LightState.Builder().setColor(Color.rgb(red, green, blue)).build())
	}

	/** Player number LEDs, if the controller has them */
	fun setPlayerIndex(playerIndex: Int) = requestLights { light, builder ->
		if(light.type == Light.LIGHT_TYPE_PLAYER_ID)
			builder.addLight(light, LightState.Builder().setPlayerId(playerIndex).build())
	}

	private fun requestLights(add: (Light, LightsRequest.Builder) -> Unit)
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
			return
		val device = device ?: return
		try
		{
			val manager = device.lightsManager
			val builder = LightsRequest.Builder()
			manager.lights.forEach { add(it, builder) }
			val session = lightsSession ?: manager.openSession().also { lightsSession = it }
			session.requestLights(builder.build())
		}
		catch(e: Exception)
		{
			Log.w(TAG, "Could not set controller lights", e)
		}
	}

	fun close()
	{
		stopRumble()
		lightsSession?.close()
		lightsSession = null
		inputManager.unregisterInputDeviceListener(deviceListener)
	}
}
