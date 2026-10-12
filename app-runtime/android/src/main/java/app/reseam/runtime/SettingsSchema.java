// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import app.reseam.runtime.ui.VectorImage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class SettingsSchema {
    final String version;
    final String engine;
    final String license;
    final List<Page> pages;
    final List<Section> sections;

    SettingsSchema(JSONObject json) throws JSONException {
        version = optString(json.getJSONObject("bundle"), "version");
        engine = json.getString("engine");
        license = optString(json, "license");
        JSONArray pageArray = json.getJSONArray("pages");
        List<Page> pages = new ArrayList<>(pageArray.length());
        for (int i = 0; i < pageArray.length(); i++) pages.add(new Page(pageArray.getJSONObject(i)));
        this.pages = Collections.unmodifiableList(pages);
        JSONArray sectionArray = json.getJSONArray("sections");
        List<Section> sections = new ArrayList<>(sectionArray.length());
        for (int i = 0; i < sectionArray.length(); i++) sections.add(new Section(sectionArray.getJSONObject(i)));
        this.sections = Collections.unmodifiableList(sections);
    }

    Page page(String id) {
        for (Page page : pages) {
            if (page.id.equals(id)) return page;
        }
        throw new IllegalArgumentException("Unknown settings page: " + id);
    }

    String path(String id) {
        if (id.isEmpty()) return "";
        Page page = page(id);
        String parent = path(page.parent);
        return parent.isEmpty() ? page.title : parent + " › " + page.title;
    }

    Setting setting(String key) {
        for (Section section : sections) {
            for (Setting setting : section.settings) {
                if (setting.key.equals(key)) return setting;
            }
        }
        throw new IllegalArgumentException("Unknown setting: " + key);
    }

    private static String optString(JSONObject json, String key) throws JSONException {
        return json.isNull(key) ? null : json.getString(key);
    }

    /** {@code ""} is the root page. */
    static final class Page {
        final String id;
        final String title;
        final String summary;
        final VectorImage icon;
        final String parent;

        private Page(JSONObject json) throws JSONException {
            id = json.getString("id");
            title = json.getString("title");
            summary = optString(json, "summary");
            icon = VectorImage.read(json.optJSONObject("icon"));
            parent = json.isNull("parent") ? "" : json.getString("parent");
        }
    }

    static final class Section {
        final String page;
        final String title;
        final List<Setting> settings;

        private Section(JSONObject json) throws JSONException {
            page = json.isNull("page") ? "" : json.getString("page");
            title = json.getString("title");
            JSONArray array = json.getJSONArray("settings");
            List<Setting> settings = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) settings.add(Setting.parse(array.getJSONObject(i)));
            this.settings = Collections.unmodifiableList(settings);
        }
    }

    abstract static class Setting {
        final String key;
        final String title;
        final String summary;
        final VectorImage icon;

        private Setting(JSONObject json) throws JSONException {
            key = json.getString("key");
            title = json.getString("title");
            summary = optString(json, "summary");
            icon = VectorImage.read(json.optJSONObject("icon"));
        }

        private static Setting parse(JSONObject json) throws JSONException {
            String type = json.getString("type");
            switch (type) {
                case "toggle": return new Toggle(json);
                case "text": return new Text(json);
                case "folder": return new Folder(json);
                case "choice": return new Choice(json);
                default: throw new JSONException("Unknown setting type: " + type);
            }
        }
    }

    static final class Toggle extends Setting {
        final boolean defaultValue;

        private Toggle(JSONObject json) throws JSONException {
            super(json);
            defaultValue = json.getBoolean("default");
        }
    }

    static final class Text extends Setting {
        final String defaultValue;
        final boolean multiline;

        private Text(JSONObject json) throws JSONException {
            super(json);
            defaultValue = json.getString("default");
            multiline = json.getBoolean("multiline");
        }
    }

    static final class Folder extends Setting {
        final String defaultValue;

        private Folder(JSONObject json) throws JSONException {
            super(json);
            defaultValue = json.getString("default");
        }
    }

    static final class Choice extends Setting {
        final String defaultValue;
        final String[] values;
        final String[] titles;

        private Choice(JSONObject json) throws JSONException {
            super(json);
            defaultValue = json.getString("default");
            JSONArray choices = json.getJSONArray("choices");
            values = new String[choices.length()];
            titles = new String[choices.length()];
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                values[i] = choice.getString("value");
                titles[i] = choice.getString("title");
            }
        }

        /** A stored value the schema no longer lists, left by a patch that offered it, reads as the default. */
        int index(String stored) {
            int fallback = 0;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(stored)) return i;
                if (values[i].equals(defaultValue)) fallback = i;
            }
            return fallback;
        }
    }
}
