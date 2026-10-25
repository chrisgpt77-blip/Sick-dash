package com.sickdash.ui

import android.graphics.Color

/** A colour scheme for the HUD. [primary] is the wireframe / live colour, [secondary] the highlight. */
class Theme(
    val key: String,
    val name: String,
    val primary: Int,
    val secondary: Int,
    val bg: Int = Color.rgb(8, 8, 10),
    val panel: Int = Color.rgb(17, 17, 21),
    val line: Int = Color.rgb(44, 44, 51),
    val text: Int = Color.rgb(237, 237, 241),
    val dim: Int = Color.rgb(150, 150, 160),
    val faint: Int = Color.rgb(72, 72, 80),
    val grid: Int = Color.rgb(200, 200, 210),
) {
    companion object {
        val ALL = listOf(
            Theme("akasaka", "Akasaka", Color.rgb(255, 36, 72), Color.rgb(242, 169, 59)),
            Theme("gazoo", "Gazoo", Color.rgb(235, 10, 30), Color.rgb(255, 255, 255)),
            Theme("wrblue", "WR Blue", Color.rgb(47, 123, 255), Color.rgb(242, 194, 48)),
            Theme("cyberpunk", "Cyberpunk", Color.rgb(252, 238, 10), Color.rgb(0, 240, 255)),
            Theme("midnight", "Midnight", Color.rgb(140, 110, 255), Color.rgb(255, 106, 213)),
            Theme("matrix", "Matrix", Color.rgb(57, 255, 106), Color.rgb(182, 255, 0)),
            Theme("sunset", "Sunset", Color.rgb(255, 122, 61), Color.rgb(255, 61, 139)),
            Theme("ice", "Ice", Color.rgb(79, 216, 255), Color.rgb(184, 242, 255)),
            Theme(
                "daylight", "Daylight", Color.rgb(215, 38, 61), Color.rgb(214, 128, 0),
                bg = Color.rgb(230, 231, 235), panel = Color.rgb(244, 244, 247), line = Color.rgb(196, 197, 204),
                text = Color.rgb(20, 20, 26), dim = Color.rgb(90, 90, 100), faint = Color.rgb(170, 170, 178),
                grid = Color.rgb(120, 120, 130),
            ),
        )

        fun of(key: String?) = ALL.firstOrNull { it.key == key } ?: ALL[0]
    }
}
