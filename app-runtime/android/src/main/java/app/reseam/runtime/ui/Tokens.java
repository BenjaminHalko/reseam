// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-License-Identifier: AGPL-3.0-or-later

package app.reseam.runtime.ui;

import android.content.Context;

public final class Tokens {
    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 16;
    public static final int SPACE_XXL = 24;
    public static final int SPACE_XXXL = 32;

    public static final int RADIUS_XS = 4;
    public static final int RADIUS_SM = 8;
    public static final int RADIUS_LG = 16;
    public static final int RADIUS_XL = 28;

    public static final int TOUCH_TARGET = 48;
    public static final int ICON_MD = 24;
    public static final int BUTTON = 40;
    public static final int TOP_BAR = 64;
    public static final int SEARCH_BAR = 56;
    public static final int LIST_ITEM = 64;
    public static final int LOGO = 80;
    public static final int DIALOG_MIN_WIDTH = 280;
    public static final int DIALOG_MAX_WIDTH = 560;

    public static final float DISABLED = 0.38f;
    public static final float PRESSED = 0.10f;
    public static final float SCRIM = 0.32f;

    private Tokens() {}

    public static int px(Context context, float dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
