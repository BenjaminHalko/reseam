// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The app runtime the engine adds to patched apps. Each bundle's settings host calls {@link #init}
 * at app start; extensions reach their bundle through {@link #bundle}.
 */
public final class Reseam {
    private static final String OPEN_ACTION = "app.reseam.settings.OPEN";
    private static final String SCHEME = "reseam-settings";
    private static final Map<String, ReseamBundle> bundles = new ConcurrentHashMap<>();
    private static volatile Context app;

    private Reseam() {}

    /** Called by the engine first in the app's {@code Application.onCreate}; later calls do nothing. */
    public static synchronized void init(Context context) {
        if (app != null) return;
        Context application = context.getApplicationContext();
        app = application != null ? application : context;
        if (app instanceof Application) SettingsPanel.track((Application) app);
    }

    /** Whether {@link #init} has run; until then, settings read as their defaults. */
    public static boolean isStarted() {
        return app != null;
    }

    /** The bundle {@code name} as the app knows it. Fails when the app has no settings for it. */
    public static ReseamBundle bundle(String name) {
        Context context = app;
        if (context == null) throw new IllegalStateException("Reseam.init has not run");
        return bundles.computeIfAbsent(name, bundle -> new ReseamBundle(context, bundle));
    }

    /**
     * Whether the bundle's toggle is on, for code the engine emits. Code that runs before the app's
     * {@code Application.onCreate}, such as a content provider, reads the default.
     */
    public static boolean isEnabled(String bundle, String key, boolean defaultValue) {
        return app == null ? defaultValue : bundle(bundle).settings().getBoolean(key, defaultValue);
    }

    public static String getString(String bundle, String key, String defaultValue) {
        return app == null ? defaultValue : bundle(bundle).settings().getString(key, defaultValue);
    }

    public static String getChoice(String bundle, String key, String defaultValue) {
        return app == null ? defaultValue : bundle(bundle).settings().getChoice(key);
    }

    public static void openSettings(String bundle) {
        bundle(bundle).openSettings();
    }

    /** Starts {@code intent}, or opens a bundle's settings when the patch SDK set it up to. */
    public static void startActivity(Context context, Intent intent) {
        Uri data = intent.getData();
        if (OPEN_ACTION.equals(intent.getAction()) && data != null && SCHEME.equals(data.getScheme())) {
            openSettings(data.getSchemeSpecificPart());
        } else {
            context.startActivity(intent);
        }
    }
}
