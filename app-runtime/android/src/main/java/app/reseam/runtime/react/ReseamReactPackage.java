// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.react;

import com.facebook.react.BaseReactPackage;
import com.facebook.react.ReactPackage;
import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.module.model.ReactModuleInfo;
import com.facebook.react.module.model.ReactModuleInfoProvider;

import java.util.Collections;
import java.util.List;

public final class ReseamReactPackage extends BaseReactPackage {
    /** Adds the package to the app's package list, once however many bundles call this. */
    public static void addTo(List<ReactPackage> packages) {
        for (ReactPackage existing : packages) {
            if (existing instanceof ReseamReactPackage) return;
        }
        packages.add(new ReseamReactPackage());
    }

    @Override
    public NativeModule getModule(String name, ReactApplicationContext context) {
        return ReseamSettingsModule.NAME.equals(name) ? new ReseamSettingsModule(context) : null;
    }

    @Override
    public ReactModuleInfoProvider getReactModuleInfoProvider() {
        ReactModuleInfo info = new ReactModuleInfo(ReseamSettingsModule.NAME,
                ReseamSettingsModule.class.getName(), false, false, false, false);
        return () -> Collections.singletonMap(ReseamSettingsModule.NAME, info);
    }
}
