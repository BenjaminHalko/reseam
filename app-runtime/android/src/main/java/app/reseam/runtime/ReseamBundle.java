// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime;

import android.content.Context;

import app.reseam.runtime.ui.Brand;
import app.reseam.runtime.ui.Theme;
import app.reseam.runtime.ui.Ui;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class ReseamBundle {
    public final String name;
    final SettingsSchema schema;
    final Brand brand;
    private final Settings settings;

    ReseamBundle(Context app, String name) {
        this.name = name;
        String directory = "reseam/" + name;
        try {
            JSONObject json = new JSONObject(read(app, directory + "/settings.json"));
            schema = new SettingsSchema(json);
            brand = new Brand(json.getJSONObject("brand"), app.getAssets(), directory);
        } catch (IOException | JSONException e) {
            throw new IllegalArgumentException("The app has no readable settings for bundle " + name, e);
        }
        settings = new Settings(app, name, schema);
    }

    public Settings settings() {
        return settings;
    }

    /** Builds views in the bundle's brand, in the system's current light or dark theme. */
    public Ui ui(Context context) {
        return new Ui(context, Theme.of(brand));
    }

    /** Opens the bundle's settings over the app's current screen. Callable from any thread. */
    public void openSettings() {
        SettingsPanel.open(this);
    }

    private static String read(Context app, String path) throws IOException {
        try (InputStream in = app.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        }
    }
}
