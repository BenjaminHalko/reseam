// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.widget.LinearLayout;

public final class ItemGroup extends LinearLayout {
    private final Ui ui;

    ItemGroup(Ui ui) {
        super(ui.context);
        this.ui = ui;
        setOrientation(VERTICAL);
    }

    public ItemGroup add(ListItem item) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (getChildCount() > 0) params.topMargin = ui.px(Tokens.SPACE_XS);
        addView(item, params);
        for (int i = 0; i < getChildCount(); i++) {
            ((ListItem) getChildAt(i)).setShape(i == 0, i == getChildCount() - 1);
        }
        return this;
    }
}
