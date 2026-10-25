// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.Color
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.metallic.chiaki.common.Preferences

/**
 * Debug tool for tuning the video filters without a console.
 *
 * adb push clip.mp4 /data/local/tmp/clip.mp4
 * adb shell am start -n com.metallic.chiaki/.stream.VideoFilterPreviewActivity \
 *     --es video /data/local/tmp/clip.mp4 --es deband medium --es sharpen off --ez stats true
 *
 * deband/sharpen take the preference values (off, low, medium, high).
 * Both off plays through a plain SurfaceView, like the stream does without filters.
 */
class VideoFilterPreviewActivity: AppCompatActivity()
{
	companion object
	{
		private const val TAG = "VideoFilterPreview"
	}

	private val handler = Handler(Looper.getMainLooper())
	private var player: MediaPlayer? = null
	private var glView: GLSurfaceView? = null
	private var renderer: VideoFilterRenderer? = null
	private var prepared = false

	override fun onCreate(savedInstanceState: Bundle?)
	{
		super.onCreate(savedInstanceState)
		val path = intent.getStringExtra("video") ?: "/data/local/tmp/clip.mp4"
		val deband = Preferences.Deband.values().firstOrNull { it.value == intent.getStringExtra("deband") } ?: Preferences.Deband.MEDIUM
		val sharpen = Preferences.Sharpen.values().firstOrNull { it.value == intent.getStringExtra("sharpen") } ?: Preferences.Sharpen.OFF
		val settings = VideoFilterSettings(deband.iterations, deband.threshold, deband.radius, deband.grain, sharpen.stops,
			intent.getBooleanExtra("stats", true))

		val root = FrameLayout(this).also { it.setBackgroundColor(Color.BLACK) }
		setContentView(root)
		val stats = TextView(this).also {
			it.setTextColor(Color.WHITE)
			it.setBackgroundColor(0x80000000.toInt())
			it.textSize = 12f
		}

		val player = MediaPlayer()
		this.player = player
		player.setDataSource(path)
		player.isLooping = true
		player.setOnPreparedListener { it.start() }
		player.setOnVideoSizeChangedListener { _, w, h -> Log.i(TAG, "Video size ${w}x$h") }
		val probe = android.media.MediaMetadataRetriever().also { it.setDataSource(path) }
		val width = probe.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
		val height = probe.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
		probe.release()

		val layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		if(settings.enabled)
		{
			val view = GLSurfaceView(this)
			glView = view
			view.setEGLContextClientVersion(3)
			view.preserveEGLContextOnPause = true
			val renderer = VideoFilterRenderer(view, width, height, settings,
				onSurfaceChanged = { surface, releaseOld ->
					handler.post {
						player.setSurface(surface)
						releaseOld()
						if(!prepared)
						{
							prepared = true
							player.prepareAsync()
						}
					}
				},
				onStats = { text ->
					Log.i(TAG, text)
					handler.post { stats.text = text }
				})
			this.renderer = renderer
			view.setRenderer(renderer)
			view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
			root.addView(view, layoutParams)
		}
		else
		{
			val view = SurfaceView(this)
			view.holder.setFixedSize(width, height)
			view.holder.addCallback(object: android.view.SurfaceHolder.Callback {
				override fun surfaceCreated(holder: android.view.SurfaceHolder)
				{
					player.setDisplay(holder)
					player.prepareAsync()
				}
				override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}
				override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
			})
			root.addView(view, layoutParams)
		}
		root.addView(stats, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
		Log.i(TAG, "Preview $path ${width}x$height $settings")
	}

	override fun onResume()
	{
		super.onResume()
		glView?.onResume()
	}

	override fun onPause()
	{
		super.onPause()
		glView?.onPause()
	}

	override fun onDestroy()
	{
		super.onDestroy()
		player?.release()
		player = null
		renderer?.release()
	}
}
