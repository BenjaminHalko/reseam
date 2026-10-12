// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.function.Consumer;

public final class SearchBar extends LinearLayout {
    private final EditText field;

    SearchBar(Ui ui, CharSequence hint) {
        super(ui.context);
        Colors colors = ui.colors();
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(ui.px(Tokens.SEARCH_BAR));
        setPadding(ui.px(Tokens.SPACE_LG), 0, ui.px(Tokens.SPACE_XS), 0);
        setBackground(ui.shape(colors.surfaceContainerHigh, Tokens.SEARCH_BAR / 2f));

        addView(ui.icon(Icons.SEARCH, Tokens.ICON_MD, colors.onSurfaceVariant),
                new LayoutParams(ui.px(Tokens.ICON_MD), ui.px(Tokens.ICON_MD)));

        field = new EditText(ui.context);
        ui.theme.style(field, ui.type().bodyLarge);
        field.setTextColor(colors.onSurface);
        field.setHintTextColor(colors.onSurfaceVariant);
        field.setHint(hint);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setPadding(ui.px(Tokens.SPACE_LG), 0, 0, 0);
        field.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            ui.context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(view.getWindowToken(), 0);
            return true;
        });
        addView(field, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        ImageView clear = ui.iconButton(Icons.CLOSE, colors.onSurfaceVariant, "Clear search", v -> field.setText(""));
        clear.setVisibility(GONE);
        addView(clear, new LayoutParams(ui.px(Tokens.TOUCH_TARGET), ui.px(Tokens.TOUCH_TARGET)));
        onQueryChange(query -> clear.setVisibility(query.isEmpty() ? GONE : VISIBLE));
    }

    public void onQueryChange(Consumer<String> listener) {
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                listener.accept(s.toString());
            }
        });
    }
}
