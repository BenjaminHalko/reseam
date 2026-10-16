// SPDX-FileCopyrightText: 2026 Cossale <hello@auna.li>
// SPDX-FileCopyrightText: 2026 Lucide Icons and Contributors
// SPDX-License-Identifier: AGPL-3.0-or-later AND ISC

package app.reseam.runtime.ui

object Icons {
    @JvmField val ARROW_LEFT = VectorImage.stroked("M 12 19 L 5 12 L 12 5", "M 19 12 L 5 12")
    @JvmField val CHEVRON_RIGHT = VectorImage.stroked("M 9 18 L 15 12 L 9 6")
    @JvmField
    val SEARCH =
        VectorImage.stroked(
            "M 21 21 L 16.66 16.66",
            "M 3 11 C 3 15.418 6.582 19 11 19 C 15.418 19 19 15.418 19 11 C 19 6.582 15.418 3 11 3 " +
                "C 6.582 3 3 6.582 3 11 Z",
        )
    @JvmField val CLOSE = VectorImage.stroked("M 18 6 L 6 18", "M 6 6 L 18 18")
    @JvmField val CHECK = VectorImage.stroked("M 20 6 L 9 17 L 4 12")
    @JvmField
    val RESET =
        VectorImage.stroked(
            "M 3 12 C 3 16.971 7.029 21 12 21 C 16.971 21 21 16.971 21 12 C 21 7.029 16.971 3 12 3 " +
                "C 9.484 3.009 7.069 3.991 5.26 5.74 L 3 8",
            "M 3 3 L 3 8 L 8 8",
        )
    @JvmField
    val UPLOAD =
        VectorImage.stroked(
            TRAY,
            "M 17 8 L 12 3 L 7 8",
            "M 12 3 L 12 15",
        )
    @JvmField
    val DOWNLOAD =
        VectorImage.stroked(
            TRAY,
            "M 7 10 L 12 15 L 17 10",
            "M 12 15 L 12 3",
        )
}

private const val TRAY =
    "M 21 15 L 21 19 C 21 20.105 20.105 21 19 21 L 5 21 C 3.895 21 3 20.105 3 19 L 3 15"
