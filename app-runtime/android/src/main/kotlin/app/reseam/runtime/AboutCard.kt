// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import app.reseam.runtime.ui.Tokens
import app.reseam.runtime.ui.Ui

internal fun aboutCard(ui: Ui, bundle: ReseamBundle): View {
    val colors = ui.colors
    val schema = bundle.schema
    return LinearLayout(ui.context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = ui.shape(colors.surfaceContainer, Tokens.RADIUS_XL)
        val padding = ui.px(Tokens.SPACE_XXL)
        setPadding(padding, padding, padding, padding)
        ui.theme.logo?.let {
            addView(
                ui.icon(it, Tokens.LOGO, colors.onSurface),
                LinearLayout.LayoutParams(ui.px(Tokens.LOGO), ui.px(Tokens.LOGO)).apply {
                    bottomMargin = ui.px(Tokens.SPACE_LG)
                },
            )
        }
        addView(
            ui.text(ui.theme.brand.name, ui.type.titleLarge, colors.onSurface).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        schema.version?.let { addView(line(ui, RuntimeStrings.version(it))) }
        addView(
            line(
                ui,
                schema.license?.let { RuntimeStrings.engineLicense(schema.engine, it) }
                    ?: RuntimeStrings.engine(schema.engine),
            )
        )
    }
}

private fun line(ui: Ui, text: String): TextView =
    ui.text(text, ui.type.bodyMedium, ui.colors.onSurfaceVariant).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(0, ui.px(Tokens.SPACE_XS), 0, 0)
    }
