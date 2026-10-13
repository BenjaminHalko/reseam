// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArrayList

/** Stored settings isolated by bundle. Obtain them through Reseam.bundle(name).settings. */
class Settings
internal constructor(app: Context, bundle: String, private val schema: SettingsSchema) {
    private val prefs = app.getSharedPreferences("reseam.$bundle", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<OnChangeListener>()
    // Preferences hold listeners weakly; retain the dispatcher for the lifetime of these settings.
    private val dispatch = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        listeners.forEach { it.onChanged(key) }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(dispatch)
    }

    fun interface OnChangeListener {
        /** Null indicates that every value was cleared. */
        fun onChanged(key: String?)
    }

    /** Calls the listener on the main thread after each change until removed. */
    fun addOnChangeListener(listener: OnChangeListener) {
        listeners.add(listener)
    }

    fun removeOnChangeListener(listener: OnChangeListener) {
        listeners.remove(listener)
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    fun getString(key: String, defaultValue: String): String =
        checkNotNull(prefs.getString(key, defaultValue)) { "A non-null default always yields text" }

    /** Unlisted stored choices read as the schema default. */
    fun getChoice(key: String): String {
        val choice = schema.setting(key) as SettingsSchema.Choice
        return choice.options[choice.index(getString(key, choice.defaultValue))].value
    }

    fun setBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    fun setString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    internal fun snapshot(): Map<String, *> = prefs.all.toMap()
}
