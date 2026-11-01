package com.example.cet6.design

/**
 * The 朱批 design tokens shared by the phone and the Windows app: ARGB colours as Long (pass to `Color(Long)`),
 * and SVG path data for the brand icons. Ink on paper, with vermilion reserved for corrections and brand marks.
 */
object ZhupiTokens {
    /** One theme's surface and text colours. */
    data class Palette(
        val ink: Long,
        val onInk: Long,
        val ground: Long,
        val sheet: Long,
        val chip: Long,
        val line: Long,
        val raised: Long,
        val text: Long,
        val textSecondary: Long,
        val textMuted: Long,
        val must: Long,
        val mustSoft: Long,
        val better: Long,
        val betterSoft: Long,
        val good: Long,
        val goodSoft: Long,
        val brand: Long,
    )

    val Light = Palette(
        ink = 0xFF16181D,
        onInk = 0xFFF5F1E8,
        ground = 0xFFF5F4F1,
        sheet = 0xFFFFFFFF,
        chip = 0xFFECEAE4,
        line = 0xFFDEDBD3,
        raised = 0xFFF0EEE9,
        text = 0xFF16181D,
        textSecondary = 0xFF5F626A,
        textMuted = 0xFF8A8D94,
        must = 0xFFC0351E,
        mustSoft = 0xFFFCEBE7,
        better = 0xFFA35F00,
        betterSoft = 0xFFFFF3E2,
        good = 0xFF1F5F99,
        goodSoft = 0xFFE6EEF6,
        brand = 0xFFE0452B,
    )

    val Dark = Palette(
        ink = 0xFFF5F1E8,
        onInk = 0xFF16181D,
        ground = 0xFF111214,
        sheet = 0xFF1B1D21,
        chip = 0xFF2E3036,
        line = 0xFF34363C,
        raised = 0xFF24262B,
        text = 0xFFF5F1E8,
        textSecondary = 0xFFA9AAB0,
        textMuted = 0xFF6E7077,
        must = 0xFFFF8A73,
        mustSoft = 0xFF3D1C16,
        better = 0xFFF0B45A,
        betterSoft = 0xFF392A14,
        good = 0xFF8DBBEA,
        goodSoft = 0xFF17283A,
        brand = 0xFFFF6A4F,
    )

    /** 24-unit line icons for the four sections; the accent path is the stroke that turns vermilion when current. */
    object Icons {
        const val TODAY_RING = "M3.5,12a8.5,8.5 0 1,0 17,0a8.5,8.5 0 1,0 -17,0"
        const val TODAY_DOT = "M9.4,12a2.6,2.6 0 1,0 5.2,0a2.6,2.6 0 1,0 -5.2,0"
        const val PRACTICE_PEN = "M4.5,19.5l1.1,-4.4L15.4,5.3a2,2 0 0,1 2.9,0l0.4,0.4a2,2 0 0,1 0,2.9L8.9,18.4z"
        const val PRACTICE_NIB = "M13.6,7.1l3.3,3.3"
        const val PRACTICE_ACCENT = "M12.5,20h7"
        const val REVIEW_PAGE = "M9.2,3.5h8.1a2.2,2.2 0 0,1 2.2,2.2v10.6a2.2,2.2 0 0,1 -2.2,2.2h-8.1a2.2,2.2 0 0,1 -2.2,-2.2v-10.6a2.2,2.2 0 0,1 2.2,-2.2z"
        const val REVIEW_BACK = "M4.5,7.5v11a2.5,2.5 0 0,0 2.5,2.5h8.5"
        const val REVIEW_ACCENT = "M10.5,9h5.5"
        const val ME_HEAD = "M8.2,8.5a3.8,3.8 0 1,0 7.6,0a3.8,3.8 0 1,0 -7.6,0"
        const val ME_BODY = "M4.8,20c1.2,-3.6 3.9,-5.4 7.2,-5.4s6,1.8 7.2,5.4"
        const val STROKE_WIDTH = 1.9f
    }

    /** The logo's brush ring in its 1024-unit artwork, open where the stroke overlaps itself. */
    object Ring {
        const val PATH = "M196,572 C172,470 230,360 327,300 C400,262 460,250 512,250 C697,250 847,375 847,530 " +
            "C847,685 697,810 512,810 C327,810 177,685 177,530 C172,440 225,340 311,288"
        const val ROTATION = -12f
        const val PIVOT_X = 512f
        const val PIVOT_Y = 530f
        /** The ring's bounds within the artwork, for drawing it alone. */
        const val LEFT = 150f
        const val TOP = 220f
        const val WIDTH = 724f
        const val HEIGHT = 620f
    }
}
