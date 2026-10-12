// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

plugins {
    id("app.reseam.extension")
    `maven-publish`
}

dependencies { compileOnly("com.facebook.react:react-android:0.86.0") }

publishing { publications { create<MavenPublication>("maven") { from(components["java"]) } } }
