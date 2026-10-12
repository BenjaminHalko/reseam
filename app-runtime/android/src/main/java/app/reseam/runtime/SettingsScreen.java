// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.reseam.runtime.ui.ItemGroup;
import app.reseam.runtime.ui.SearchBar;
import app.reseam.runtime.ui.Tokens;
import app.reseam.runtime.ui.Ui;

import java.util.function.Consumer;

final class SettingsScreen {
    private static final String ROOT = "";

    private SettingsScreen() {}

    static View page(ReseamBundle bundle, Ui ui, String pageId, Runnable back, Consumer<String> open) {
        SettingsSchema schema = bundle.schema;
        boolean root = pageId.equals(ROOT);
        LinearLayout container = vertical(ui);
        container.setBackgroundColor(ui.colors().surface);
        // Hosts targeting API 35 draw edge to edge; keep the bar below the status bar.
        container.setFitsSystemWindows(true);
        container.addView(ui.topBar(root ? "Settings" : schema.page(pageId).title, back), matchWidth());

        LinearLayout body = vertical(ui);
        int margin = ui.px(Tokens.SPACE_LG);
        body.setPadding(margin, 0, margin, ui.px(Tokens.SPACE_XXL));
        ScrollView scroll = new ScrollView(ui.context);
        scroll.addView(body);
        container.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        SettingRows rows = new SettingRows(bundle, ui);
        rows.follow(container);
        LinearLayout content = vertical(ui);
        if (root) content.addView(AboutCard.create(ui, bundle), matchWidth());

        ItemGroup pages = ui.itemGroup();
        for (SettingsSchema.Page page : schema.pages) {
            if (!page.parent.equals(pageId)) continue;
            pages.add(ui.listItem(page.title)
                    .icon(page.icon)
                    .supporting(page.summary)
                    .chevron()
                    .onClick(v -> open.accept(page.id)));
        }
        if (pages.getChildCount() > 0) {
            LinearLayout.LayoutParams params = matchWidth();
            params.topMargin = ui.px(Tokens.SPACE_LG);
            content.addView(pages, params);
        }

        boolean empty = pages.getChildCount() == 0;
        for (SettingsSchema.Section section : schema.sections) {
            if (!section.page.equals(pageId) || section.settings.isEmpty()) continue;
            content.addView(ui.sectionLabel(section.title));
            ItemGroup group = ui.itemGroup();
            for (SettingsSchema.Setting setting : section.settings) group.add(rows.create(setting));
            content.addView(group, matchWidth());
            empty = false;
        }
        if (empty) content.addView(message(ui, "The patches you chose have no settings here."));

        if (root) {
            SearchResults results = new SearchResults(ui, schema, rows, content);
            SearchBar search = ui.searchBar("Search settings");
            search.onQueryChange(query -> {
                results.show(query);
                scroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams params = matchWidth();
            params.topMargin = ui.px(Tokens.SPACE_XS);
            params.bottomMargin = ui.px(Tokens.SPACE_LG);
            body.addView(search, params);
            body.addView(results.view(), matchWidth());
        }
        body.addView(content, matchWidth());
        return container;
    }

    static TextView message(Ui ui, String text) {
        TextView view = ui.text(text, ui.type().bodyMedium, ui.colors().onSurfaceVariant);
        view.setPadding(0, ui.px(Tokens.SPACE_LG), 0, ui.px(Tokens.SPACE_LG));
        return view;
    }

    private static LinearLayout vertical(Ui ui) {
        LinearLayout layout = new LinearLayout(ui.context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
