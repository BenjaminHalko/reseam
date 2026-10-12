// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A bundle's stored settings. Each bundle keeps its own, so keys only need to be unique within
 * the bundle. Get them from {@code Reseam.bundle(name).settings()}.
 */
public final class Settings {
    private final SharedPreferences prefs;
    private final SettingsSchema schema;
    private final List<OnChangeListener> listeners = new CopyOnWriteArrayList<>();
    // Preferences hold their listeners weakly; this field keeps the one that fans out alive.
    private final SharedPreferences.OnSharedPreferenceChangeListener dispatch = (prefs, key) -> {
        for (OnChangeListener listener : listeners) listener.onChanged(key);
    };

    Settings(Context app, String bundle, SettingsSchema schema) {
        prefs = app.getSharedPreferences("reseam." + bundle, Context.MODE_PRIVATE);
        this.schema = schema;
        prefs.registerOnSharedPreferenceChangeListener(dispatch);
    }

    public interface OnChangeListener {
        /** {@code key} changed; it is {@code null} when every value was cleared. */
        void onChanged(String key);
    }

    /** Calls {@code listener} on the main thread after each change, until it is removed. */
    public void addOnChangeListener(OnChangeListener listener) {
        listeners.add(listener);
    }

    public void removeOnChangeListener(OnChangeListener listener) {
        listeners.remove(listener);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return prefs.getBoolean(key, defaultValue);
    }

    public String getString(String key, String defaultValue) {
        return prefs.getString(key, defaultValue);
    }

    /** The stored value of a choice setting if its schema lists it, and the schema default otherwise. */
    public String getChoice(String key) {
        SettingsSchema.Choice choice = (SettingsSchema.Choice) schema.setting(key);
        return choice.values[choice.index(getString(key, choice.defaultValue))];
    }

    public void setBoolean(String key, boolean value) {
        prefs.edit().putBoolean(key, value).apply();
    }

    public void setString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    Map<String, ?> snapshot() {
        return new HashMap<>(prefs.getAll());
    }
}
