// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.lib.VideoFPSPreset
import com.metallic.chiaki.lib.VideoResolutionPreset
import com.metallic.chiaki.stream.VideoFilterSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

class Preferences(context: Context)
{
	enum class Resolution(val value: String, @StringRes val title: Int, val preset: VideoResolutionPreset)
	{
		RES_360P("360p", R.string.preferences_resolution_title_360p, VideoResolutionPreset.RES_360P),
		RES_540P("540p", R.string.preferences_resolution_title_540p, VideoResolutionPreset.RES_540P),
		RES_720P("720p", R.string.preferences_resolution_title_720p, VideoResolutionPreset.RES_720P),
		RES_1080P("1080p", R.string.preferences_resolution_title_1080p, VideoResolutionPreset.RES_1080P),
	}

	enum class FPS(val value: String, @StringRes val title: Int, val preset: VideoFPSPreset)
	{
		FPS_30("30", R.string.preferences_fps_title_30, VideoFPSPreset.FPS_30),
		FPS_60("60", R.string.preferences_fps_title_60, VideoFPSPreset.FPS_60)
	}

	enum class Codec(val value: String, @StringRes val title: Int, val codec: com.metallic.chiaki.lib.Codec)
	{
		CODEC_H264("h264", R.string.preferences_codec_title_h264, com.metallic.chiaki.lib.Codec.CODEC_H264),
		CODEC_H265("h265", R.string.preferences_codec_title_h265, com.metallic.chiaki.lib.Codec.CODEC_H265)
	}

	enum class Deband(val value: String, @StringRes val title: Int, val iterations: Int, val threshold: Float, val radius: Float, val grain: Float)
	{
		OFF("off", R.string.preferences_video_filter_off, 0, 0f, 0f, 0f),
		LOW("low", R.string.preferences_video_filter_low, 1, 0.004f, 12f, 0.75f),
		MEDIUM("medium", R.string.preferences_video_filter_medium, 2, 0.006f, 16f, 1.0f),
		HIGH("high", R.string.preferences_video_filter_high, 3, 0.009f, 20f, 1.25f)
	}

	enum class Sharpen(val value: String, @StringRes val title: Int, val stops: Float?)
	{
		OFF("off", R.string.preferences_video_filter_off, null),
		LOW("low", R.string.preferences_video_filter_low, 1.0f),
		MEDIUM("medium", R.string.preferences_video_filter_medium, 0.5f),
		HIGH("high", R.string.preferences_video_filter_high, 0.2f)
	}

	/** Strength of haptics turned into rumble, percent of the desktop GUI's "Normal" */
	enum class HapticsRumble(val value: String, @StringRes val title: Int, val percent: Int)
	{
		OFF("off", R.string.preferences_video_filter_off, 0),
		WEAK("weak", R.string.preferences_haptics_rumble_weak, 50),
		NORMAL("normal", R.string.preferences_haptics_rumble_normal, 100),
		STRONG("strong", R.string.preferences_haptics_rumble_strong, 200)
	}

	enum class LeaveAction(val value: String, @StringRes val title: Int)
	{
		ASK("ask", R.string.preferences_leave_action_ask),
		REST("rest", R.string.preferences_leave_action_rest),
		KEEP_ON("keep_on", R.string.preferences_leave_action_keep_on)
	}

	companion object
	{
		val resolutionDefault = Resolution.RES_720P
		val resolutionAll = Resolution.values()
		val fpsDefault = FPS.FPS_60
		val fpsAll = FPS.values()
		val codecDefault = Codec.CODEC_H265
		val codecAll = Codec.values()
		val debandDefault = Deband.OFF
		val debandAll = Deband.values()
		val sharpenDefault = Sharpen.OFF
		val sharpenAll = Sharpen.values()
		val leaveActionDefault = LeaveAction.ASK
		val leaveActionAll = LeaveAction.values()
		val hapticsRumbleDefault = HapticsRumble.NORMAL
		val hapticsRumbleAll = HapticsRumble.values()
	}

	private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
	private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
		when(key)
		{
			resolutionKey -> _bitrateAutoFlow.value = bitrateAuto
			remoteResolutionKey -> _remoteBitrateAutoFlow.value = remoteBitrateAuto
		}
	}.also { sharedPreferences.registerOnSharedPreferenceChangeListener(it) }

	private val resources = context.resources

	val discoveryEnabledKey get() = resources.getString(R.string.preferences_discovery_enabled_key)
	var discoveryEnabled
		get() = sharedPreferences.getBoolean(discoveryEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(discoveryEnabledKey, value).apply() }

	val onScreenControlsEnabledKey get() = resources.getString(R.string.preferences_on_screen_controls_enabled_key)
	var onScreenControlsEnabled
		get() = sharedPreferences.getBoolean(onScreenControlsEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(onScreenControlsEnabledKey, value).apply() }

	val touchpadOnlyEnabledKey get() = resources.getString(R.string.preferences_touchpad_only_enabled_key)
	var touchpadOnlyEnabled
		get() = sharedPreferences.getBoolean(touchpadOnlyEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(touchpadOnlyEnabledKey, value).apply() }

	val rumbleEnabledKey get() = resources.getString(R.string.preferences_rumble_enabled_key)
	var rumbleEnabled
		get() = sharedPreferences.getBoolean(rumbleEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(rumbleEnabledKey, value).apply() }

	val motionEnabledKey get() = resources.getString(R.string.preferences_motion_enabled_key)
	var motionEnabled
		get() = sharedPreferences.getBoolean(motionEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(motionEnabledKey, value).apply() }

	val buttonHapticEnabledKey get() = resources.getString(R.string.preferences_button_haptic_enabled_key)
	var buttonHapticEnabled
		get() = sharedPreferences.getBoolean(buttonHapticEnabledKey, true)
		set(value) { sharedPreferences.edit().putBoolean(buttonHapticEnabledKey, value).apply() }

	val logVerboseKey get() = resources.getString(R.string.preferences_log_verbose_key)
	var logVerbose
		get() = sharedPreferences.getBoolean(logVerboseKey, false)
		set(value) { sharedPreferences.edit().putBoolean(logVerboseKey, value).apply() }

	val swapCrossMoonKey get() = resources.getString(R.string.preferences_swap_cross_moon_key)
	var swapCrossMoon
		get() = sharedPreferences.getBoolean(swapCrossMoonKey, false)
		set(value) { sharedPreferences.edit().putBoolean(swapCrossMoonKey, value).apply() }

	val resolutionKey get() = resources.getString(R.string.preferences_resolution_key)
	var resolution
		get() = sharedPreferences.getString(resolutionKey, resolutionDefault.value)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolutionDefault
		set(value) { sharedPreferences.edit().putString(resolutionKey, value.value).apply() }

	val fpsKey get() = resources.getString(R.string.preferences_fps_key)
	var fps
		get() = sharedPreferences.getString(fpsKey, fpsDefault.value)?.let { value ->
			FPS.values().firstOrNull { it.value == value }
		}  ?: fpsDefault
		set(value) { sharedPreferences.edit().putString(fpsKey, value.value).apply() }

	fun validateBitrate(bitrate: Int) = max(2000, min(100000, bitrate))
	val bitrateKey get() = resources.getString(R.string.preferences_bitrate_key)
	var bitrate
		get() = sharedPreferences.getInt(bitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(bitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val bitrateAuto get() = videoProfileDefaultBitrate.bitrate
	private val _bitrateAutoFlow by lazy { MutableStateFlow(bitrateAuto) }
	val bitrateAutoFlow: StateFlow<Int> get() = _bitrateAutoFlow.asStateFlow()

	val codecKey get() = resources.getString(R.string.preferences_codec_key)
	var codec
		get() = sharedPreferences.getString(codecKey, codecDefault.value)?.let { value ->
			Codec.values().firstOrNull { it.value == value }
		}  ?: codecDefault
		set(value) { sharedPreferences.edit().putString(codecKey, value.value).apply() }

	private val videoProfileDefaultBitrate get() = ConnectVideoProfile.preset(resolution.preset, fps.preset, codec.codec)
	val videoProfile get() = videoProfileDefaultBitrate.let {
		val bitrate = bitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}

	// Remote Play over PSN gets its own resolution and bitrate, like the desktop GUI's
	// local/remote settings: mobile data usually needs less than the home network.
	val remoteResolutionKey get() = resources.getString(R.string.preferences_remote_resolution_key)
	var remoteResolution
		get() = sharedPreferences.getString(remoteResolutionKey, null)?.let { value ->
			Resolution.values().firstOrNull { it.value == value }
		} ?: resolution
		set(value) { sharedPreferences.edit().putString(remoteResolutionKey, value.value).apply() }

	val remoteBitrateKey get() = resources.getString(R.string.preferences_remote_bitrate_key)
	var remoteBitrate
		get() = sharedPreferences.getInt(remoteBitrateKey, 0).let { if(it == 0) null else validateBitrate(it) }
		set(value) { sharedPreferences.edit().putInt(remoteBitrateKey, if(value != null) validateBitrate(value) else 0).apply() }
	val remoteBitrateAuto get() = remoteVideoProfileDefaultBitrate.bitrate
	private val _remoteBitrateAutoFlow by lazy { MutableStateFlow(remoteBitrateAuto) }
	val remoteBitrateAutoFlow: StateFlow<Int> get() = _remoteBitrateAutoFlow.asStateFlow()

	private val remoteVideoProfileDefaultBitrate get() = ConnectVideoProfile.preset(remoteResolution.preset, fps.preset, codec.codec)
	val remoteVideoProfile get() = remoteVideoProfileDefaultBitrate.let {
		val bitrate = remoteBitrate
		if(bitrate == null)
			it
		else
			it.copy(bitrate = bitrate)
	}

	val debandKey get() = resources.getString(R.string.preferences_deband_key)
	var deband
		get() = sharedPreferences.getString(debandKey, debandDefault.value)?.let { value ->
			Deband.values().firstOrNull { it.value == value }
		} ?: debandDefault
		set(value) { sharedPreferences.edit().putString(debandKey, value.value).apply() }

	val sharpenKey get() = resources.getString(R.string.preferences_sharpen_key)
	var sharpen
		get() = sharedPreferences.getString(sharpenKey, sharpenDefault.value)?.let { value ->
			Sharpen.values().firstOrNull { it.value == value }
		} ?: sharpenDefault
		set(value) { sharedPreferences.edit().putString(sharpenKey, value.value).apply() }

	val hapticsRumbleKey get() = resources.getString(R.string.preferences_haptics_rumble_key)
	var hapticsRumble
		get() = sharedPreferences.getString(hapticsRumbleKey, hapticsRumbleDefault.value)?.let { value ->
			HapticsRumble.values().firstOrNull { it.value == value }
		} ?: hapticsRumbleDefault
		set(value) { sharedPreferences.edit().putString(hapticsRumbleKey, value.value).apply() }
	/** For ConnectInfo.hapticsRumblePercent: 0 when rumble is off */
	val hapticsRumblePercent get() = if(rumbleEnabled) hapticsRumble.percent else 0

	val controllerMotionKey get() = resources.getString(R.string.preferences_controller_motion_key)
	var controllerMotion
		get() = sharedPreferences.getBoolean(controllerMotionKey, true)
		set(value) { sharedPreferences.edit().putBoolean(controllerMotionKey, value).apply() }

	val leaveActionKey get() = resources.getString(R.string.preferences_leave_action_key)
	var leaveAction
		get() = sharedPreferences.getString(leaveActionKey, leaveActionDefault.value)?.let { value ->
			LeaveAction.values().firstOrNull { it.value == value }
		} ?: leaveActionDefault
		set(value) { sharedPreferences.edit().putString(leaveActionKey, value.value).apply() }

	val keyboardEnabledKey get() = resources.getString(R.string.preferences_keyboard_enabled_key)
	var keyboardEnabled
		get() = sharedPreferences.getBoolean(keyboardEnabledKey, false)
		set(value) { sharedPreferences.edit().putBoolean(keyboardEnabledKey, value).apply() }

	val videoFilterStatsKey get() = resources.getString(R.string.preferences_video_filter_stats_key)
	var videoFilterStats
		get() = sharedPreferences.getBoolean(videoFilterStatsKey, false)
		set(value) { sharedPreferences.edit().putBoolean(videoFilterStatsKey, value).apply() }

	val videoFilterSettings get() = deband.let {
		VideoFilterSettings(
			debandIterations = it.iterations,
			debandThreshold = it.threshold,
			debandRadius = it.radius,
			debandGrain = it.grain,
			sharpenStops = sharpen.stops,
			showStats = videoFilterStats
		)
	}
}
