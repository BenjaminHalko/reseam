// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.graphics.Typeface;
import android.os.Build;
import android.util.TypedValue;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

/** One Material 3 type role: size and line height in sp, a weight, and tracking in sp. */
public final class TypeStyle {
    public final float size;
    public final float lineHeight;
    public final int weight;
    public final float tracking;

    TypeStyle(JSONObject json) throws JSONException {
        size = (float) json.getDouble("size");
        lineHeight = (float) json.getDouble("lineHeight");
        weight = json.getInt("weight");
        tracking = (float) json.getDouble("tracking");
    }

    void apply(TextView view, Typeface typeface) {
        view.setTypeface(typeface);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setLetterSpacing(tracking / size);
        view.setIncludeFontPadding(false);
        int lineHeightPx = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, lineHeight, view.getResources().getDisplayMetrics()));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setLineHeight(lineHeightPx);
        } else {
            view.setLineSpacing(lineHeightPx - view.getPaint().getFontMetricsInt(null), 1f);
        }
    }
}
