package com.soulreturns.sound.gui

import com.soulreturns.sound.CustomSound
import com.soulreturns.sound.RecentSounds
import com.soulreturns.sound.SoundLibrary
import com.soulreturns.sound.SoundNames
import com.soulreturns.sound.SoundRef

/** One listed sound. [ref] carries volume/pitch 1; screens apply the user's values on top. */
class SoundItem(
    val ref: SoundRef,
    val title: String,
    val idLine: String,
    /** Folder name of a Minecraft sound (`Entity`), or "My sound". */
    val tag: String,
    val custom: CustomSound?,
    /** How often it was heard, for the "Recently heard" folder; 0 elsewhere. */
    val heardCount: Int = 0,
) {
    val haystack: String = "$title ${ref.id} $idLine $tag".lowercase()
}

/**
 * Folders, rows and search for the library screens. Built per screen, so the vanilla catalog (and
 * every subtitle) is resolved once per open rather than per frame; rows are cached until the
 * folder, the query or the library changes.
 */
class SoundBrowserModel {
    companion object {
        const val FAVORITES = "favorites"
        const val RECENT = "recent"
        const val HEARD = "heard"
        const val MINE = "mine"
        const val GROUP = "group:"

        /** A few sounds that work as alerts, for a library with no favorites or history yet. */
        val SUGGESTED =
            listOf(
                "minecraft:entity.experience_orb.pickup",
                "minecraft:entity.player.levelup",
                "minecraft:block.note_block.pling",
                "minecraft:block.note_block.bell",
                "minecraft:block.note_block.chime",
                "minecraft:block.note_block.harp",
                "minecraft:block.anvil.land",
                "minecraft:entity.arrow.hit_player",
                "minecraft:block.bell.use",
                "minecraft:ui.toast.challenge_complete",
            )
    }

    class Group(val id: String, val title: String, val count: Int)

    val vanilla: List<SoundItem> by lazy {
        SoundNames.clearCache()
        SoundLibrary.vanillaCatalog().map { vanillaItem(it.id, it.group) }
    }

    private val vanillaById: Map<String, SoundItem> by lazy { vanilla.associateBy { it.ref.id } }

    val groups: List<Group> by lazy {
        vanilla.groupBy { it.tag }.map { (tag, items) -> Group(GROUP + tag, tag, items.size) }.sortedBy { it.title }
    }

    private var customVersion = -1
    private var customItems: List<SoundItem> = emptyList()

    fun custom(): List<SoundItem> {
        if (customVersion != SoundLibrary.version) {
            customVersion = SoundLibrary.version
            customItems = SoundLibrary.entries().sortedByDescending { it.addedAt }.map { customItem(it) }
        }
        return customItems
    }

    fun favorites(): List<SoundItem> = SoundLibrary.favorites().mapNotNull { itemFor(it) }

    fun recents(): List<SoundItem> = SoundLibrary.recents().mapNotNull { itemFor(it) }

    fun heard(): List<SoundItem> {
        val merged = LinkedHashMap<String, Int>()
        for (sound in RecentSounds.snapshot()) merged[sound.id] = (merged[sound.id] ?: 0) + sound.count
        return merged.map { (id, count) ->
            val base = vanillaById[id] ?: vanillaItem(id, id.substringAfter(':').substringBefore('.'))
            SoundItem(base.ref, base.title, base.idLine, base.tag, null, count)
        }
    }

    fun suggested(): List<SoundItem> = SUGGESTED.mapNotNull { vanillaById[it] }

    /** The listed item for a stored ref; null for a custom sound that was deleted. */
    fun itemFor(ref: SoundRef): SoundItem? =
        if (ref.isCustom) {
            SoundLibrary.entry(ref.id)?.let { customItem(it) }
        } else {
            val id = SoundRef.normalizeVanillaId(ref.id)
            vanillaById[id] ?: vanillaItem(id, id.substringAfter(':').substringBefore('.'))
        }

    fun count(folder: String): Int =
        when (folder) {
            FAVORITES -> SoundLibrary.favorites().size
            RECENT -> SoundLibrary.recents().size
            HEARD -> RecentSounds.snapshot().distinctBy { it.id }.size
            MINE -> SoundLibrary.entries().size
            else -> groups.firstOrNull { it.id == folder }?.count ?: 0
        }

    fun folderTitle(folder: String): String =
        when (folder) {
            FAVORITES -> "Favorites"
            RECENT -> "Recently used"
            HEARD -> "Recently heard"
            MINE -> "My sounds"
            else -> folder.removePrefix(GROUP)
        }

    private var cacheKey: String? = null
    private var cacheRows: List<SoundItem> = emptyList()

    /** Rows of [folder], or of every sound when [query] is not blank. */
    fun rows(
        folder: String,
        query: String,
    ): List<SoundItem> {
        val q = query.trim().lowercase()
        val key = "$folder|$q|${SoundLibrary.version}|${if (folder == HEARD && q.isEmpty()) RecentSounds.version else 0}"
        if (key == cacheKey) return cacheRows
        cacheKey = key
        cacheRows = if (q.isNotEmpty()) search(q) else folderRows(folder)
        return cacheRows
    }

    private fun folderRows(folder: String): List<SoundItem> =
        when (folder) {
            FAVORITES -> favorites()
            RECENT -> recents()
            HEARD -> heard()
            MINE -> custom()
            else -> {
                val tag = folder.removePrefix(GROUP)
                vanilla.filter { it.tag == tag }
            }
        }

    /** Every word must match; title prefix hits first, then title hits, then id-only hits. Custom sounds lead. */
    fun search(query: String): List<SoundItem> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val hits = (custom() + vanilla).filter { item -> words.all { item.haystack.contains(it) } }
        val first = words[0]
        return hits.sortedBy { item ->
            val title = item.title.lowercase()
            when {
                title.startsWith(first) -> 0
                title.contains(first) -> 1
                else -> 2
            }
        }
    }

    private fun vanillaItem(
        id: String,
        group: String,
    ): SoundItem {
        val ref = SoundRef(SoundRef.VANILLA, id)
        return SoundItem(ref, SoundNames.vanillaTitle(id), id, SoundNames.groupTitle(group), null)
    }

    private fun customItem(entry: CustomSound): SoundItem = SoundItem(SoundRef.custom(entry.id), entry.name, entry.file, "My sound", entry)
}
