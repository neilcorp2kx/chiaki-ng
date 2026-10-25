// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.view.View
import org.json.JSONObject

/**
 * Where the player moved the on-screen controls, and how big they made them. Stored per control
 * as an offset from its default place (a fraction of the screen) and a scale, separately for
 * each kind of screen (folded and unfolded keep their own arrangement).
 */
object ControlsLayout
{
	data class Placement(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f)

	const val MIN_SCALE = 0.5f
	const val MAX_SCALE = 2f

	private const val PREFS = "touch_controls_layout"

	/** Bumped on every save, so views that keep their placement applied know to reload */
	@Volatile var version = 0
		private set

	fun key(context: Context): String
	{
		val c = context.resources.configuration
		val size = if(c.smallestScreenWidthDp >= 600) "large" else "compact"
		val orientation = if(c.screenWidthDp >= c.screenHeightDp) "land" else "port"
		return "${size}_$orientation"
	}

	fun load(context: Context, key: String = key(context)): Map<String, Placement>
	{
		val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null) ?: return emptyMap()
		return try
		{
			val o = JSONObject(json)
			o.keys().asSequence().associateWith { name ->
				val p = o.getJSONObject(name)
				Placement(p.optDouble("dx", 0.0).toFloat(), p.optDouble("dy", 0.0).toFloat(), p.optDouble("s", 1.0).toFloat())
			}
		}
		catch(e: Exception)
		{
			emptyMap()
		}
	}

	fun save(context: Context, key: String, placements: Map<String, Placement>)
	{
		val o = JSONObject()
		placements.filterValues { it != Placement() }.forEach { (name, p) ->
			o.put(name, JSONObject().put("dx", p.dx.toDouble()).put("dy", p.dy.toDouble()).put("s", p.scale.toDouble()))
		}
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, o.toString()).apply()
		version++
	}

	fun resetAll(context: Context)
	{
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
		version++
	}

	/** Moves and scales a view, relative to its parent's size */
	fun apply(view: View, placement: Placement?)
	{
		val parent = view.parent as? View ?: return
		val p = placement ?: Placement()
		view.translationX = p.dx * parent.width
		view.translationY = p.dy * parent.height
		view.scaleX = p.scale
		view.scaleY = p.scale
	}

	/**
	 * Keeps the saved placements applied to these views as the screen changes size, for example
	 * when the phone is folded or unfolded.
	 */
	fun keepApplied(root: View, views: () -> Map<String, View>)
	{
		var appliedFor = ""
		fun applyAll()
		{
			val key = key(root.context)
			val state = "$key/$version/${root.width}x${root.height}"
			if(state == appliedFor)
				return
			appliedFor = state
			val placements = load(root.context, key)
			views().forEach { (name, view) -> apply(view, placements[name]) }
		}
		root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root.post { applyAll() } }
		root.post { applyAll() }
	}
}
