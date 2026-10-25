// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.app.Activity
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.databinding.HomeConsoleHomeBinding

/**
 * Console home consoles screen: a home row of console tiles. The chosen tile grows and the
 * screen below it shows that console. Choosing is by focus (D-pad) or by a first tap;
 * a second tap, or A, plays.
 */
class ConsoleHomeScreen(activity: Activity, actions: HomeActions): HomeScreen(activity, actions)
{
	private val binding = HomeConsoleHomeBinding.inflate(LayoutInflater.from(activity))
	override val root: View get() = binding.root
	override val contentView: View get() = binding.contentScrollView

	private var items: List<Pair<DisplayHost, ConsoleStatus>> = emptyList()
	private val tiles = mutableListOf<ConsoleTileView>()
	private val addTile = ConsoleTileView(activity).also {
		it.glyph = ContextCompat.getDrawable(activity, R.drawable.ic_look_add)?.mutate()?.apply { setTint(ContextCompat.getColor(activity, R.color.ch_lumen)) }
		it.contentDescription = activity.getString(R.string.home_add_tile)
	}
	/** Key of the chosen console, or null for the add tile */
	private var chosenKey: String? = null
	private var chosenIndex = 0
	private var hostsSet = false

	init
	{
		binding.findButton.setOnClickListener { actions.toggleDiscovery() }
		binding.settingsButton.setOnClickListener { actions.openSettings() }
		binding.secondaryButton.setOnClickListener { actions.openSettings() }
		binding.root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			val narrow = v.width / v.resources.displayMetrics.density < 600f
			val size = if(narrow) 40f else 54f
			if(binding.heroNameTextView.textSize != size * v.resources.displayMetrics.scaledDensity)
				v.post { binding.heroNameTextView.textSize = size }
			binding.clock.isVisible = !narrow
		}
		setupTile(addTile, -1)
		binding.tilesLayout.addView(addTile)
		show()
	}

	private fun setupTile(tile: ConsoleTileView, index: Int)
	{
		tile.setOnFocusChangeListener { _, hasFocus ->
			if(hasFocus)
				choose(if(index < 0) items.size else index)
			tile.invalidate()
		}
		tile.setOnClickListener {
			val i = if(index < 0) items.size else index
			if(i != chosenIndex)
				choose(i)
			else
				primaryAction(it)
		}
	}

	override fun setHosts(hosts: List<DisplayHost>)
	{
		items = hosts.map { it to statusOf(it) }
		// One tile per console, then the add tile
		while(tiles.size < items.size)
		{
			val tile = ConsoleTileView(activity)
			tile.glyph = ContextCompat.getDrawable(activity, R.drawable.ic_look_console)
			setupTile(tile, tiles.size)
			if(tiles.isEmpty() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
				tile.isFocusedByDefault = true
			tiles += tile
			binding.tilesLayout.addView(tile, tiles.size - 1, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
		}
		while(tiles.size > items.size)
			binding.tilesLayout.removeView(tiles.removeAt(tiles.size - 1))
		items.forEachIndexed { i, (_, status) ->
			tiles[i].light = status.light
			tiles[i].contentDescription = "${status.name}. ${status.state}"
		}
		val keyIndex = items.indexOfFirst { it.second.key == chosenKey }
		chosenIndex = when
		{
			chosenKey == null && hostsSet -> items.size
			keyIndex >= 0 -> keyIndex
			else -> 0
		}
		chosenKey = items.getOrNull(chosenIndex)?.second?.key
		show()
		if(!hostsSet && items.isNotEmpty())
		{
			hostsSet = true
			binding.tilesLayout.post { tiles.firstOrNull()?.requestFocus() }
		}
	}

	private fun choose(index: Int)
	{
		if(index == chosenIndex && hostsSet)
			return show()
		chosenIndex = index
		chosenKey = items.getOrNull(index)?.second?.key
		show()
	}

	private fun show()
	{
		val all = tiles + addTile
		val index = chosenIndex.coerceIn(0, items.size)
		all.forEachIndexed { i, tile -> tile.chosen = i == index }
		all.getOrNull(index)?.let { tile ->
			binding.tilesScrollView.post {
				binding.tilesScrollView.smoothScrollTo((tile.left - tile.width).coerceAtLeast(0), 0)
			}
		}
		val item = items.getOrNull(index)
		if(item == null)
		{
			// The add tile, or no consoles yet
			binding.ambientView.warm = false
			binding.heroNameTextView.setText(if(items.isEmpty()) R.string.home_empty_title else R.string.home_add_console)
			binding.heroStateTextView.setText(R.string.home_empty_body)
			binding.heroAppTextView.isVisible = false
			binding.primaryButton.setText(R.string.home_add_console)
			binding.secondaryButton.setText(R.string.home_settings)
			binding.optionsButton.isVisible = false
			return
		}
		val (_, status) = item
		binding.ambientView.warm = status.light == ConsoleLight.STANDBY
		binding.heroNameTextView.text = status.name
		val lumen = ContextCompat.getColor(activity, if(status.light == ConsoleLight.STANDBY) R.color.ch_standby else R.color.ch_lumen)
		binding.heroStateTextView.text = SpannableStringBuilder().apply {
			append(status.state, ForegroundColorSpan(lumen), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			append(". ")
			append(status.route)
		}
		binding.heroAppTextView.text = status.app
		binding.heroAppTextView.isVisible = status.app != null
		binding.primaryButton.text = status.action
		binding.secondaryButton.setText(R.string.action_stream_settings)
		binding.optionsButton.isVisible = status.hasOptions
		binding.optionsButton.contentDescription = activity.getString(R.string.home_console_options, status.name)
		binding.optionsButton.setOnClickListener { actions.options(item.first, it) }
		binding.primaryButton.setOnClickListener { primaryAction(it) }
	}

	private fun primaryAction(source: View)
	{
		val item = items.getOrNull(chosenIndex)
		if(item == null)
			actions.addConsole(source)
		else
			actions.play(item.first)
	}

	override fun setDiscoveryActive(active: Boolean)
	{
		binding.findButton.alpha = if(active) 1f else 0.6f
		binding.findButton.contentDescription = activity.getString(if(active) R.string.home_find_on_network_on else R.string.home_find_on_network_off)
	}

	override fun setPsnSignedIn(signedIn: Boolean)
	{
		binding.accountTextView.setText(if(signedIn) R.string.home_psn_signed_in else R.string.home_psn_signed_out)
	}

	override fun setControllerConnected(connected: Boolean)
	{
		binding.hintTextView.isVisible = connected && items.size > 1
	}

	override fun onKeyDown(keyCode: Int): Boolean
	{
		if(isOptionsKey(keyCode))
		{
			val item = items.getOrNull(chosenIndex) ?: return false
			if(!item.second.hasOptions)
				return false
			actions.options(item.first, binding.optionsButton)
			return true
		}
		if(isAddKey(keyCode))
		{
			actions.addConsole(addTile)
			return true
		}
		return false
	}
}
