// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.graphics.Color;

import org.json.JSONException;
import org.json.JSONObject;

public final class Colors {
    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    public final int secondaryContainer;
    public final int onSecondaryContainer;
    public final int surface;
    public final int surfaceContainerLow;
    public final int surfaceContainer;
    public final int surfaceContainerHigh;
    public final int surfaceContainerHighest;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int outline;
    public final int outlineVariant;
    public final int error;
    public final int onError;
    public final int errorContainer;
    public final int onErrorContainer;
    public final int scrim;

    Colors(JSONObject json) throws JSONException {
        primary = color(json, "primary");
        onPrimary = color(json, "onPrimary");
        primaryContainer = color(json, "primaryContainer");
        onPrimaryContainer = color(json, "onPrimaryContainer");
        secondaryContainer = color(json, "secondaryContainer");
        onSecondaryContainer = color(json, "onSecondaryContainer");
        surface = color(json, "surface");
        surfaceContainerLow = color(json, "surfaceContainerLow");
        surfaceContainer = color(json, "surfaceContainer");
        surfaceContainerHigh = color(json, "surfaceContainerHigh");
        surfaceContainerHighest = color(json, "surfaceContainerHighest");
        onSurface = color(json, "onSurface");
        onSurfaceVariant = color(json, "onSurfaceVariant");
        outline = color(json, "outline");
        outlineVariant = color(json, "outlineVariant");
        error = color(json, "error");
        onError = color(json, "onError");
        errorContainer = color(json, "errorContainer");
        onErrorContainer = color(json, "onErrorContainer");
        scrim = color(json, "scrim");
    }

    private static int color(JSONObject json, String role) throws JSONException {
        return Color.parseColor(json.getString(role));
    }

    public static int withOpacity(int color, float opacity) {
        return (color & 0x00FFFFFF) | (Math.round(Color.alpha(color) * opacity) << 24);
    }
}
