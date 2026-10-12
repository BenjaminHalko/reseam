// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.widget.TextView;

public final class Theme {
    public final Brand brand;
    public final boolean dark;
    public final Colors colors;
    public final Typography type;

    private Theme(Brand brand, boolean dark) {
        this.brand = brand;
        this.dark = dark;
        colors = dark ? brand.dark : brand.light;
        type = brand.typography;
    }

    /**
     * The brand in the system theme. Apps can force their own night mode on their activities, so
     * the system's configuration decides, not the host's.
     */
    public static Theme of(Brand brand) {
        int night = Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return new Theme(brand, night == Configuration.UI_MODE_NIGHT_YES);
    }

    /** The brand's logo for this theme, or {@code null} when it has none. */
    public VectorImage logo() {
        return dark ? brand.darkLogo : brand.lightLogo;
    }

    public Typeface typeface(int weight) {
        return brand.typeface(weight);
    }

    public void style(TextView view, TypeStyle style) {
        style.apply(view, typeface(style.weight));
    }
}
