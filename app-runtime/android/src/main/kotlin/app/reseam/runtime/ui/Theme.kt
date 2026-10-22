// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.widget.TextView

class Theme private constructor(@JvmField val brand: Brand, @JvmField val dark: Boolean) {
    @JvmField val colors = if (dark) brand.dark else brand.light
    @JvmField val type = brand.typography
    val logo: VectorImage?
        get() = if (dark) brand.darkLogo else brand.lightLogo

    fun typeface(weight: Int): Typeface = brand.typeface(weight)

    fun style(view: TextView, style: TypeStyle) {
        style.apply(view, typeface(style.weight))
    }

    companion object {
        /**
         * Uses the system night mode because hosts can force their own activity configuration.
         * Before Android 10 there is no system dark theme, so it follows the background of the
         * activity behind [context].
         */
        @JvmStatic
        fun of(brand: Brand, context: Context): Theme {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return Theme(brand, hasDarkBackground(context))
            }
            val night =
                Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return Theme(brand, night == Configuration.UI_MODE_NIGHT_YES)
        }

        // Dialogs wrap the activity in their own theme, so the activity's theme is read instead.
        private fun hasDarkBackground(context: Context): Boolean {
            val activity =
                generateSequence(context) { (it as? ContextWrapper)?.baseContext }
                    .firstOrNull { it is Activity } ?: context
            val attributes =
                activity.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground))
            val background = attributes.getColor(0, Color.WHITE)
            attributes.recycle()
            return Color.red(background) + Color.green(background) + Color.blue(background) <
                3 * 128
        }
    }
}
