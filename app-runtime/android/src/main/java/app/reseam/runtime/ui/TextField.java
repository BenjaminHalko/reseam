// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

public final class TextField extends FrameLayout {
    private final Ui ui;
    private final EditText field;
    private final TextView label;
    private final Outline outline;
    private ImageView action;

    TextField(Ui ui, CharSequence labelText) {
        super(ui.context);
        this.ui = ui;
        Colors colors = ui.colors();
        label = ui.text(labelText, ui.type().labelMedium, colors.onSurfaceVariant);
        int labelHeight = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                ui.type().labelMedium.lineHeight, getResources().getDisplayMetrics()));

        field = new EditText(ui.context);
        ui.theme.style(field, ui.type().bodyLarge);
        field.setTextColor(colors.onSurface);
        field.setHintTextColor(colors.onSurfaceVariant);
        field.setMinHeight(ui.px(Tokens.SEARCH_BAR));
        field.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setSingleLine(true);
        int padding = ui.px(Tokens.SPACE_LG);
        field.setPadding(padding, padding, padding, padding);
        outline = new Outline(colors, ui.px(Tokens.RADIUS_XS), ui.px(1), ui.px(2));
        field.setBackground(outline);
        field.setOnFocusChangeListener((view, focused) -> {
            outline.setFocused(focused);
            label.setTextColor(focused ? colors.primary : colors.onSurfaceVariant);
        });
        LayoutParams fieldParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = labelHeight / 2;
        addView(field, fieldParams);

        label.setPadding(ui.px(Tokens.SPACE_XS), 0, ui.px(Tokens.SPACE_XS), 0);
        label.setLabelFor(View.generateViewId());
        field.setId(label.getLabelFor());
        LayoutParams labelParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        labelParams.setMarginStart(ui.px(Tokens.SPACE_MD));
        addView(label, labelParams);
        label.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                outline.setGap(left, right));
    }

    public EditText field() {
        return field;
    }

    public String text() {
        return field.getText().toString();
    }

    public TextField text(CharSequence value) {
        field.setText(value);
        field.setSelection(field.length());
        return this;
    }

    public TextField multiline(int lines) {
        field.setSingleLine(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        field.setMinLines(lines);
        field.setGravity(Gravity.TOP | Gravity.START);
        return this;
    }

    public TextField action(VectorImage icon, CharSequence description, View.OnClickListener onClick) {
        if (action != null) removeView(action);
        action = ui.iconButton(icon, ui.colors().onSurfaceVariant, description, onClick);
        LayoutParams params = new LayoutParams(ui.px(Tokens.TOUCH_TARGET), ui.px(Tokens.TOUCH_TARGET), Gravity.END | Gravity.TOP);
        params.topMargin = ((LayoutParams) field.getLayoutParams()).topMargin + ui.px(Tokens.SPACE_XS);
        params.setMarginEnd(ui.px(Tokens.SPACE_XS));
        addView(action, params);
        field.setPaddingRelative(field.getPaddingStart(), field.getPaddingTop(), ui.px(Tokens.TOUCH_TARGET + Tokens.SPACE_XS),
                field.getPaddingBottom());
        return this;
    }

    public void showAction(boolean visible) {
        if (action != null) action.setVisibility(visible ? VISIBLE : INVISIBLE);
    }

    private static final class Outline extends Drawable {
        private final Colors colors;
        private final float radius;
        private final float thin;
        private final float thick;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private boolean focused;
        private float gapStart;
        private float gapEnd;

        Outline(Colors colors, float radius, float thin, float thick) {
            this.colors = colors;
            this.radius = radius;
            this.thin = thin;
            this.thick = thick;
            paint.setStyle(Paint.Style.STROKE);
        }

        void setFocused(boolean focused) {
            this.focused = focused;
            invalidateSelf();
        }

        void setGap(float start, float end) {
            gapStart = start;
            gapEnd = end;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            float width = focused ? thick : thin;
            paint.setStrokeWidth(width);
            paint.setColor(focused ? colors.primary : colors.outline);
            bounds.set(getBounds());
            bounds.inset(width / 2, width / 2);
            canvas.save();
            canvas.clipOutRect(gapStart, bounds.top - width, gapEnd, bounds.top + width);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
