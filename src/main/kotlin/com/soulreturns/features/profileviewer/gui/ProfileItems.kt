package com.soulreturns.features.profileviewer.gui

import com.soulreturns.data.hypixel.LegacyItemDecoder
import com.soulreturns.platform.concurrent.SoulScope
import kotlinx.coroutines.launch
import net.minecraft.world.item.ItemStack

/**
 * Hypixel's base64 item blobs (`inv_contents.data`, Ender Chest, backpacks, wardrobe, …) decoded to
 * real [ItemStack]s, off the client thread and once per blob.
 *
 * Decoding runs vanilla's datafixer from data version 99 and costs milliseconds per container, so a
 * section asks [get] every frame and draws a skeleton while [Blob.loading]; the first ask schedules
 * the decode. Keyed by the blob text itself (the JSON already holds it), bounded LRU.
 */
object ProfileItems {
    private const val MAX_BLOBS = 96

    /** One blob's state. [stacks] is one entry per slot, null for an occupied slot that failed to decode. */
    class Blob(val loading: Boolean, val failed: Boolean, val stacks: List<ItemStack?>) {
        val ready: Boolean get() = !loading && !failed
    }

    private val LOADING = Blob(loading = true, failed = false, stacks = emptyList())
    private val FAILED = Blob(loading = false, failed = true, stacks = emptyList())

    private val cache = object : LinkedHashMap<String, Blob>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Blob>?): Boolean = size > MAX_BLOBS
    }

    /** The decoded slots of [base64], or a loading/failed marker. Never blocks. */
    fun get(base64: String): Blob {
        synchronized(cache) {
            cache[base64]?.let { return it }
            cache[base64] = LOADING
        }
        SoulScope.io.launch {
            val decoded = try {
                LegacyItemDecoder.decodeSlots(base64)
            } catch (_: Throwable) {
                null
            }
            val blob = if (decoded == null) FAILED else Blob(loading = false, failed = false, stacks = decoded)
            synchronized(cache) { cache[base64] = blob }
        }
        return LOADING
    }

    /** Test seam and memory relief on close: forget every decoded blob. */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}
