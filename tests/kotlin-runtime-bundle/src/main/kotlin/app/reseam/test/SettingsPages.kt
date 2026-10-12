// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.test

import app.reseam.patch.appEntry
import app.reseam.patch.patch
import app.reseam.patch.settings.SettingsPage
import app.reseam.patch.settings.TextSetting
import app.reseam.patch.settings.ToggleSetting
import app.reseam.patch.settings.Vector
import app.reseam.patch.settings.section
import app.reseam.patch.settings.settingsHost
import app.reseam.patch.settings.whenEnabled

private val chevron =
    Vector.svg(
        """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
            stroke-linejoin="round"><path d="m9 18 6-6-6-6"/></svg>"""
    )

private val dot =
    Vector.svg(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="10 10 4 4">
            <circle cx="12" cy="12" r="2" fill="#f00"/></svg>"""
    )

private val navigationRoot = SettingsPage("media", "Media", order = 10, icon = chevron)
private val navigationChild = SettingsPage("quality", "Quality", navigationRoot, order = 11)
private val navigationOther = SettingsPage("general", "General", order = 0)
private val navigationEmpty = SettingsPage("empty", "Empty")
private val navigationFirst = ToggleSetting("quality.first", "First", default = true)
private val navigationSecond = ToggleSetting("quality.second", "Second", default = false)

val navigationSettings =
    settingsHost("navigation") {
        compatibleWith("com.example.test")
        settings(
            section(
                "Root",
                ToggleSetting("root.enabled", "Root", default = false, icon = dot),
                TextSetting("root.lines", "Lines", default = "", multiline = true),
            )
        )
    }

val navigationFirstPatch =
    patch("Settings navigation first") {
        compatibleWith("com.example.test")
        enabledByDefault(false)
        settings(
            navigationSettings,
            section(navigationChild, "Quality", navigationFirst),
            section(navigationEmpty, "Empty"),
        )
    }

val navigationSecondPatch =
    patch("Settings navigation second") {
        compatibleWith("com.example.test")
        enabledByDefault(false)
        dependsOn(navigationFirstPatch)
        settings(
            navigationSettings,
            section(navigationChild, "Quality", navigationFirst, navigationSecond),
            section(
                navigationOther,
                "General",
                ToggleSetting("general.enabled", "General", default = true),
            ),
        )
    }

private val gateToggle = ToggleSetting("gate.enabled", "Gate", default = true)

val gatedEntry =
    patch("Settings gated entry") {
        compatibleWith("com.example.test")
        enabledByDefault(false)
        settings(navigationSettings, section("Gate", gateToggle))
        execute {
            appEntry {
                whenEnabled(gateToggle) {
                    callStatic(
                        "com.example.Observer",
                        "started",
                        "(Landroid/app/Application;)V",
                        application,
                    )
                }
            }
        }
    }
