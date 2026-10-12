// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import app.reseam.runtime.ui.ItemGroup;
import app.reseam.runtime.ui.ListItem;
import app.reseam.runtime.ui.Ui;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class SearchResults {
    private final Ui ui;
    private final SettingsSchema schema;
    private final SettingRows rows;
    private final View page;
    private final LinearLayout view;
    private final View empty;
    private final Map<SettingsSchema.Section, View> labels = new HashMap<>();
    private final Map<String, ListItem> items = new HashMap<>();

    SearchResults(Ui ui, SettingsSchema schema, SettingRows rows, View page) {
        this.ui = ui;
        this.schema = schema;
        this.rows = rows;
        this.page = page;
        view = new LinearLayout(ui.context);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setVisibility(View.GONE);
        empty = SettingsScreen.message(ui, "No settings match your search.");
    }

    View view() {
        return view;
    }

    void show(String query) {
        String[] words = words(query);
        boolean searching = words.length > 0;
        page.setVisibility(searching ? View.GONE : View.VISIBLE);
        view.setVisibility(searching ? View.VISIBLE : View.GONE);
        view.removeAllViews();
        if (!searching) return;
        for (SettingsSchema.Section section : schema.sections) {
            ItemGroup group = null;
            for (SettingsSchema.Setting setting : section.settings) {
                if (!matches(setting, words)) continue;
                if (group == null) {
                    view.addView(labels.computeIfAbsent(section, this::label));
                    group = ui.itemGroup();
                    view.addView(group);
                }
                ListItem item = items.computeIfAbsent(setting.key, key -> rows.create(setting));
                // Items are reused across queries; the previous query's group still holds them.
                if (item.getParent() != null) ((ViewGroup) item.getParent()).removeView(item);
                group.add(item);
            }
        }
        if (view.getChildCount() == 0) view.addView(empty);
    }

    private View label(SettingsSchema.Section section) {
        String path = schema.path(section.page);
        return ui.sectionLabel(path.isEmpty() ? section.title : path + " › " + section.title);
    }

    private static String[] words(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
    }

    private static boolean matches(SettingsSchema.Setting setting, String[] words) {
        String text = (setting.summary == null ? setting.title : setting.title + "\n" + setting.summary)
                .toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (!text.contains(word)) return false;
        }
        return true;
    }
}
