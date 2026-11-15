package com.soulreturns.sound

import net.minecraft.util.Util
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture

/**
 * Decoded custom clips, keyed by library id. Decoding runs on Minecraft's background executor, so
 * the first play of a cold clip starts a little late; [SoundLibrary.register] warms every entry at
 * startup. Bounded by [BUDGET_BYTES]: least recently used clips are dropped and re-decoded on demand.
 */
object CustomClips {
    const val BUDGET_BYTES = 128L * 1024 * 1024

    private val lock = Any()
    private val cache = LinkedHashMap<String, CompletableFuture<DecodedClip>>(16, 0.75f, true)

    /** The clip for a library entry, decoding it if needed; null when the id is not in the library. */
    fun get(id: String): CompletableFuture<DecodedClip>? {
        val entry = SoundLibrary.entry(id) ?: return null
        synchronized(lock) {
            cache[id]?.let { if (!it.isCompletedExceptionally) return it }
            val path = SoundLibrary.fileOf(entry)
            val future = CompletableFuture.supplyAsync({ SoundDecoder.decode(path, entry.format) }, Util.backgroundExecutor())
            cache[id] = future
            future.thenRun { trim() }
            return future
        }
    }

    /** Seeds the cache with a clip decoded elsewhere (import validation decodes it anyway). */
    fun put(
        id: String,
        clip: DecodedClip,
    ) {
        synchronized(lock) { cache[id] = CompletableFuture.completedFuture(clip) }
        trim()
    }

    fun evict(id: String) {
        synchronized(lock) { cache.remove(id) }
    }

    private val opened = ConcurrentHashMap<String, AtomicInteger>()
    private val finished = ConcurrentHashMap<String, AtomicInteger>()

    private val lastMono = ConcurrentHashMap<String, Boolean>()

    /** The engine opened a stream over this clip (it passed the volume check and got a channel). */
    internal fun noteOpened(
        id: String,
        mono: Boolean,
    ) {
        opened.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
        lastMono[id] = mono
    }

    /** OpenAL pulled the last samples of a stream over this clip. */
    internal fun noteFinished(id: String) {
        finished.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet()
    }

    /** Streams opened / fully read this session, and whether the latest one was a positional (mono) stream. */
    fun streamCounts(id: String): Triple<Int, Int, Boolean?> = Triple(opened[id]?.get() ?: 0, finished[id]?.get() ?: 0, lastMono[id])

    data class Stats(
        val clips: Int,
        val decoding: Int,
        val failed: Int,
        val bytes: Long,
    )

    fun stats(): Stats =
        synchronized(lock) {
            var bytes = 0L
            var decoding = 0
            var failed = 0
            for (f in cache.values) {
                if (!f.isDone) {
                    decoding++
                } else if (f.isCompletedExceptionally) {
                    failed++
                } else {
                    bytes += f.join().bytes
                }
            }
            Stats(cache.size, decoding, failed, bytes)
        }

    /** Decode state of one clip for the dev dump: `cached`, `decoding`, `failed: …` or `cold`. */
    fun state(id: String): String =
        synchronized(lock) {
            val f = cache[id] ?: return "cold"
            when {
                !f.isDone -> "decoding"
                f.isCompletedExceptionally -> "failed: " + runCatching { f.join() }.exceptionOrNull()?.cause?.message
                else -> "cached"
            }
        }

    private fun trim() {
        synchronized(lock) {
            var total = cache.values.sumOf { if (it.isDone && !it.isCompletedExceptionally) it.join().bytes else 0L }
            val it = cache.entries.iterator()
            while (total > BUDGET_BYTES && it.hasNext()) {
                val e = it.next()
                val f = e.value
                if (!f.isDone) continue
                if (!f.isCompletedExceptionally) total -= f.join().bytes
                it.remove()
            }
        }
    }
}
