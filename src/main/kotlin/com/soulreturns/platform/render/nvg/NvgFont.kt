// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License.
// Original backend by Aton; design by Stivais.
package com.soulreturns.platform.render.nvg

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Memory-backed TTF font handle for [NvgRenderer].
 *
 * NanoVG's `nvgCreateFontMem` requires the TTF bytes to remain reachable for the lifetime of
 * the font face — if the buffer is GC'd while NanoVG holds a pointer into it, the JVM crashes
 * (segfault, native side). We cache the TTF bytes on construction and expose a fresh direct
 * [ByteBuffer] via [buffer] so a strong reference is held in the [NVGFont] cache by
 * [NvgRenderer].
 *
 * Construct one [NvgFont] per logical face. Multiple [NvgFont] instances pointing at the same
 * TTF resource are allowed; they each hold their own copy.
 *
 * @property name Logical font name used as the NanoVG identifier. Pick distinct values across
 *   fonts in the same NVG context — `nvgFontFace(vg, name)` selects by this string.
 */
class NvgFont(val name: String, private val cachedBytes: ByteArray) {
    constructor(name: String, source: InputStream) : this(name, source.use { it.readBytes() })

    /**
     * Returns a fresh direct, native-order [ByteBuffer] containing the TTF bytes.
     *
     * Each call allocates a new direct buffer — `nvgCreateFontMem` takes ownership of the
     * pointer (with `freeData = false` it does NOT free), so the caller must keep a strong
     * reference to the returned buffer for as long as NanoVG holds the font. [NvgRenderer]
     * stores it alongside the font id.
     */
    fun buffer(): ByteBuffer =
        ByteBuffer.allocateDirect(cachedBytes.size)
            .order(ByteOrder.nativeOrder())
            .put(cachedBytes)
            .flip() as ByteBuffer

    override fun equals(other: Any?): Boolean = other is NvgFont && other.name == name

    override fun hashCode(): Int = name.hashCode()
}
