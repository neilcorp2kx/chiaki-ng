// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.metallic.chiaki.R
import com.metallic.chiaki.common.DisplayHost
import com.metallic.chiaki.databinding.HomeStatusLightBinding
import com.metallic.chiaki.databinding.ItemStatusLightConsoleBinding
import kotlin.math.max

/** Status light consoles screen: a grid of tiles, each with the console's light strip */
class StatusLightHomeScreen(activity: Activity, actions: HomeActions): HomeScreen(activity, actions)
{
	private val binding = HomeStatusLightBinding.inflate(LayoutInflater.from(activity))
	override val root: View get() = binding.root
	override val contentView: View get() = binding.root

	private val adapter = Adapter()
	private val layoutManager = GridLayoutManager(activity, 1)
	private var hostsSet = false

	init
	{
		binding.consolesRecyclerView.layoutManager = layoutManager
		binding.consolesRecyclerView.adapter = adapter
		binding.consolesRecyclerView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			val dp = v.width / v.resources.displayMetrics.density
			val spans = max(1, (dp / 320f).toInt())
			if(layoutManager.spanCount != spans)
				v.post { layoutManager.spanCount = spans }
		}
		binding.root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
			// Narrow screens (folded): icon-only header buttons
			val narrow = v.width / v.resources.displayMetrics.density < 600f
			for(button in listOf(binding.findButton, binding.addButton))
			{
				val label = button.tag as? CharSequence ?: button.text.also { button.tag = it }
				val wanted = if(narrow) "" else label
				if(button.text != wanted)
					v.post {
						button.text = wanted
						if(button === binding.addButton)
							button.contentDescription = label
						button.compoundDrawablePadding = if(narrow) 0 else (8 * v.resources.displayMetrics.density).toInt()
					}
			}
		}
		binding.findButton.setOnClickListener { actions.toggleDiscovery() }
		binding.addButton.setOnClickListener { actions.addConsole(it) }
		binding.settingsButton.setOnClickListener { actions.openSettings() }
		binding.emptyLight.light = ConsoleLight.OFF
	}

	override fun setHosts(hosts: List<DisplayHost>)
	{
		adapter.items = hosts.map { it to statusOf(it) }
		binding.emptyLayout.isVisible = hosts.isEmpty()
		if(!hostsSet && hosts.isNotEmpty())
		{
			hostsSet = true
			binding.consolesRecyclerView.post {
				// Controller users land on the first console.
				binding.consolesRecyclerView.getChildAt(0)?.requestFocus()
			}
		}
	}

	override fun setDiscoveryActive(active: Boolean)
	{
		binding.findButton.isActivated = active
		binding.findButton.alpha = if(active) 1f else 0.72f
		binding.findButton.contentDescription = activity.getString(if(active) R.string.home_find_on_network_on else R.string.home_find_on_network_off)
		binding.findButton.tag = activity.getString(R.string.home_find_on_network)
		val tint = ContextCompat.getColor(activity, if(active) R.color.sl_frost else R.color.sl_haze)
		binding.findButton.compoundDrawablesRelative[0]?.mutate()?.setTint(tint)
	}

	override fun setPsnSignedIn(signedIn: Boolean)
	{
		binding.accountLight.light = if(signedIn) ConsoleLight.ON else ConsoleLight.OFF
		binding.accountTextView.setText(if(signedIn) R.string.home_psn_signed_in else R.string.home_psn_signed_out)
	}

	override fun setControllerConnected(connected: Boolean)
	{
		binding.hintTextView.isVisible = connected
	}

	private fun focusedHost(): DisplayHost?
	{
		val child = binding.consolesRecyclerView.focusedChild ?: return null
		val position = binding.consolesRecyclerView.getChildAdapterPosition(child)
		return adapter.items.getOrNull(position)?.first
	}

	override fun onKeyDown(keyCode: Int): Boolean
	{
		if(isOptionsKey(keyCode))
		{
			val host = focusedHost() ?: return false
			actions.options(host, binding.consolesRecyclerView.focusedChild)
			return true
		}
		if(isAddKey(keyCode))
		{
			actions.addConsole(binding.addButton)
			return true
		}
		return false
	}

	private inner class Adapter: RecyclerView.Adapter<Adapter.ViewHolder>()
	{
		var items: List<Pair<DisplayHost, ConsoleStatus>> = emptyList()
			set(value)
			{
				val old = field
				field = value
				DiffUtil.calculateDiff(object: DiffUtil.Callback()
				{
					override fun getOldListSize() = old.size
					override fun getNewListSize() = value.size
					override fun areItemsTheSame(o: Int, n: Int) = old[o].second.key == value[n].second.key
					override fun areContentsTheSame(o: Int, n: Int) = old[o].second == value[n].second
				}).dispatchUpdatesTo(this)
			}

		inner class ViewHolder(val binding: ItemStatusLightConsoleBinding): RecyclerView.ViewHolder(binding.root)

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
			ViewHolder(ItemStatusLightConsoleBinding.inflate(LayoutInflater.from(parent.context), parent, false))

		override fun getItemCount() = items.size

		override fun onBindViewHolder(holder: ViewHolder, position: Int)
		{
			val (host, status) = items[position]
			val b = holder.binding
			b.strip.light = status.light
			b.nameTextView.text = status.name
			b.stateTextView.text = status.state
			b.stateTextView.setTextColor(ContextCompat.getColor(activity, when(status.light)
			{
				ConsoleLight.STANDBY -> R.color.sl_standby
				ConsoleLight.ON -> R.color.sl_frost
				ConsoleLight.OFF -> R.color.sl_haze
			}))
			b.routeTextView.text = status.route
			b.appTextView.text = status.app
			b.appTextView.isVisible = status.app != null
			b.root.contentDescription = "${status.name}. ${status.state}. ${status.route}. ${status.action}"
			b.root.setOnClickListener { actions.play(host) }
			// A controller's first D-pad press lands on the first console, not the header
			if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
				b.root.isFocusedByDefault = position == 0
			b.optionsButton.isVisible = status.hasOptions
			b.optionsButton.contentDescription = activity.getString(R.string.home_console_options, status.name)
			b.optionsButton.setOnClickListener { actions.options(host, it) }
			b.root.setOnLongClickListener {
				if(status.hasOptions)
				{
					actions.options(host, b.optionsButton)
					true
				}
				else
					false
			}
		}
	}
}
