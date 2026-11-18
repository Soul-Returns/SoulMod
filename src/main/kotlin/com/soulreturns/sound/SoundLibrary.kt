package com.soulreturns.sound

import com.soulreturns.features.dev.DevState
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.util.Util
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

/** One imported sound file. [id] is stable (refs point at it); [name] is the display name and may change. */
@Serializable
data class CustomSound(
    val id: String,
    val name: String,
    /** File name inside [SoundLibrary.dir]. */
    val file: String,
    /** `ogg`, `wav` or `mp3`. */
    val format: String,
    val durationMs: Long,
    /** Epoch millis. */
    val addedAt: Long,
)

/** A sound event the client knows (vanilla or resource pack). `entity.enderman.teleport` → group `entity`, subgroup `enderman`. */
data class VanillaSound(
    val id: String,
    val group: String,
    val subgroup: String?,
)

/**
 * The custom sound library plus the picker's favorites and recently used sounds.
 *
 * Storage: `config/soul/sounds/` holds the imported files (copied, renamed to `<id>.<ext>`) and
 * `library.json` describing them. Kept beside the files rather than in `stats.json` so the folder is
 * self-contained (sound packs will zip it) and `stats.json` stays about accumulated numbers.
 */
object SoundLibrary {
    private val logger = SoulLogger("Soul/Sounds")

    const val SCHEMA_VERSION = 1
    const val MAX_RECENTS = 20
    const val MAX_NAME_LENGTH = 48

    @Serializable
    private data class LibraryFile(
        val schemaVersion: Int = SCHEMA_VERSION,
        val entries: List<CustomSound> = emptyList(),
        val favorites: List<SoundRef> = emptyList(),
        val recents: List<SoundRef> = emptyList(),
    )

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** One writer thread, so saves land in order. */
    private val io =
        Executors.newSingleThreadExecutor { r -> Thread(r, "Soul Sounds IO").apply { isDaemon = true } }

    private val lock = Any()

    @Volatile
    private var state: LibraryFile? = null

    /** Bumped on every change, so a screen can refresh only when something moved. */
    @Volatile
    var version: Int = 0
        private set

    val dir: Path get() = FabricLoader.getInstance().configDir.resolve("soul").resolve("sounds")
    private val indexFile: Path get() = dir.resolve("library.json")

    /** Loads the index and warms every clip in the background. */
    fun register() {
        val loaded = current()
        for (entry in loaded.entries) CustomClips.get(entry.id)
        // What reached the engine (a sound canceled at the packet never does), newest first.
        DevState.register("recent_sounds") {
            mapOf(
                "recent" to RecentSounds.snapshot().take(20).map {
                    linkedMapOf("id" to it.id, "category" to it.category, "count" to it.count, "outcome" to it.outcome, "pitch" to it.pitch)
                },
            )
        }
    }

    private fun current(): LibraryFile {
        state?.let { return it }
        synchronized(lock) {
            state?.let { return it }
            val loaded = load()
            state = loaded
            return loaded
        }
    }

    private fun load(): LibraryFile {
        val file = indexFile
        if (!Files.isRegularFile(file)) return LibraryFile()
        return try {
            json.decodeFromString(LibraryFile.serializer(), Files.readString(file))
        } catch (t: Throwable) {
            // Keep the unreadable file for a manual rescue instead of overwriting it on the next save.
            val backup = file.resolveSibling("library.json.broken-${System.currentTimeMillis()}")
            runCatching { Files.move(file, backup) }
            logger.warn("Sound library index unreadable, starting empty (kept as ${backup.fileName})", t)
            LibraryFile()
        }
    }

    private fun mutate(block: (LibraryFile) -> LibraryFile) {
        synchronized(lock) {
            val next = block(current())
            state = next
            version++
            val text = json.encodeToString(LibraryFile.serializer(), next)
            io.execute { write(text) }
        }
    }

    private fun write(text: String) {
        try {
            Files.createDirectories(dir)
            val tmp = indexFile.resolveSibling("library.json.tmp")
            Files.writeString(tmp, text)
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            logger.warn("Failed to save the sound library index", t)
        }
    }

    // --- custom entries ---

    fun entries(): List<CustomSound> = current().entries

    fun entry(id: String): CustomSound? = current().entries.firstOrNull { it.id == id }

    fun fileOf(entry: CustomSound): Path = dir.resolve(entry.file)

    /**
     * Copies [path] into the library after decoding it once (format, ≤ [SoundDecoder.MAX_FILE_BYTES],
     * ≤ [SoundDecoder.MAX_DURATION_MS]). Blocking: call off the render thread, or use [importAsync].
     */
    fun import(path: Path): Result<CustomSound> =
        runCatching {
            if (!Files.isRegularFile(path)) throw IOException("Not a file: $path")
            val format = SoundDecoder.detectFormat(path) ?: throw IOException("Not an .ogg, .wav or .mp3 file")
            val clip = SoundDecoder.decode(path, format)
            Files.createDirectories(dir)
            val name = path.fileName.toString().substringBeforeLast('.').let(::cleanName).ifEmpty { "Sound" }
            var created: CustomSound? = null
            synchronized(lock) {
                val id = uniqueId(slug(name))
                val target = dir.resolve("$id.$format")
                Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                val entry = CustomSound(id, name, target.fileName.toString(), format, clip.durationMs, System.currentTimeMillis())
                mutate { it.copy(entries = it.entries + entry) }
                created = entry
            }
            val entry = created!!
            CustomClips.put(entry.id, clip)
            logger.info("Imported sound '${entry.name}' as ${entry.id} (${entry.format}, ${entry.durationMs} ms)")
            entry
        }

    fun importAsync(path: Path): CompletableFuture<Result<CustomSound>> = CompletableFuture.supplyAsync({ import(path) }, Util.backgroundExecutor())

    /** Changes the display name only; the id (and every ref to it) stays. */
    fun rename(
        id: String,
        name: String,
    ): Boolean {
        val clean = cleanName(name)
        if (clean.isEmpty() || entry(id) == null) return false
        mutate { lib -> lib.copy(entries = lib.entries.map { if (it.id == id) it.copy(name = clean) else it }) }
        return true
    }

    /** Removes the entry, its file and any favorite/recent pointing at it. Refs elsewhere then fail soft. */
    fun delete(id: String): Boolean {
        val entry = entry(id) ?: return false
        mutate { lib ->
            lib.copy(
                entries = lib.entries.filterNot { it.id == id },
                favorites = lib.favorites.filterNot { it.isCustom && it.id == id },
                recents = lib.recents.filterNot { it.isCustom && it.id == id },
            )
        }
        CustomClips.evict(id)
        val file = fileOf(entry)
        io.execute { runCatching { Files.deleteIfExists(file) }.onFailure { logger.warn("Could not delete $file", it) } }
        return true
    }

    // --- favorites and recents (vanilla and custom alike) ---

    fun favorites(): List<SoundRef> = current().favorites

    fun isFavorite(ref: SoundRef): Boolean = current().favorites.any { it.sameSound(ref) }

    fun setFavorite(
        ref: SoundRef,
        favorite: Boolean,
    ) {
        if (favorite == isFavorite(ref)) return
        mutate { lib ->
            lib.copy(favorites = if (favorite) lib.favorites + ref else lib.favorites.filterNot { it.sameSound(ref) })
        }
    }

    /** Flips the favorite state; returns the new state. */
    fun toggleFavorite(ref: SoundRef): Boolean {
        val next = !isFavorite(ref)
        setFavorite(ref, next)
        return next
    }

    /** Recently used sounds (previewed or picked), newest first, at most [MAX_RECENTS]. */
    fun recents(): List<SoundRef> = current().recents

    fun markUsed(ref: SoundRef) {
        if (current().recents.firstOrNull()?.let { it == ref } == true) return
        mutate { lib -> lib.copy(recents = (listOf(ref) + lib.recents.filterNot { it.sameSound(ref) }).take(MAX_RECENTS)) }
    }

    // --- vanilla catalog ---

    /** Every sound event the client's `SoundManager` knows after the last resource reload, sorted by id. */
    fun vanillaCatalog(): List<VanillaSound> =
        Minecraft.getInstance().soundManager.availableSounds
            .map { id ->
                val segments = id.path.split('.')
                VanillaSound(id.toString(), segments[0], segments.getOrNull(1)?.takeIf { segments.size > 2 })
            }.sortedBy { it.id }

    // --- helpers ---

    private fun cleanName(name: String): String =
        name.filter { it >= ' ' && it != '§' }.trim().take(MAX_NAME_LENGTH)

    private fun slug(name: String): String =
        name.lowercase()
            .map { c -> if (c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-') c else '_' }
            .joinToString("")
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(32)
            .ifEmpty { "sound" }

    private fun uniqueId(base: String): String {
        val taken = current().entries.mapTo(HashSet()) { it.id }
        if (base !in taken && !SoundDecoder.FORMATS.any { Files.exists(dir.resolve("$base.$it")) }) return base
        var n = 2
        while (true) {
            val candidate = "$base-$n"
            if (candidate !in taken && !SoundDecoder.FORMATS.any { Files.exists(dir.resolve("$candidate.$it")) }) return candidate
            n++
        }
    }
}
