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
    private const val MAX_PIECE_SETS = 24

    /** One blob's state. [stacks] is one entry per slot, null for an occupied slot that failed to decode. */
    class Blob(val loading: Boolean, val failed: Boolean, val stacks: List<ItemStack?>) {
        val ready: Boolean get() = !loading && !failed
    }

    private val LOADING = Blob(loading = true, failed = false, stacks = emptyList())
    private val FAILED = Blob(loading = false, failed = true, stacks = emptyList())

    private val cache = object : LinkedHashMap<String, Blob>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Blob>?): Boolean = size > MAX_BLOBS
    }

    /** Tests decode on the calling thread, so a section's first build already sees its items. */
    @Volatile
    var synchronous = false

    /** The decoded slots of [base64], or a loading/failed marker. Never blocks (unless [synchronous]). */
    fun get(base64: String): Blob {
        synchronized(cache) {
            cache[base64]?.let { return it }
            cache[base64] = LOADING
        }
        if (synchronous) return decode(base64)
        SoulScope.io.launch { decode(base64) }
        return LOADING
    }

    private fun decode(base64: String): Blob {
        val decoded = try {
            LegacyItemDecoder.decodeSlots(base64)
        } catch (_: Throwable) {
            null
        }
        val blob = if (decoded == null) FAILED else Blob(loading = false, failed = false, stacks = decoded)
        synchronized(cache) { cache[base64] = blob }
        return blob
    }

    /** The key of one set of one-item blobs: [owner] by identity (the member's raw JSON), [tag] telling the sets apart. */
    private class PiecesKey(val owner: Any, val tag: String) {
        override fun equals(other: Any?) = other is PiecesKey && other.owner === owner && other.tag == tag
        override fun hashCode() = System.identityHashCode(owner) * 31 + tag.hashCode()
    }

    private val pieces = object : LinkedHashMap<PiecesKey, Blob>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PiecesKey, Blob>?): Boolean = size > MAX_PIECE_SETS
    }

    /**
     * Loadout pieces (`loadout.armor.<id>.HELMET.data`, one item per blob, ~100 per profile) decoded together:
     * [Blob.stacks] has one entry per blob of [blobs], in order, [ItemStack.EMPTY] for a blob without an item and
     * null for one that cannot be decoded. They bypass the per-blob cache, which a hundred blobs would flush.
     * Memoized by [owner] identity and [tag], so [blobs] only runs for the first ask.
     */
    fun getPieces(owner: Any, tag: String, blobs: () -> List<String>): Blob {
        val key = PiecesKey(owner, tag)
        synchronized(pieces) {
            pieces[key]?.let { return it }
            pieces[key] = LOADING
        }
        val list = blobs()
        if (synchronous) return decodePieces(key, list)
        SoulScope.io.launch { decodePieces(key, list) }
        return LOADING
    }

    private fun decodePieces(key: PiecesKey, blobs: List<String>): Blob {
        val stacks = blobs.map { base64 ->
            try {
                val slots = LegacyItemDecoder.decodeSlots(base64)
                if (slots == null) null else if (slots.isEmpty()) ItemStack.EMPTY else slots[0]
            } catch (_: Throwable) {
                null
            }
        }
        val blob = Blob(loading = false, failed = false, stacks = stacks)
        synchronized(pieces) { pieces[key] = blob }
        return blob
    }

    /** Test seam and memory relief on close: forget every decoded blob. */
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(pieces) { pieces.clear() }
    }
}
