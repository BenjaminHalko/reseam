// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-FileCopyrightText: 2026 Lucide Icons and Contributors
// SPDX-License-Identifier: AGPL-3.0-or-later AND ISC

package app.reseam.runtime.ui;

public final class Icons {
    public static final VectorImage ARROW_LEFT = VectorImage.stroked("M 12 19 L 5 12 L 12 5", "M 19 12 L 5 12");
    public static final VectorImage CHEVRON_RIGHT = VectorImage.stroked("M 9 18 L 15 12 L 9 6");
    public static final VectorImage SEARCH = VectorImage.stroked("M 21 21 L 16.66 16.66",
            "M 3 11 C 3 15.418 6.582 19 11 19 C 15.418 19 19 15.418 19 11 C 19 6.582 15.418 3 11 3 "
                    + "C 6.582 3 3 6.582 3 11 Z");
    public static final VectorImage CLOSE = VectorImage.stroked("M 18 6 L 6 18", "M 6 6 L 18 18");
    public static final VectorImage CHECK = VectorImage.stroked("M 20 6 L 9 17 L 4 12");
    public static final VectorImage RESET = VectorImage.stroked(
            "M 3 12 C 3 16.971 7.029 21 12 21 C 16.971 21 21 16.971 21 12 C 21 7.029 16.971 3 12 3 "
                    + "C 9.484 3.009 7.069 3.991 5.26 5.74 L 3 8",
            "M 3 3 L 3 8 L 8 8");

    private Icons() {}
}
