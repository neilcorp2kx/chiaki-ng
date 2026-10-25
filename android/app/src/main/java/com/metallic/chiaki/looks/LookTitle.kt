// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.looks

import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.metallic.chiaki.R
import com.metallic.chiaki.common.Preferences

/**
 * Sets the look's display face (lookTitleFont) on a title. The theme's body face would
 * otherwise win over a title's text appearance. Does nothing in Classic.
 */
fun TextView.applyLookTitleFont()
{
	val value = TypedValue()
	if(!context.theme.resolveAttribute(R.attr.lookTitleFont, value, true) || value.resourceId == 0)
		return
	ResourcesCompat.getFont(context, value.resourceId)?.let { typeface = it }
}

/**
 * Forms read badly across a wide unfolded screen: keeps the content at most maxWidthDp wide,
 * centered, by padding the sides. Does nothing in Classic or on narrow screens.
 */
fun View.centerOnWideScreen(maxWidthDp: Int = 640)
{
	if(Preferences(context).appStyle.isClassic)
		return
	val left = paddingLeft
	val right = paddingRight
	addOnLayoutChangeListener { v, l, _, r, _, oldL, _, oldR, _ ->
		if(r - l == oldR - oldL)
			return@addOnLayoutChangeListener
		val max = (maxWidthDp * v.resources.displayMetrics.density).toInt()
		val extra = ((r - l) - max).coerceAtLeast(0) / 2
		v.post { v.setPadding(left + extra, v.paddingTop, right + extra, v.paddingBottom) }
	}
}
