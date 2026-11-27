package com.soulreturns.config.gui.render

import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chroma palette has two implementations, Kotlin's [Chroma.rainbow] (CPU paths) and the shaders' `chromaRgb`
 * (GPU paths). They must draw the same colors: the mock's stops, in order, blended linearly and seamless at the wrap.
 */
class ChromaTest {
    private val shaders = File("src/main/resources/assets/soul/shaders/core")

    /** The one `chromaRgb` every shader carries, comments included (a shader cannot import it during startup). */
    private val snippet =
        """
        // Chroma palette of mocks/scoreboard: FF5555 FFFF55 55FF55 55FFFF 5555FF FF55FF, linear between stops, wrapping.
        // Identical in every Soul shader and equal to Kotlin's Chroma.rainbow (ChromaTest checks both).
        vec3 chromaRgb(float h) {
            return 1.0 / 3.0 + 2.0 / 3.0 * clamp(abs(fract(h + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
        }
        """.trimIndent()

    /** [snippet]'s formula, evaluated in Kotlin as GLSL does (0..1 per channel). */
    private fun glsl(h: Float): FloatArray {
        fun fract(v: Float) = v - floor(v)
        val offsets = floatArrayOf(0f, 2f / 3f, 1f / 3f)
        return FloatArray(3) { c -> 1f / 3f + 2f / 3f * (abs(fract(h + offsets[c]) * 6f - 3f) - 1f).coerceIn(0f, 1f) }
    }

    private fun channels(argb: Int) = intArrayOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    @Test
    fun `stops are the mock's chat colors in its order`() {
        assertEquals(listOf(0xFF5555, 0xFFFF55, 0x55FF55, 0x55FFFF, 0x5555FF, 0xFF55FF), Chroma.STOPS.toList())
        for ((i, stop) in Chroma.STOPS.withIndex()) {
            assertEquals(0xFF000000.toInt() or stop, Chroma.rainbow(i / 6f), "stop $i")
            assertEquals(0xFF000000.toInt() or stop, Chroma.rainbow(i / 6f + 3f), "stop $i one field later")
        }
    }

    @Test
    fun `kotlin and shader palettes agree everywhere`() {
        for (k in -600..1200) {
            val h = k / 600f
            val cpu = channels(Chroma.rainbow(h))
            val gpu = glsl(h).map { (it * 255f).roundToInt() }
            for (c in 0..2) assertTrue(abs(cpu[c] - gpu[c]) <= 1, "h=$h channel $c: kotlin ${cpu[c]} shader ${gpu[c]}")
        }
    }

    @Test
    fun `the field is seamless at the wrap`() {
        val before = channels(Chroma.rainbow(0.9999f))
        val after = channels(Chroma.rainbow(0f))
        for (c in 0..2) assertTrue(abs(before[c] - after[c]) <= 1, "channel $c: ${before[c]} vs ${after[c]}")
    }

    @Test
    fun `every shader with chroma carries the same palette function`() {
        val files = shaders.listFiles { f -> f.name.endsWith(".fsh") }!!.filter { "chromaRgb" in it.readText() }
        assertEquals(
            setOf("chroma_gui.fsh", "chroma_textured.fsh", "msdf_text.fsh", "panel.fsh", "pet_glow.fsh", "round_rect.fsh"),
            files.map { it.name }.toSet(),
        )
        for (f in files) assertTrue(snippet in f.readText(), "${f.name} has a different chromaRgb")
    }
}
