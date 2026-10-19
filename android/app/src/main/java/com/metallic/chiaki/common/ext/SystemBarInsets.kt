// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.common.ext

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Pads the view so its content stays clear of the status bar, navigation bar, display cutout
 * and keyboard. Needed because targetSdk 35 draws all activities edge-to-edge.
 */
fun View.padForSystemBars()
{
	val left = paddingLeft
	val top = paddingTop
	val right = paddingRight
	val bottom = paddingBottom
	ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
		val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
		view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + max(bars.bottom, ime.bottom))
		insets
	}
	ViewCompat.requestApplyInsets(this)
}
