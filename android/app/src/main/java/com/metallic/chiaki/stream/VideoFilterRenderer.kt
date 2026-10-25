// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * Optional post-processing between the decoder and the screen.
 *
 * Without filters the decoder renders straight into a SurfaceView (zero copy).
 * With filters it renders into a SurfaceTexture, and one fragment shader pass
 * applies debanding and/or RCAS sharpening. The GL surface is sized to the
 * view size; the filters work in stream pixels (u_texelX/u_texelY) and the same pass scales
 * the picture to the view.
 */
data class VideoFilterSettings(
	/** 0 disables debanding */
	val debandIterations: Int,
	/** Per-channel difference below which a pixel counts as part of a gradient (0-1 scale) */
	val debandThreshold: Float,
	/** Sampling radius in stream pixels for the first iteration */
	val debandRadius: Float,
	/** Dither amplitude in 8-bit steps, applied after debanding */
	val debandGrain: Float,
	/** RCAS sharpness in stops (0 = strongest), null disables sharpening */
	val sharpenStops: Float?,
	val showStats: Boolean
)
{
	val debandEnabled get() = debandIterations > 0
	val enabled get() = debandEnabled || sharpenStops != null
}

class VideoFilterRenderer(
	private val view: GLSurfaceView,
	private val videoWidth: Int,
	private val videoHeight: Int,
	private val settings: VideoFilterSettings,
	/**
	 * Called on the GL thread when the decoder output surface changes.
	 * Call releaseOld once the decoder has switched to the new surface.
	 */
	private val onSurfaceChanged: (surface: Surface, releaseOld: () -> Unit) -> Unit,
	/** Called on the GL thread about once per second when stats are enabled */
	private val onStats: (String) -> Unit
): GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener
{
	companion object
	{
		private const val TAG = "VideoFilterRenderer"

		private const val VERTEX_SHADER = """#version 300 es
in vec2 a_position;
uniform mat4 u_texMatrix;
out vec2 v_texCoord;
void main()
{
	v_texCoord = (u_texMatrix * vec4(a_position * 0.5 + 0.5, 0.0, 1.0)).xy;
	gl_Position = vec4(a_position, 0.0, 1.0);
}
"""

		private const val FRAGMENT_SHADER_BODY = """
precision highp float;
precision highp int;

uniform samplerExternalOES u_texture;
// One stream pixel along x and y, in texture coordinates after the SurfaceTexture transform
uniform vec2 u_texelX;
uniform vec2 u_texelY;
// Valid texture area (the decoder buffer can be padded beyond the visible picture)
uniform vec2 u_uvMin;
uniform vec2 u_uvMax;
uniform uint u_frame;

in vec2 v_texCoord;
out vec4 outColor;

vec3 tap(vec2 uv)
{
	return texture(u_texture, clamp(uv, u_uvMin, u_uvMax)).rgb;
}

uint hash(uint x)
{
	x ^= x >> 16;
	x *= 0x7feb352du;
	x ^= x >> 15;
	x *= 0x846ca68bu;
	x ^= x >> 16;
	return x;
}

float rand(inout uint state)
{
	state = hash(state);
	return float(state >> 8) * (1.0 / 16777216.0);
}

void main()
{
	vec2 uv = v_texCoord;
	vec3 original = tap(uv);
	vec3 color = original;

#ifdef DEBAND
	uint state = hash(uint(gl_FragCoord.x) + hash(uint(gl_FragCoord.y) + hash(u_frame)));

	// Same idea as libplacebo's deband: average 4 random taps on a ring and
	// keep the average only where it is close to the source pixel.
	for(int i = 1; i <= DEBAND_ITERATIONS; i++)
	{
		float r = rand(state) * DEBAND_RADIUS * float(i);
		float a = rand(state) * 6.2831853;
		vec2 o1 = (cos(a) * u_texelX + sin(a) * u_texelY) * r;
		vec2 o2 = (cos(a) * u_texelY - sin(a) * u_texelX) * r;
		vec3 avg = 0.25 * (tap(uv + o1) + tap(uv - o1) + tap(uv + o2) + tap(uv - o2));
		vec3 diff = abs(color - avg);
		color = mix(avg, color, greaterThan(diff, vec3(DEBAND_THRESHOLD / float(i))));
	}
#endif

#ifdef SHARPEN
	// RCAS from AMD FidelityFX FSR 1.0, on the source neighbours
	vec3 b = tap(uv - u_texelY);
	vec3 d = tap(uv - u_texelX);
	vec3 f = tap(uv + u_texelX);
	vec3 h = tap(uv + u_texelY);
	vec3 mn4 = min(min(b, d), min(f, h));
	vec3 mx4 = max(max(b, d), max(f, h));
	vec3 hitMin = min(mn4, original) / (4.0 * mx4 + 1e-5);
	vec3 hitMax = (1.0 - max(mx4, original)) / min(4.0 * mn4 - 4.0, -1e-5);
	vec3 lobe3 = max(-hitMin, hitMax);
	float lobe = max(-0.1875, min(max(lobe3.r, max(lobe3.g, lobe3.b)), 0.0)) * SHARPEN_AMOUNT;
#ifdef DEBAND
	// Do not sharpen the band edges that debanding just smoothed out
	vec3 range = mx4 - mn4;
	lobe *= smoothstep(2.0 * DEBAND_THRESHOLD, 4.0 * DEBAND_THRESHOLD, max(range.r, max(range.g, range.b)));
#endif
	color = (lobe * (b + d + f + h) + color) / (4.0 * lobe + 1.0);
#endif

#ifdef DEBAND
	// Triangular dither, so the smoothed values are not quantized back into bands
	float noise = rand(state) + rand(state) - 1.0;
	color += noise * (DEBAND_GRAIN / 255.0);
#endif

	outColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
"""

		private val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

		private const val STATS_INTERVAL_MS = 1000L
	}

	private var program = 0
	private var textureId = 0
	@Volatile private var surfaceTexture: SurfaceTexture? = null
	@Volatile private var surface: Surface? = null

	private var positionLocation = 0
	private var texMatrixLocation = 0
	private var texelXLocation = 0
	private var texelYLocation = 0
	private var uvMinLocation = 0
	private var uvMaxLocation = 0
	private var frameLocation = 0

	private val texMatrix = FloatArray(16)
	private val quadBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
		.order(ByteOrder.nativeOrder())
		.asFloatBuffer()
		.put(QUAD)
		.also { it.position(0) }

	private var frame = 0

	// Stats, written from the frame-available thread and read on the GL thread
	private val framesAvailable = AtomicInteger(0)
	private val pendingSinceNs = AtomicLong(0)
	private var statsStartMs = 0L
	private var statsFramesDrawn = 0
	private var statsTotalNs = 0L
	private var statsMaxNs = 0L

	private fun buildFragmentShader(): String
	{
		val defines = StringBuilder()
		if(settings.debandEnabled)
		{
			defines.append("#define DEBAND\n")
			defines.append("#define DEBAND_ITERATIONS ${settings.debandIterations}\n")
			defines.append("#define DEBAND_THRESHOLD ${settings.debandThreshold}\n")
			defines.append("#define DEBAND_RADIUS ${settings.debandRadius}\n")
			defines.append("#define DEBAND_GRAIN ${settings.debandGrain}\n")
		}
		settings.sharpenStops?.let {
			defines.append("#define SHARPEN\n")
			defines.append("#define SHARPEN_AMOUNT ${Math.pow(2.0, -it.toDouble()).toFloat()}\n")
		}
		return "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n$defines$FRAGMENT_SHADER_BODY"
	}

	private fun compileShader(type: Int, source: String): Int
	{
		val shader = GLES30.glCreateShader(type)
		GLES30.glShaderSource(shader, source)
		GLES30.glCompileShader(shader)
		val status = IntArray(1)
		GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
		if(status[0] == 0)
		{
			val log = GLES30.glGetShaderInfoLog(shader)
			GLES30.glDeleteShader(shader)
			throw RuntimeException("Failed to compile shader: $log")
		}
		return shader
	}

	private fun createProgram(): Int
	{
		val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER)
		val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, buildFragmentShader())
		val program = GLES30.glCreateProgram()
		GLES30.glAttachShader(program, vertexShader)
		GLES30.glAttachShader(program, fragmentShader)
		GLES30.glLinkProgram(program)
		GLES30.glDeleteShader(vertexShader)
		GLES30.glDeleteShader(fragmentShader)
		val status = IntArray(1)
		GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
		if(status[0] == 0)
		{
			val log = GLES30.glGetProgramInfoLog(program)
			GLES30.glDeleteProgram(program)
			throw RuntimeException("Failed to link program: $log")
		}
		return program
	}

	override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
	{
		// Also called again after the EGL context was lost, so everything is recreated here.
		program = createProgram()
		positionLocation = GLES30.glGetAttribLocation(program, "a_position")
		texMatrixLocation = GLES30.glGetUniformLocation(program, "u_texMatrix")
		texelXLocation = GLES30.glGetUniformLocation(program, "u_texelX")
		texelYLocation = GLES30.glGetUniformLocation(program, "u_texelY")
		uvMinLocation = GLES30.glGetUniformLocation(program, "u_uvMin")
		uvMaxLocation = GLES30.glGetUniformLocation(program, "u_uvMax")
		frameLocation = GLES30.glGetUniformLocation(program, "u_frame")

		val textures = IntArray(1)
		GLES30.glGenTextures(1, textures, 0)
		textureId = textures[0]
		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

		val oldSurfaceTexture = surfaceTexture
		val oldSurface = surface
		val newSurfaceTexture = SurfaceTexture(textureId).also {
			it.setDefaultBufferSize(videoWidth, videoHeight)
			it.setOnFrameAvailableListener(this)
		}
		val newSurface = Surface(newSurfaceTexture)
		surfaceTexture = newSurfaceTexture
		surface = newSurface
		onSurfaceChanged(newSurface) {
			oldSurface?.release()
			oldSurfaceTexture?.release()
		}

		resetStats()
		Log.i(TAG, "Video filters ready: $settings")
	}

	override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
	{
		GLES30.glViewport(0, 0, width, height)
	}

	override fun onFrameAvailable(surfaceTexture: SurfaceTexture)
	{
		framesAvailable.incrementAndGet()
		pendingSinceNs.compareAndSet(0, System.nanoTime())
		view.requestRender()
	}

	override fun onDrawFrame(gl: GL10?)
	{
		val surfaceTexture = surfaceTexture ?: return
		val since = pendingSinceNs.getAndSet(0)
		surfaceTexture.updateTexImage()
		surfaceTexture.getTransformMatrix(texMatrix)

		GLES30.glUseProgram(program)
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
		GLES30.glUniformMatrix4fv(texMatrixLocation, 1, false, texMatrix, 0)

		// Column-major matrix: a step along s maps to column 0, a step along t to column 1.
		val dx = 1f / videoWidth
		val dy = 1f / videoHeight
		GLES30.glUniform2f(texelXLocation, texMatrix[0] * dx, texMatrix[1] * dx)
		GLES30.glUniform2f(texelYLocation, texMatrix[4] * dy, texMatrix[5] * dy)
		val u0 = texMatrix[12]
		val v0 = texMatrix[13]
		val u1 = texMatrix[0] + texMatrix[4] + texMatrix[12]
		val v1 = texMatrix[1] + texMatrix[5] + texMatrix[13]
		// Inset by half a texel so bilinear taps at the edge do not read the padding.
		val insetU = 0.5f * (Math.abs(texMatrix[0] * dx) + Math.abs(texMatrix[4] * dy))
		val insetV = 0.5f * (Math.abs(texMatrix[1] * dx) + Math.abs(texMatrix[5] * dy))
		GLES30.glUniform2f(uvMinLocation, minOf(u0, u1) + insetU, minOf(v0, v1) + insetV)
		GLES30.glUniform2f(uvMaxLocation, maxOf(u0, u1) - insetU, maxOf(v0, v1) - insetV)
		GLES30.glUniform1ui(frameLocation, frame++)

		GLES30.glEnableVertexAttribArray(positionLocation)
		GLES30.glVertexAttribPointer(positionLocation, 2, GLES30.GL_FLOAT, false, 0, quadBuffer)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
		GLES30.glDisableVertexAttribArray(positionLocation)

		if(settings.showStats)
		{
			// Waiting for the GPU costs a little latency, so only when stats are shown.
			GLES30.glFinish()
			if(since != 0L)
				recordFrame(System.nanoTime() - since)
		}
	}

	private fun resetStats()
	{
		framesAvailable.set(0)
		statsStartMs = SystemClock.elapsedRealtime()
		statsFramesDrawn = 0
		statsTotalNs = 0
		statsMaxNs = 0
	}

	private fun recordFrame(ns: Long)
	{
		statsFramesDrawn++
		statsTotalNs += ns
		statsMaxNs = max(statsMaxNs, ns)
		val elapsedMs = SystemClock.elapsedRealtime() - statsStartMs
		if(elapsedMs < STATS_INTERVAL_MS)
			return
		val received = framesAvailable.get()
		val fps = statsFramesDrawn * 1000f / elapsedMs
		val avgMs = statsTotalNs / 1e6f / statsFramesDrawn
		val maxMs = statsMaxNs / 1e6f
		val skipped = max(0, received - statsFramesDrawn)
		onStats(String.format("Filters: %.0f fps · %.1f ms avg · %.1f ms max · %d skipped", fps, avgMs, maxMs, skipped))
		resetStats()
	}

	/** Call after GLSurfaceView.onPause() and after the decoder no longer uses the surface */
	fun release()
	{
		surface?.release()
		surface = null
		surfaceTexture?.release()
		surfaceTexture = null
	}
}
