// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import app.reseam.runtime.ui.Icons;
import app.reseam.runtime.ui.ListItem;
import app.reseam.runtime.ui.SwitchView;
import app.reseam.runtime.ui.TextField;
import app.reseam.runtime.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the row for each setting and keeps every row in step with its stored value, so a setting
 * shown on a page and in search results agrees after a change made in either place.
 */
final class SettingRows implements Settings.OnChangeListener {
    private final ReseamBundle bundle;
    private final Ui ui;
    private final Map<String, List<Runnable>> refreshes = new HashMap<>();

    SettingRows(ReseamBundle bundle, Ui ui) {
        this.bundle = bundle;
        this.ui = ui;
    }

    void follow(View screen) {
        Settings settings = bundle.settings();
        screen.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                settings.addOnChangeListener(SettingRows.this);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                settings.removeOnChangeListener(SettingRows.this);
            }
        });
    }

    @Override
    public void onChanged(String key) {
        List<Runnable> rows = refreshes.get(key);
        if (rows == null) return;
        for (Runnable refresh : rows) refresh.run();
    }

    ListItem create(SettingsSchema.Setting setting) {
        ListItem item = ui.listItem(setting.title).icon(setting.icon);
        if (setting instanceof SettingsSchema.Toggle) toggle(item, (SettingsSchema.Toggle) setting);
        else if (setting instanceof SettingsSchema.Choice) choice(item, (SettingsSchema.Choice) setting);
        else if (setting instanceof SettingsSchema.Folder) folder(item, (SettingsSchema.Folder) setting);
        else if (setting instanceof SettingsSchema.Text) text(item, (SettingsSchema.Text) setting);
        else throw new IllegalArgumentException("Unknown setting type: " + setting.getClass().getName());
        return item;
    }

    private void onChange(String key, Runnable refresh) {
        refresh.run();
        refreshes.computeIfAbsent(key, k -> new ArrayList<>()).add(refresh);
    }

    private void toggle(ListItem item, SettingsSchema.Toggle toggle) {
        Settings settings = bundle.settings();
        SwitchView view = ui.switchView();
        view.setOnCheckedChangeListener((v, checked) -> settings.setBoolean(toggle.key, checked));
        item.supporting(toggle.summary).trailing(view).onClick(v -> view.toggle());
        onChange(toggle.key, () -> view.setChecked(settings.getBoolean(toggle.key, toggle.defaultValue)));
    }

    private void choice(ListItem item, SettingsSchema.Choice choice) {
        Settings settings = bundle.settings();
        onChange(choice.key, () -> item.supporting(choice.titles[selected(choice)]));
        item.onClick(v -> ui.dialog()
                .title(choice.title)
                .message(choice.summary)
                .choices(choice.titles, selected(choice), index -> settings.setString(choice.key, choice.values[index]))
                .dismissAction("Cancel")
                .show());
    }

    private int selected(SettingsSchema.Choice choice) {
        return choice.index(bundle.settings().getString(choice.key, choice.defaultValue));
    }

    private void text(ListItem item, SettingsSchema.Text text) {
        Settings settings = bundle.settings();
        onChange(text.key, () -> {
            String value = settings.getString(text.key, text.defaultValue);
            item.supporting(value.isEmpty() ? text.summary : value);
        });
        item.onClick(v -> {
            TextField field = ui.textField(text.title).text(settings.getString(text.key, text.defaultValue));
            if (text.multiline) field.multiline(3);
            field.action(Icons.RESET, "Restore the default", button -> field.text(text.defaultValue));
            field.showAction(!field.text().equals(text.defaultValue));
            field.field().addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    field.showAction(!s.toString().equals(text.defaultValue));
                }
            });
            ui.dialog()
                    .title(text.title)
                    .message(text.summary)
                    .view(field)
                    .dismissAction("Cancel")
                    .action("Save", () -> settings.setString(text.key, field.text()))
                    .show();
        });
    }

    private void folder(ListItem item, SettingsSchema.Folder folder) {
        Settings settings = bundle.settings();
        onChange(folder.key, () -> {
            String value = settings.getString(folder.key, folder.defaultValue);
            item.supporting(value.isEmpty() ? folder.summary : displayFolder(value));
        });
        item.onClick(v -> FolderPicker.pick(activity(ui.context), bundle.name, folder.key));
    }

    /** The folder's name for a document tree URI, which is all a user recognizes in it. */
    private static String displayFolder(String value) {
        if (!value.startsWith("content://")) return value;
        String last = Uri.parse(value).getLastPathSegment();
        if (last == null) return value;
        String name = last.substring(last.lastIndexOf(':') + 1);
        return name.isEmpty() ? value : name;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        throw new IllegalStateException("The settings are not shown over an activity");
    }
}
