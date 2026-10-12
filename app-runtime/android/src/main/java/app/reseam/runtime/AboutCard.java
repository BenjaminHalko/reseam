// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.reseam.runtime.ui.Colors;
import app.reseam.runtime.ui.Tokens;
import app.reseam.runtime.ui.Ui;
import app.reseam.runtime.ui.VectorImage;

final class AboutCard {
    private AboutCard() {}

    static View create(Ui ui, ReseamBundle bundle) {
        Colors colors = ui.colors();
        SettingsSchema schema = bundle.schema;
        LinearLayout card = new LinearLayout(ui.context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(ui.shape(colors.surfaceContainer, Tokens.RADIUS_XL));
        int padding = ui.px(Tokens.SPACE_XXL);
        card.setPadding(padding, padding, padding, padding);

        VectorImage logo = ui.theme.logo();
        if (logo != null) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.px(Tokens.LOGO), ui.px(Tokens.LOGO));
            params.bottomMargin = ui.px(Tokens.SPACE_LG);
            card.addView(ui.icon(logo, Tokens.LOGO, colors.onSurface), params);
        }
        TextView name = ui.text(ui.theme.brand.name, ui.type().titleLarge, colors.onSurface);
        name.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(name);
        if (schema.version != null) card.addView(line(ui, "Version " + schema.version));
        String engine = "Engine " + schema.engine;
        card.addView(line(ui, schema.license == null ? engine : engine + " · " + schema.license));
        return card;
    }

    private static TextView line(Ui ui, String text) {
        TextView view = ui.text(text, ui.type().bodyMedium, ui.colors().onSurfaceVariant);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, ui.px(Tokens.SPACE_XS), 0, 0);
        return view;
    }
}
