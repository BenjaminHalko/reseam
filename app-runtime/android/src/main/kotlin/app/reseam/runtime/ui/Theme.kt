// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
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
        /** Uses the system night mode because hosts can force their own activity configuration. */
        @JvmStatic
        fun of(brand: Brand): Theme {
            val night =
                Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return Theme(brand, night == Configuration.UI_MODE_NIGHT_YES)
        }
    }
}
