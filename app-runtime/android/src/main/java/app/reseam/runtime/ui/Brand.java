// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.os.Build;
import android.util.SparseArray;

import org.json.JSONException;
import org.json.JSONObject;

public final class Brand {
    public final String name;
    final Colors light;
    final Colors dark;
    final Typography typography;
    final VectorImage lightLogo;
    final VectorImage darkLogo;
    private final AssetManager assets;
    private final String font;
    private final SparseArray<Typeface> typefaces = new SparseArray<>();

    public Brand(JSONObject json, AssetManager assets, String directory) throws JSONException {
        name = json.getString("name");
        light = new Colors(json.getJSONObject("light"));
        dark = new Colors(json.getJSONObject("dark"));
        typography = new Typography(json.getJSONObject("typography"));
        JSONObject logo = json.optJSONObject("logo");
        lightLogo = logo == null ? null : VectorImage.read(logo.getJSONObject("light"));
        darkLogo = logo == null ? null : VectorImage.read(logo.getJSONObject("dark"));
        this.assets = assets;
        font = json.isNull("font") ? null : directory + "/" + json.getString("font");
    }

    synchronized Typeface typeface(int weight) {
        Typeface typeface = typefaces.get(weight);
        if (typeface == null) {
            typeface = font != null
                    ? new Typeface.Builder(assets, font).setFontVariationSettings("'wght' " + weight).build()
                    : systemTypeface(weight);
            typefaces.put(weight, typeface);
        }
        return typeface;
    }

    private static Typeface systemTypeface(int weight) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Typeface.create(Typeface.DEFAULT, weight, false);
        // Before API 28 the system font comes in regular, medium and bold only.
        if (weight >= 600) return Typeface.DEFAULT_BOLD;
        if (weight >= 500) return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return Typeface.DEFAULT;
    }
}
