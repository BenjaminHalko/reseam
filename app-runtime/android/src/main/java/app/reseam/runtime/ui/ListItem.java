// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ListItem extends LinearLayout {
    private final Ui ui;
    private final ImageView icon;
    private final TextView title;
    private final TextView supporting;
    private View trailing;

    ListItem(Ui ui, CharSequence title) {
        super(ui.context);
        this.ui = ui;
        Colors colors = ui.colors();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(ui.px(Tokens.LIST_ITEM));
        setPadding(ui.px(Tokens.SPACE_LG), ui.px(Tokens.SPACE_MD), ui.px(Tokens.SPACE_LG), ui.px(Tokens.SPACE_MD));

        icon = new ImageView(ui.context);
        icon.setVisibility(GONE);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams iconParams = new LayoutParams(ui.px(Tokens.ICON_MD), ui.px(Tokens.ICON_MD));
        iconParams.setMarginEnd(ui.px(Tokens.SPACE_LG));
        addView(icon, iconParams);

        LinearLayout texts = new LinearLayout(ui.context);
        texts.setOrientation(VERTICAL);
        this.title = ui.text(title, ui.type().bodyLarge, colors.onSurface);
        texts.addView(this.title);
        supporting = ui.text("", ui.type().bodyMedium, colors.onSurfaceVariant);
        supporting.setVisibility(GONE);
        texts.addView(supporting);
        addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        setShape(true, true);
    }

    public ListItem icon(VectorImage image) {
        icon.setImageDrawable(image == null ? null : new VectorIcon(image, ui.px(Tokens.ICON_MD), ui.colors().onSurfaceVariant));
        icon.setVisibility(image == null ? GONE : VISIBLE);
        return this;
    }

    public ListItem title(CharSequence value) {
        title.setText(value);
        return this;
    }

    public ListItem supporting(CharSequence value) {
        supporting.setText(value);
        supporting.setVisibility(value == null || value.length() == 0 ? GONE : VISIBLE);
        return this;
    }

    /** Replaces the trailing view; {@code null} removes it. */
    public ListItem trailing(View view) {
        if (trailing != null) removeView(trailing);
        trailing = view;
        if (view != null) {
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            params.setMarginStart(ui.px(Tokens.SPACE_LG));
            addView(view, params);
        }
        return this;
    }

    public ListItem chevron() {
        return trailing(ui.icon(Icons.CHEVRON_RIGHT, Tokens.ICON_MD, ui.colors().onSurfaceVariant));
    }

    public ListItem onClick(View.OnClickListener listener) {
        setOnClickListener(listener);
        setFocusable(true);
        return this;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : Tokens.DISABLED);
    }

    void setShape(boolean first, boolean last) {
        float top = first ? Tokens.RADIUS_LG : Tokens.RADIUS_SM;
        float bottom = last ? Tokens.RADIUS_LG : Tokens.RADIUS_SM;
        setBackground(ui.ripple(ui.shape(ui.colors().surfaceContainer, top, bottom), top, bottom, ui.colors().onSurface));
    }
}
