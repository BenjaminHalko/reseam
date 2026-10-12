// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Builds views in a brand's theme: text, icons, buttons, list items, switches, fields, bars and
 * dialogs. Get one from {@code Reseam.bundle(name).ui(context)}.
 */
public final class Ui {
    public final Context context;
    public final Theme theme;

    public Ui(Context context, Theme theme) {
        this.context = context;
        this.theme = theme;
    }

    public Colors colors() {
        return theme.colors;
    }

    public Typography type() {
        return theme.type;
    }

    public int px(float dp) {
        return Tokens.px(context, dp);
    }

    public TextView text(CharSequence value, TypeStyle style, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        theme.style(view, style);
        view.setTextColor(color);
        return view;
    }

    /** {@code image} at {@code sizeDp}, in {@code tint} where it has no colors of its own. */
    public ImageView icon(VectorImage image, int sizeDp, int tint) {
        ImageView view = new ImageView(context);
        view.setImageDrawable(new VectorIcon(image, px(sizeDp), tint));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    public ImageView iconButton(VectorImage image, int tint, CharSequence description, View.OnClickListener onClick) {
        ImageView view = icon(image, Tokens.ICON_MD, tint);
        view.setScaleType(ImageView.ScaleType.CENTER);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setContentDescription(description);
        view.setBackground(ripple(null, Tokens.TOUCH_TARGET / 2f, Tokens.TOUCH_TARGET / 2f, theme.colors.onSurfaceVariant));
        view.setOnClickListener(onClick);
        view.setMinimumWidth(px(Tokens.TOUCH_TARGET));
        view.setMinimumHeight(px(Tokens.TOUCH_TARGET));
        return view;
    }

    public GradientDrawable shape(int color, float radiusDp) {
        return shape(color, radiusDp, radiusDp);
    }

    public GradientDrawable shape(int color, float topDp, float bottomDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        float top = px(topDp);
        float bottom = px(bottomDp);
        shape.setCornerRadii(new float[] {top, top, top, top, bottom, bottom, bottom, bottom});
        return shape;
    }

    public RippleDrawable ripple(Drawable content, float topDp, float bottomDp, int stateColor) {
        return new RippleDrawable(ColorStateList.valueOf(Colors.withOpacity(stateColor, Tokens.PRESSED)),
                content, shape(0xFFFFFFFF, topDp, bottomDp));
    }

    /** A text button, in the primary color. */
    public TextView button(CharSequence label, View.OnClickListener onClick) {
        TextView button = text(label, theme.type.labelLarge, theme.colors.primary);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(px(Tokens.BUTTON));
        button.setMinimumHeight(px(Tokens.BUTTON));
        int horizontal = px(Tokens.SPACE_MD);
        button.setPadding(horizontal, 0, horizontal, 0);
        float radius = Tokens.BUTTON / 2f;
        button.setBackground(ripple(null, radius, radius, theme.colors.primary));
        button.setOnClickListener(onClick);
        button.setFocusable(true);
        return button;
    }

    public ListItem listItem(CharSequence title) {
        return new ListItem(this, title);
    }

    public ItemGroup itemGroup() {
        return new ItemGroup(this);
    }

    public TextView sectionLabel(CharSequence label) {
        TextView view = text(label, theme.type.labelLarge, theme.colors.primary);
        view.setPadding(0, px(Tokens.SPACE_LG), 0, px(Tokens.SPACE_SM));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) view.setAccessibilityHeading(true);
        return view;
    }

    public SwitchView switchView() {
        return new SwitchView(this);
    }

    public SearchBar searchBar(CharSequence hint) {
        return new SearchBar(this, hint);
    }

    public TextField textField(CharSequence label) {
        return new TextField(this, label);
    }

    public TopBar topBar(CharSequence title, Runnable back) {
        return new TopBar(this, title, back);
    }

    public BrandDialog.Builder dialog() {
        return new BrandDialog.Builder(this);
    }

}
