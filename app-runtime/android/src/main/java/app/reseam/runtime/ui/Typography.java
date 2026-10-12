// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import org.json.JSONException;
import org.json.JSONObject;

public final class Typography {
    public final TypeStyle headlineSmall;
    public final TypeStyle titleLarge;
    public final TypeStyle titleMedium;
    public final TypeStyle bodyLarge;
    public final TypeStyle bodyMedium;
    public final TypeStyle labelLarge;
    public final TypeStyle labelMedium;

    Typography(JSONObject json) throws JSONException {
        headlineSmall = new TypeStyle(json.getJSONObject("headlineSmall"));
        titleLarge = new TypeStyle(json.getJSONObject("titleLarge"));
        titleMedium = new TypeStyle(json.getJSONObject("titleMedium"));
        bodyLarge = new TypeStyle(json.getJSONObject("bodyLarge"));
        bodyMedium = new TypeStyle(json.getJSONObject("bodyMedium"));
        labelLarge = new TypeStyle(json.getJSONObject("labelLarge"));
        labelMedium = new TypeStyle(json.getJSONObject("labelMedium"));
    }
}
