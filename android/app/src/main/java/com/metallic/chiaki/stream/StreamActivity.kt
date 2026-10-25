// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.AlertDialog
import android.graphics.Matrix
import android.opengl.GLSurfaceView
import android.os.*
import android.view.*
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences
import com.metallic.chiaki.common.PsnAccount
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import kotlinx.coroutines.launch
import com.metallic.chiaki.common.ext.viewModelFactory
import com.metallic.chiaki.databinding.ActivityStreamBinding
import com.metallic.chiaki.lib.Codec
import com.metallic.chiaki.lib.KeyboardEvent
import com.metallic.chiaki.lib.LedColorEvent
import com.metallic.chiaki.lib.PlayerIndexEvent
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ControllerState
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import android.widget.CompoundButton
import androidx.core.view.children
import com.google.android.material.button.MaterialButtonToggleGroup
import com.metallic.chiaki.databinding.StreamOverlayCardsBinding
import com.metallic.chiaki.looks.AppStyle
import com.metallic.chiaki.looks.ConnectProgress
import com.metallic.chiaki.looks.ConnectScreen
import com.metallic.chiaki.looks.StreamLook
import com.metallic.chiaki.looks.applyAppStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

/** The stream controls: the Classic overlay of activity_stream.xml, or the cards of the other looks */
private class OverlayViews(
	val root: View,
	val onScreenControlsSwitch: CompoundButton,
	val touchpadOnlySwitch: CompoundButton,
	val microphoneSwitch: CompoundButton,
	val displayModeToggle: MaterialButtonToggleGroup,
	val cards: List<View>)

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		/** Console name for the connecting screen, optional */
		const val EXTRA_CONSOLE_NAME = "console_name"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val STATS_INTERVAL_MS = 1000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat
	private lateinit var overlay: OverlayViews
	private var appStyle = AppStyle.CLASSIC
	private var connectScreen: ConnectScreen? = null
	private lateinit var connectProgress: ConnectProgress

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	override fun onCreate(savedInstanceState: Bundle?)
	{
		appStyle = applyAppStyle(stream = true)
		super.onCreate(savedInstanceState)

		val connectInfo = IntentCompat.getParcelableExtra(intent, EXTRA_CONNECT_INFO, ConnectInfo::class.java)
		if(connectInfo == null)
		{
			finish()
			return
		}

		viewModel = ViewModelProvider(this, viewModelFactory {
			StreamViewModel(application, connectInfo)
		})[StreamViewModel::class.java]

		viewModel.input.observe(this)

		binding = ActivityStreamBinding.inflate(layoutInflater)
		setContentView(binding.root)
		overlay = setupOverlay()
		connectProgress = ConnectProgress(this, connectInfo.isPsn)
		connectScreen = ConnectScreen.create(appStyle, this, binding.connectContainer)?.also {
			binding.connectContainer.addView(it.root)
			binding.connectContainer.isVisible = true
			it.show(consoleName, connectProgress)
		}
		if(!appStyle.isClassic)
		{
			StreamLook.styleStats(appStyle, binding.filterStatsTextView)
			binding.liveEdgeView.style = appStyle
		}

		WindowCompat.setDecorFitsSystemWindows(window, false)
		insetsController = WindowCompat.getInsetsController(window, window.decorView)
		insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

		ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
			val systemBars = insets.isVisible(WindowInsetsCompat.Type.systemBars())
			if(systemBars)
				showOverlay()
			else
				hideOverlay()
			insets
		}

		viewModel.onScreenControlsEnabled.observe(this, Observer {
			if(overlay.onScreenControlsSwitch.isChecked != it)
				overlay.onScreenControlsSwitch.isChecked = it
			if(overlay.onScreenControlsSwitch.isChecked)
				overlay.touchpadOnlySwitch.isChecked = false
		})
		overlay.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(overlay.touchpadOnlySwitch.isChecked != it)
				overlay.touchpadOnlySwitch.isChecked = it
			if(overlay.touchpadOnlySwitch.isChecked)
				overlay.onScreenControlsSwitch.isChecked = false
		})
		overlay.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		overlay.microphoneSwitch.setOnCheckedChangeListener { _, isChecked ->
			if(isChecked)
			{
				if(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
					enableMicrophone()
				else
					microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
			}
			else
				viewModel.session.setMicrophone(false)
			showOverlay()
		}

		overlay.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
			adjustStreamViewAspect()
			showOverlay()
		}

		//viewModel.session.attachToTextureView(textureView)
		val videoProfile = connectInfo.videoProfile
		val filterSettings = Preferences(this).videoFilterSettings
		if(filterSettings.enabled && videoProfile.codec != Codec.CODEC_H265_HDR)
			attachFilterRenderer(filterSettings)
		else
			viewModel.session.attachToSurfaceView(binding.surfaceView)
		viewModel.session.state.observe(this, Observer { this.stateChanged(it) })
		viewModel.session.keyboardEvent.observe(this, Observer { it?.let { keyboardEvent(it) } })
		onBackPressedDispatcher.addCallback(this) { leaveStream() }
		adjustStreamViewAspect()

		val feedback = ControllerFeedback(this) { viewModel.setControllerConnected(it != null) }
		viewModel.setControllerConnected(feedback.controller != null)
		viewModel.session.psnRetryStatus = getString(R.string.status_psn_retry)
		viewModel.session.status.observe(this, Observer { status ->
			binding.statusTextView.text = status ?: ""
			connectProgress.update(status)
			connectScreen?.show(consoleName, connectProgress)
		})
		controllerFeedback = feedback
		if(Preferences(this).rumbleEnabled)
		{
			// Rumble on the controller's motors, or the phone's when the controller has none.
			viewModel.session.rumbleState.observe(this, Observer {
				feedback.rumble(it.left.toInt(), it.right.toInt())
			})
		}
		viewModel.session.lightState.observe(this, Observer {
			when(it)
			{
				is LedColorEvent -> feedback.setLightbar(it.red, it.green, it.blue)
				is PlayerIndexEvent -> feedback.setPlayerIndex(it.playerIndex)
				else -> {}
			}
		})
	}

	private var controllerFeedback: ControllerFeedback? = null

	private val consoleName get() = intent.getStringExtra(EXTRA_CONSOLE_NAME)

	private fun setupOverlay(): OverlayViews
	{
		if(appStyle.isClassic)
			return OverlayViews(binding.overlay, binding.onScreenControlsSwitch, binding.touchpadOnlySwitch,
				binding.microphoneSwitch, binding.displayModeToggle, emptyList())
		// Same controls as cards in the band below the picture
		val parent = binding.overlay.parent as ViewGroup
		val index = parent.indexOfChild(binding.overlay)
		parent.removeView(binding.overlay)
		val cards = StreamOverlayCardsBinding.inflate(layoutInflater, parent, false)
		parent.addView(cards.root, index)
		cards.leaveButton.setOnClickListener { leaveStream() }
		return OverlayViews(cards.root, cards.onScreenControlsSwitch, cards.touchpadOnlySwitch,
			cards.microphoneSwitch, cards.displayModeToggle, cards.cardsLayout.children.toList())
	}

	/** The connecting screen is up; it covers the touch controls */
	private var connectScreenShown = true

	private fun showConnectScreen(show: Boolean)
	{
		val container = binding.connectContainer
		if(connectScreen == null || show == connectScreenShown)
			return
		connectScreenShown = show
		container.animate().cancel()
		if(show)
		{
			container.alpha = 1f
			container.isVisible = true
		}
		else
		{
			container.animate().alpha(0f).setDuration(300).start()
			// Gone right after the fade in any case, so it never stays over the touch controls.
			container.postDelayed({ if(!connectScreenShown) container.isGone = true }, 320)
		}
	}

	private var filterRenderer: VideoFilterRenderer? = null

	private fun attachFilterRenderer(settings: VideoFilterSettings)
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		val view = binding.filterSurfaceView
		binding.surfaceView.isGone = true
		view.isVisible = true
		view.setEGLContextClientVersion(3)
		view.preserveEGLContextOnPause = true
		// Render at the view size and scale in the shader. A fixed stream-size buffer relies on the
		// compositor to scale it, but EGL surfaces keep the "freeze" scaling mode on some devices
		// (seen on a Galaxy Z Fold7), which shows the buffer unscaled in a corner.
		val renderer = VideoFilterRenderer(view, videoProfile.width, videoProfile.height, settings,
			onSurfaceChanged = { surface, releaseOld ->
				uiVisibilityHandler.post {
					viewModel.session.setVideoSurface(surface)
					releaseOld()
				}
			},
			onStats = { stats ->
				uiVisibilityHandler.post { filterStats = stats }
			})
		view.setRenderer(renderer)
		view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
		filterRenderer = renderer
	}

	private val controlsJobs = mutableMapOf<Int, Job>()
	private val touchStates = mutableMapOf<Int, ControllerState>()

	private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if(granted)
			enableMicrophone()
		else
		{
			overlay.microphoneSwitch.isChecked = false
			Toast.makeText(this, R.string.microphone_permission_denied, Toast.LENGTH_LONG).show()
		}
	}

	private fun enableMicrophone()
	{
		if(!viewModel.session.setMicrophone(true))
		{
			overlay.microphoneSwitch.isChecked = false
			Toast.makeText(this, R.string.microphone_failed, Toast.LENGTH_LONG).show()
		}
	}

	private fun leaveStream()
	{
		if(viewModel.session.state.value != StreamStateConnected)
		{
			finish()
			return
		}
		when(Preferences(this).leaveAction)
		{
			Preferences.LeaveAction.REST -> restAndFinish()
			Preferences.LeaveAction.KEEP_ON -> finish()
			Preferences.LeaveAction.ASK -> MaterialAlertDialogBuilder(this)
				.setTitle(R.string.leave_stream_title)
				.setPositiveButton(R.string.action_leave_rest) { _, _ -> restAndFinish() }
				.setNegativeButton(R.string.action_leave_keep_on) { _, _ -> finish() }
				.setNeutralButton(android.R.string.cancel) { _, _ -> }
				.show()
		}
	}

	private fun restAndFinish()
	{
		viewModel.session.gotoBed()
		// Give the control message a moment to go out before the session stops.
		uiVisibilityHandler.postDelayed({ finish() }, 500)
	}

	private var keyboardDialog: androidx.appcompat.app.AlertDialog? = null

	private fun keyboardEvent(event: KeyboardEvent)
	{
		when(event.type)
		{
			KeyboardEvent.Type.OPEN ->
			{
				keyboardDialog?.dismiss()
				val editText = EditText(this).also {
					it.setText(event.text ?: "")
					it.setSelection(it.text.length)
					it.isSingleLine = true
				}
				keyboardDialog = MaterialAlertDialogBuilder(this)
					.setTitle(R.string.keyboard_dialog_title)
					.setView(editText)
					.setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.session.keyboardSetText(editText.text.toString())
						viewModel.session.keyboardAccept()
					}
					.setNegativeButton(android.R.string.cancel) { _, _ -> viewModel.session.keyboardReject() }
					.setOnCancelListener { viewModel.session.keyboardReject() }
					.show()
				editText.requestFocus()
			}
			KeyboardEvent.Type.REMOTE_CLOSE ->
			{
				keyboardDialog?.dismiss()
				keyboardDialog = null
			}
			KeyboardEvent.Type.TEXT_CHANGE -> {}
		}
	}

	private var filterStats: String? = null
	private var lastFramesRendered = -1L
	private var lastStatsTimeMs = 0L

	private val statsRunnable = object: Runnable
	{
		override fun run()
		{
			updateStreamStats()
			uiVisibilityHandler.postDelayed(this, STATS_INTERVAL_MS)
		}
	}

	private fun updateStreamStats()
	{
		val stats = viewModel.session.stats()
		val now = SystemClock.elapsedRealtime()
		val lines = mutableListOf<String>()
		if(stats != null)
		{
			val fps = if(lastFramesRendered >= 0 && now > lastStatsTimeMs)
				(stats.framesRendered - lastFramesRendered) * 1000.0 / (now - lastStatsTimeMs)
			else
				0.0
			lastFramesRendered = stats.framesRendered
			lastStatsTimeMs = now
			val profile = viewModel.session.connectInfo.videoProfile
			if(!appStyle.isClassic)
			{
				binding.liveEdgeView.struggling = StreamLook.struggling(stats.packetLoss, fps, profile.maxFPS)
				binding.filterStatsTextView.text = StreamLook.formatStats(appStyle, profile.height, fps, stats.bitrateMbps,
					stats.packetLoss, stats.rttMs, viewModel.session.connectInfo.isPsn, controllerFeedback?.description,
					filterStats, getString(R.string.stream_stats_route_psn), getString(R.string.stream_stats_route_lan))
				return
			}
			lines += String.format("%dx%d · %.0f fps · %.1f Mbit/s · loss %.1f%% · RTT %.0f ms%s",
				profile.width, profile.height, fps, stats.bitrateMbps, stats.packetLoss * 100.0, stats.rttMs,
				if(viewModel.session.connectInfo.isPsn) " · PSN" else " · LAN")
			controllerFeedback?.let { lines += "Controller: ${it.description}" }
		}
		filterStats?.let { lines += it }
		binding.filterStatsTextView.text = lines.joinToString("\n")
	}

	/** Connects, with a fresh PSN token first for Remote Play over PSN (tokens expire after about an hour) */
	private fun resumeSession()
	{
		val session = viewModel.session
		connectProgress.reset()
		if(!session.connectInfo.isPsn)
		{
			session.setStatus(getString(R.string.status_connecting_lan))
			session.resume()
			logInputSetup()
			return
		}
		session.setStatus(getString(R.string.status_connecting_psn))
		lifecycleScope.launch {
			try
			{
				session.updatePsnToken(PsnAccount.get(this@StreamActivity).accessToken())
			}
			catch(e: Exception)
			{
				Log.w("StreamActivity", "Could not refresh the PSN token, connecting with the old one", e)
			}
			if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
			{
				session.resume()
				logInputSetup()
			}
		}
	}

	/** Which input devices count as a controller, and so whether the touch controls show */
	private fun logInputSetup()
	{
		val session = viewModel.session
		val devices = controllerFeedback?.describeInputDevices().orEmpty()
		if(devices.isEmpty())
			session.logInfo("No controller-like input devices")
		devices.forEach { session.logInfo(it) }
		val preferences = Preferences(this)
		session.logInfo("Touch controls: setting ${if(viewModel.onScreenControlsEnabled.value == true) "on" else "off"}, " +
			"hide with a controller ${if(preferences.hideTouchWithController) "on" else "off"}, " +
			"touchpad only ${if(viewModel.touchpadOnlyEnabled.value == true) "on" else "off"}, " +
			"shown ${viewModel.onScreenControlsVisible.value == true}")
	}

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			// Both touch fragments (on-screen controls and touchpad only) send their state. One job per
			// fragment: a single job slot let the second fragment cancel the first, so the on-screen
			// buttons never reached the console.
			val id = fragment.id
			controlsJobs[id]?.cancel()
			controlsJobs[id] = fragment.controllerState
				.onEach { state ->
					touchStates[id] = state
					viewModel.input.touchControllerState = touchStates.values.fold(ControllerState()) { all, one -> all or one }
				}
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsVisible
			if(fragment is TouchpadOnlyFragment)
				fragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
		}
	}

	override fun onResume()
	{
		super.onResume()
		hideSystemUI()
		filterRenderer?.let { binding.filterSurfaceView.onResume() }
		resumeSession()
		val showStats = Preferences(this).videoFilterStats
		// The other looks also read the stats for the light along the picture's edge.
		if(showStats || !appStyle.isClassic)
		{
			binding.filterStatsTextView.isVisible = showStats
			lastFramesRendered = -1L
			uiVisibilityHandler.post(statsRunnable)
		}
	}

	override fun onPause()
	{
		super.onPause()
		uiVisibilityHandler.removeCallbacks(statsRunnable)
		// The session ends on pause, and with it the microphone.
		overlay.microphoneSwitch.isChecked = false
		viewModel.session.pause()
		filterRenderer?.let { binding.filterSurfaceView.onPause() }
	}

	override fun onDestroy()
	{
		super.onDestroy()
		controlsJobs.values.forEach { it.cancel() }
		controlsJobs.clear()
		controllerFeedback?.close()
		controllerFeedback = null
		filterRenderer?.let {
			// The session lives in the view model and may outlive this activity.
			viewModel.session.setVideoSurface(null)
			it.release()
		}
		filterRenderer = null
	}

	private fun reconnect()
	{
		viewModel.session.shutdown()
		resumeSession()
	}

	private val hideSystemUIRunnable = Runnable { hideSystemUI() }

	private fun showOverlay()
	{
		if(!overlay.root.isVisible || overlay.root.alpha < 0.05f)
		{
			if(appStyle == AppStyle.CONSOLE_HOME)
				StreamLook.animateCardsIn(overlay.cards)
		}
		overlay.root.isVisible = true
		overlay.root.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					overlay.root.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		overlay.root.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					overlay.root.isGone = true
				}
			})
	}

	override fun onConfigurationChanged(newConfig: android.content.res.Configuration)
	{
		// Fold/unfold or display change, handled in place so the stream keeps running.
		super.onConfigurationChanged(newConfig)
		hideSystemUI()
		adjustStreamViewAspect()
	}

	override fun onWindowFocusChanged(hasFocus: Boolean)
	{
		super.onWindowFocusChanged(hasFocus)
		if(hasFocus)
			hideSystemUI()
	}

	private fun hideSystemUI()
	{
		insetsController.hide(WindowInsetsCompat.Type.systemBars())
	}

	private var dialogContents: DialogContents? = null
	private var dialog: AlertDialog? = null
		set(value)
		{
			field = value
			if(value == null)
				dialogContents = null
		}

	private fun stateChanged(state: StreamState)
	{
		if(connectScreen != null)
		{
			showConnectScreen(state == StreamStateConnecting || state == StreamStateIdle)
			binding.liveEdgeView.isVisible = state == StreamStateConnected
		}
		else
		{
			binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
			binding.statusTextView.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE
		}

		when(state)
		{
			is StreamStateQuit ->
			{
				if(dialogContents != StreamQuitDialog)
				{
					if(state.reason.isError)
					{
						dialog?.dismiss()
						val reasonStr = state.reasonString
						val dialog = MaterialAlertDialogBuilder(this)
							.setMessage(getString(R.string.alert_message_session_quit, state.reason.toString())
									+ (if(reasonStr != null) "\n$reasonStr" else ""))
							.setPositiveButton(R.string.action_reconnect) { _, _ ->
								dialog = null
								reconnect()
							}
							.setOnCancelListener {
								dialog = null
								finish()
							}
							.setNegativeButton(R.string.action_quit_session) { _, _ ->
								dialog = null
								finish()
							}
							.create()
						dialogContents = StreamQuitDialog
						dialog.show()
					}
					else
						finish()
				}
			}

			is StreamStateCreateError ->
			{
				if(dialogContents != CreateErrorDialog)
				{
					dialog?.dismiss()
					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(getString(R.string.alert_message_session_create_error, state.error.errorCode.toString()))
						.setOnDismissListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ -> }
						.create()
					dialogContents = CreateErrorDialog
					dialog.show()
				}
			}

			is StreamStateLoginPinRequest ->
			{
				if(dialogContents != PinRequestDialog)
				{
					dialog?.dismiss()

					val view = layoutInflater.inflate(R.layout.dialog_login_pin, null)
					val pinEditText = view.findViewById<EditText>(R.id.pinEditText)

					val dialog = MaterialAlertDialogBuilder(this)
						.setMessage(
							if(state.pinIncorrect)
								R.string.alert_message_login_pin_request_incorrect
							else
								R.string.alert_message_login_pin_request)
						.setView(view)
						.setPositiveButton(R.string.action_login_pin_connect) { _, _ ->
							dialog = null
							viewModel.session.setLoginPin(pinEditText.text.toString())
						}
						.setOnCancelListener {
							dialog = null
							finish()
						}
						.setNegativeButton(R.string.action_quit_session) { _, _ ->
							dialog = null
							finish()
						}
						.create()
					dialogContents = PinRequestDialog
					dialog.show()
				}
			}
			else ->{}
		}
	}

	private fun adjustTextureViewAspect(textureView: TextureView)
	{
		val trans = TextureViewTransform(viewModel.session.connectInfo.videoProfile, textureView)
		val resolution = trans.resolutionFor(TransformMode.fromButton(overlay.displayModeToggle.checkedButtonId))
		Matrix().also {
			textureView.getTransform(it)
			it.setScale(resolution.width / trans.viewWidth, resolution.height / trans.viewHeight)
			it.postTranslate((trans.viewWidth - resolution.width) * 0.5f, (trans.viewHeight - resolution.height) * 0.5f)
			textureView.setTransform(it)
		}
	}

	private fun adjustSurfaceViewAspect()
	{
		val videoProfile = viewModel.session.connectInfo.videoProfile
		binding.aspectRatioLayout.aspectRatio = videoProfile.width.toFloat() / videoProfile.height.toFloat()
		binding.aspectRatioLayout.mode = TransformMode.fromButton(overlay.displayModeToggle.checkedButtonId)
	}

	private fun adjustStreamViewAspect() = adjustSurfaceViewAspect()

	override fun dispatchKeyEvent(event: KeyEvent) = viewModel.input.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
	override fun onGenericMotionEvent(event: MotionEvent) = viewModel.input.onGenericMotionEvent(event) || super.onGenericMotionEvent(event)
}

enum class TransformMode
{
	FIT,
	STRETCH,
	ZOOM;

	companion object
	{
		fun fromButton(displayModeButtonId: Int)
			= when (displayModeButtonId)
			{
				R.id.display_mode_stretch_button -> STRETCH
				R.id.display_mode_zoom_button -> ZOOM
				else -> FIT
			}
	}
}

class TextureViewTransform(private val videoProfile: ConnectVideoProfile, private val textureView: TextureView)
{
	private val contentWidth : Float get() = videoProfile.width.toFloat()
	private val contentHeight : Float get() = videoProfile.height.toFloat()
	val viewWidth : Float get() = textureView.width.toFloat()
	val viewHeight : Float get() = textureView.height.toFloat()
	private val contentAspect : Float get() =  contentHeight / contentWidth

	fun resolutionFor(mode: TransformMode): Resolution
		= when(mode)
		{
			TransformMode.STRETCH -> strechedResolution
			TransformMode.ZOOM -> zoomedResolution
			TransformMode.FIT -> normalResolution
		}

	private val strechedResolution get() = Resolution(viewWidth, viewHeight)

	private val zoomedResolution get() =
		if(viewHeight > viewWidth * contentAspect)
		{
			val zoomFactor = viewHeight / contentHeight
			Resolution(contentWidth * zoomFactor, viewHeight)
		}
		else
		{
			val zoomFactor = viewWidth / contentWidth
			Resolution(viewWidth, contentHeight * zoomFactor)
		}

	private val normalResolution get() =
		if(viewHeight > viewWidth * contentAspect)
			Resolution(viewWidth, viewWidth * contentAspect)
		else
			Resolution(viewHeight / contentAspect, viewHeight)
}


data class Resolution(val width: Float, val height: Float)
