package com.soulreturns.config.gui.render

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Soul text shadow: in proportion to the drawn text, at least 1 px. */
class TextShadowTest {
    private fun near(want: Float, got: Float, what: String) = assertTrue(abs(got - want) < 1e-3f, "$what: $got, want $want")

    @Test
    fun `semibold body text gets 1,5 px per Soul scale`() {
        for (soul in listOf(1f, 1.3333f, 2f, 3f)) near(1.5f * soul, TextShadow.physicalPx(14f * soul), "Soul scale $soul")
    }

    @Test
    fun `the shadow keeps its proportion to the letters at every size`() {
        for (px in listOf(12f, 28f, 56f, 112f)) near(1.5f / 14f, TextShadow.physicalPx(px) / px, "$px px letters")
        near(1f, TextShadow.physicalPx(2f), "never under one pixel")
    }

    @Test
    fun `regular weight gets a shorter shadow than semibold`() {
        near(3f, TextShadow.physicalPx(28f, semibold = true), "semibold body text")
        near(1.95f, TextShadow.physicalPx(28f, semibold = false), "regular body text")
    }

    @Test
    fun `Minecraft's GUI scale does not change the shadow on screen`() {
        for (soul in listOf(1f, 1.3333f, 2f, 3f)) {
            for (hud in listOf(0.4f, 1f, 3f)) {
                val want = TextShadow.physicalPx(14f * soul * hud)
                for (gui in listOf(2f, 3f, 5f)) {
                    val poseScale = soul / gui * hud
                    val physical = TextShadow.poseOffset(14f, poseScale, gui) * poseScale * gui
                    assertTrue(abs(physical - want) < 1e-3f, "soul $soul hud $hud gui $gui: $physical px, want $want")
                }
            }
        }
    }

    @Test
    fun `the shadow is black at 70 percent of the text's alpha`() {
        assertEquals(0xB2000000.toInt(), TextShadow.color(0xFFFFFFFF.toInt()))
        assertEquals(0x59000000, TextShadow.color(0x80FFFFFF.toInt()))
    }
}
