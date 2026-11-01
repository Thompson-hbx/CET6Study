package com.example.cet6.design

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ZhupiTokensTest {
    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun textTonesReadOnGroundSheetAndTheirOwnSoftBackground() {
        for (p in listOf(ZhupiTokens.Light, ZhupiTokens.Dark)) {
            for (text in listOf(p.text, p.textSecondary, p.must, p.better, p.good)) {
                for (surface in listOf(p.ground, p.sheet)) {
                    assertTrue("contrast ${contrast(text, surface)}", contrast(text, surface) >= 4.5)
                }
            }
            assertTrue(contrast(p.must, p.mustSoft) >= 4.5)
            assertTrue(contrast(p.better, p.betterSoft) >= 4.5)
            assertTrue(contrast(p.good, p.goodSoft) >= 4.5)
            assertTrue(contrast(p.onInk, p.ink) >= 4.5)
        }
    }
}
