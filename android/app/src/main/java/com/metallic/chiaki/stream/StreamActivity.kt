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
import com.metallic.chiaki.lib.ConnectInfo
import com.metallic.chiaki.lib.ConnectVideoProfile
import com.metallic.chiaki.session.*
import com.metallic.chiaki.touchcontrols.DefaultTouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchControlsFragment
import com.metallic.chiaki.touchcontrols.TouchpadOnlyFragment
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.min

private sealed class DialogContents
private object StreamQuitDialog: DialogContents()
private object CreateErrorDialog: DialogContents()
private object PinRequestDialog: DialogContents()

class StreamActivity : AppCompatActivity()
{
	companion object
	{
		const val EXTRA_CONNECT_INFO = "connect_info"
		private const val HIDE_UI_TIMEOUT_MS = 2000L
		private const val STATS_INTERVAL_MS = 1000L
	}

	private lateinit var viewModel: StreamViewModel
	private lateinit var binding: ActivityStreamBinding
	private lateinit var insetsController: WindowInsetsControllerCompat

	private val uiVisibilityHandler = Handler(Looper.getMainLooper())

	override fun onCreate(savedInstanceState: Bundle?)
	{
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
			if(binding.onScreenControlsSwitch.isChecked != it)
				binding.onScreenControlsSwitch.isChecked = it
			if(binding.onScreenControlsSwitch.isChecked)
				binding.touchpadOnlySwitch.isChecked = false
		})
		binding.onScreenControlsSwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setOnScreenControlsEnabled(isChecked)
			showOverlay()
		}

		viewModel.touchpadOnlyEnabled.observe(this, Observer {
			if(binding.touchpadOnlySwitch.isChecked != it)
				binding.touchpadOnlySwitch.isChecked = it
			if(binding.touchpadOnlySwitch.isChecked)
				binding.onScreenControlsSwitch.isChecked = false
		})
		binding.touchpadOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
			viewModel.setTouchpadOnlyEnabled(isChecked)
			showOverlay()
		}

		binding.microphoneSwitch.setOnCheckedChangeListener { _, isChecked ->
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

		binding.displayModeToggle.addOnButtonCheckedListener { _, _, _ ->
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

		if(Preferences(this).rumbleEnabled)
		{
			val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
			viewModel.session.rumbleState.observe(this, Observer {
				val amplitude = min(255, (it.left.toInt() + it.right.toInt()) / 2)
				vibrator.cancel()
				if(amplitude == 0)
					return@Observer
				if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
					vibrator.vibrate(VibrationEffect.createOneShot(1000, amplitude))
				else
					vibrator.vibrate(1000)
			})
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
		// Render at stream resolution, the compositor scales to the view size.
		view.holder.setFixedSize(videoProfile.width, videoProfile.height)
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

	private var controlsJob: Job? = null

	private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		if(granted)
			enableMicrophone()
		else
		{
			binding.microphoneSwitch.isChecked = false
			Toast.makeText(this, R.string.microphone_permission_denied, Toast.LENGTH_LONG).show()
		}
	}

	private fun enableMicrophone()
	{
		if(!viewModel.session.setMicrophone(true))
		{
			binding.microphoneSwitch.isChecked = false
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
			lines += String.format("%dx%d · %.0f fps · %.1f Mbit/s · loss %.1f%% · RTT %.0f ms%s",
				profile.width, profile.height, fps, stats.bitrateMbps, stats.packetLoss * 100.0, stats.rttMs,
				if(viewModel.session.connectInfo.isPsn) " · PSN" else " · LAN")
		}
		filterStats?.let { lines += it }
		binding.filterStatsTextView.text = lines.joinToString("\n")
	}

	/** Connects, with a fresh PSN token first for Remote Play over PSN (tokens expire after about an hour) */
	private fun resumeSession()
	{
		val session = viewModel.session
		if(!session.connectInfo.isPsn)
		{
			session.resume()
			return
		}
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
				session.resume()
		}
	}

	override fun onAttachFragment(fragment: Fragment)
	{
		super.onAttachFragment(fragment)
		if(fragment is TouchControlsFragment)
		{
			controlsJob?.cancel()
			controlsJob = fragment.controllerState
				.onEach { viewModel.input.touchControllerState = it }
				.launchIn(lifecycleScope)
			fragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
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
		if(Preferences(this).videoFilterStats)
		{
			binding.filterStatsTextView.isVisible = true
			lastFramesRendered = -1L
			uiVisibilityHandler.post(statsRunnable)
		}
	}

	override fun onPause()
	{
		super.onPause()
		uiVisibilityHandler.removeCallbacks(statsRunnable)
		// The session ends on pause, and with it the microphone.
		binding.microphoneSwitch.isChecked = false
		viewModel.session.pause()
		filterRenderer?.let { binding.filterSurfaceView.onPause() }
	}

	override fun onDestroy()
	{
		super.onDestroy()
		controlsJob?.cancel()
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
		binding.overlay.isVisible = true
		binding.overlay.animate()
			.alpha(1.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.alpha = 1.0f
				}
			})
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}

	private fun hideOverlay()
	{
		binding.overlay.animate()
			.alpha(0.0f)
			.setListener(object: AnimatorListenerAdapter()
			{
				override fun onAnimationEnd(animation: Animator)
				{
					binding.overlay.isGone = true
				}
			})
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
		binding.progressBar.visibility = if(state == StreamStateConnecting) View.VISIBLE else View.GONE

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
		val resolution = trans.resolutionFor(TransformMode.fromButton(binding.displayModeToggle.checkedButtonId))
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
		binding.aspectRatioLayout.mode = TransformMode.fromButton(binding.displayModeToggle.checkedButtonId)
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
