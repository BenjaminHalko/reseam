// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * A Material 3 basic dialog in a brand's theme: an optional icon, a title, supporting text, a list
 * of choices or a custom view, and text button actions. Build one with {@link Ui#dialog()}.
 */
public final class BrandDialog {
    private BrandDialog() {}

    public static final class Builder {
        private final Ui ui;
        private VectorImage icon;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private CharSequence[] choices;
        private int selected = -1;
        private IntConsumer onChoose;
        private final List<Action> actions = new ArrayList<>();
        private Runnable onDismiss;

        Builder(Ui ui) {
            this.ui = ui;
        }

        /** An icon above a centered title. */
        public Builder icon(VectorImage icon) {
            this.icon = icon;
            return this;
        }

        public Builder title(CharSequence title) {
            this.title = title;
            return this;
        }

        public Builder message(CharSequence message) {
            this.message = message;
            return this;
        }

        public Builder view(View view) {
            this.view = view;
            return this;
        }

        /** A list of choices that marks {@code selected}; choosing one closes the dialog. */
        public Builder choices(CharSequence[] choices, int selected, IntConsumer onChoose) {
            this.choices = choices;
            this.selected = selected;
            this.onChoose = onChoose;
            return this;
        }

        /** A button that closes the dialog and then runs {@code onClick}; the last one is the confirming action. */
        public Builder action(CharSequence label, Runnable onClick) {
            actions.add(new Action(label, onClick));
            return this;
        }

        public Builder dismissAction(CharSequence label) {
            return action(label, null);
        }

        public Builder onDismiss(Runnable onDismiss) {
            this.onDismiss = onDismiss;
            return this;
        }

        public Dialog show() {
            Dialog dialog = build();
            dialog.show();
            return dialog;
        }

        private Dialog build() {
            Colors colors = ui.colors();
            int padding = ui.px(Tokens.SPACE_XXL);
            Dialog dialog = new Dialog(ui.context);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            LinearLayout container = new LinearLayout(ui.context);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setBackground(ui.shape(colors.surfaceContainerHigh, Tokens.RADIUS_XL));
            container.setPadding(0, padding, 0, padding);

            LinearLayout header = new LinearLayout(ui.context);
            header.setOrientation(LinearLayout.VERTICAL);
            header.setPadding(padding, 0, padding, 0);
            if (icon != null) {
                View image = ui.icon(icon, Tokens.ICON_MD, colors.onSurfaceVariant);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ui.px(Tokens.ICON_MD), ui.px(Tokens.ICON_MD));
                params.gravity = Gravity.CENTER_HORIZONTAL;
                params.bottomMargin = ui.px(Tokens.SPACE_LG);
                header.addView(image, params);
            }
            if (title != null) {
                TextView label = ui.text(title, ui.type().headlineSmall, colors.onSurface);
                label.setGravity(icon != null ? Gravity.CENTER_HORIZONTAL : Gravity.START);
                header.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (message != null) {
                TextView text = ui.text(message, ui.type().bodyMedium, colors.onSurfaceVariant);
                text.setPadding(0, title != null ? ui.px(Tokens.SPACE_LG) : 0, 0, 0);
                header.addView(text);
            }
            container.addView(header);

            LinearLayout body = new LinearLayout(ui.context);
            body.setOrientation(LinearLayout.VERTICAL);
            if (choices != null) {
                body.setPadding(0, ui.px(Tokens.SPACE_LG), 0, 0);
                for (int i = 0; i < choices.length; i++) body.addView(choice(dialog, i));
            }
            if (view != null) {
                body.setPadding(padding, ui.px(Tokens.SPACE_LG), padding, 0);
                body.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (body.getChildCount() > 0) {
                ScrollView scroll = new ScrollView(ui.context);
                scroll.addView(body);
                container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }

            if (!actions.isEmpty()) {
                LinearLayout buttons = new LinearLayout(ui.context);
                buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                buttons.setPadding(padding, padding, padding - ui.px(Tokens.SPACE_MD), 0);
                for (Action action : actions) {
                    buttons.addView(ui.button(action.label, v -> {
                        dialog.dismiss();
                        if (action.onClick != null) action.onClick.run();
                    }));
                }
                container.addView(buttons);
            }

            dialog.setContentView(container);
            if (onDismiss != null) dialog.setOnDismissListener(d -> onDismiss.run());
            Window window = dialog.getWindow();
            window.setBackgroundDrawable(new ColorDrawable(0));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(Tokens.SCRIM);
            int screen = ui.context.getResources().getDisplayMetrics().widthPixels;
            int width = Math.max(ui.px(Tokens.DIALOG_MIN_WIDTH),
                    Math.min(ui.px(Tokens.DIALOG_MAX_WIDTH), screen - 2 * ui.px(Tokens.SPACE_XXXL + Tokens.SPACE_SM)));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            return dialog;
        }

        private View choice(Dialog dialog, int index) {
            ChoiceRow row = new ChoiceRow(ui, choices[index], index == selected);
            row.setOnClickListener(v -> {
                dialog.dismiss();
                onChoose.accept(index);
            });
            return row;
        }
    }

    private static final class Action {
        final CharSequence label;
        final Runnable onClick;

        Action(CharSequence label, Runnable onClick) {
            this.label = label;
            this.onClick = onClick;
        }
    }

    private static final class ChoiceRow extends LinearLayout {
        ChoiceRow(Ui ui, CharSequence title, boolean checked) {
            super(ui.context);
            Colors colors = ui.colors();
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(ui.px(Tokens.SEARCH_BAR));
            setPadding(ui.px(Tokens.SPACE_XXL), 0, ui.px(Tokens.SPACE_XXL), 0);
            setBackground(ui.ripple(null, 0, 0, colors.onSurface));
            setFocusable(true);
            addView(ui.text(title, ui.type().bodyLarge, colors.onSurface), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            if (checked) addView(ui.icon(Icons.CHECK, Tokens.ICON_MD, colors.primary),
                    new LayoutParams(ui.px(Tokens.ICON_MD), ui.px(Tokens.ICON_MD)));
            setSelected(checked);
        }
    }
}
