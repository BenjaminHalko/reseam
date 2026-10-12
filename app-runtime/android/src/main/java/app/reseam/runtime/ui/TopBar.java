// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class TopBar extends LinearLayout {
    TopBar(Ui ui, CharSequence title, Runnable back) {
        super(ui.context);
        Colors colors = ui.colors();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(ui.px(Tokens.TOP_BAR));
        setPaddingRelative(ui.px(Tokens.SPACE_MD), 0, ui.px(Tokens.SPACE_LG), 0);
        addView(ui.iconButton(Icons.ARROW_LEFT, colors.onSurface, "Back", v -> back.run()),
                new LayoutParams(ui.px(Tokens.TOUCH_TARGET), ui.px(Tokens.TOUCH_TARGET)));
        TextView label = ui.text(title, ui.type().titleLarge, colors.onSurface);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams params = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(ui.px(Tokens.SPACE_SM));
        addView(label, params);
    }
}
