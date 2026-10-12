// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.react;

import app.reseam.runtime.Reseam;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

public final class ReseamSettingsModule extends ReactContextBaseJavaModule {
    static final String NAME = "ReseamSettings";

    ReseamSettingsModule(ReactApplicationContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @ReactMethod(isBlockingSynchronousMethod = true)
    public boolean isEnabled(String bundle, String key, boolean defaultValue) {
        return Reseam.isEnabled(bundle, key, defaultValue);
    }

    @ReactMethod
    public void openSettings(String bundle) {
        Reseam.openSettings(bundle);
    }
}
