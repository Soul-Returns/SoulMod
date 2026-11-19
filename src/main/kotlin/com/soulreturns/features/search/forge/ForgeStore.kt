package com.soulreturns.features.search.forge

import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import kotlinx.serialization.Serializable

/**
 * One forge slot as last known. A busy slot carries the wall-clock moment it finishes ([endsAt]); the tab does
 * no Quick Forge math for it. [source] says where that moment came from (`ForgeProcesses` ranks them).
 *
 * Files written before payload version 2 hold only [remainingBase] (BASE seconds left at [readAt], to be scaled
 * by whatever Quick Forge applies now): [source] is then empty and [ForgeProcesses.endsAt] interprets them.
 */
@Serializable
data class ForgeSlotRead(
    val slot: Int,
    /** `idle` | `busy` | `unknown` (an encoding never captured: finished, locked). */
    val state: String,
    val outputId: String? = null,
    val name: String? = null,
    /** Version 1 only: remaining base seconds at [readAt]. Version 2 writes 0. */
    val remainingBase: Long = 0,
    /** When this slot was last observed or computed (ms). */
    val readAt: Long = 0,
    /** Wall-clock end of a busy process (ms); 0 = not stored (version 1 rows). */
    val endsAt: Long = 0,
    /** When the process started (ms) if known: a start click or the API; 0 for a menu read. */
    val startedAt: Long = 0,
    /** `menu` | `start` | `api`; empty = a version 1 row. */
    val source: String = "",
    /** Computed without knowing the Quick Forge level the process started with. */
    val approx: Boolean = false,
)

/**
 * The `forge` stats namespace, one bucket per SkyBlock profile (rule://stats). [version] is this payload's
 * schema: bump it with a load-time step when a field changes meaning.
 */
@Serializable
data class ForgeProfileData(
    /** 2: busy slots store [ForgeSlotRead.endsAt] and its source (version 1 rows keep loading, see [ForgeProcesses]). */
    val version: Int = 2,
    /** Quick Forge percent (30.0 at level 20), null = never read on this profile. */
    val quickForge: Double? = null,
    val quickForgeLevel: Int? = null,
    /** `hotm` | `recipes` */
    val quickForgeFrom: String? = null,
    val quickForgeAt: Long = 0,
    val slots: List<ForgeSlotRead> = emptyList(),
    val slotsAt: Long = 0,
    /** Output id → `Requires …` text from this profile's recipe pages; entries seen unlocked are removed. */
    val locked: Map<String, String> = emptyMap(),
    val lockedAt: Long = 0,
    /** One goal per profile (approved mock). */
    val goal: String? = null,
)

@Serializable
data class ForgeLook(val item: String, val skin: String = "", val model: String = "", val glint: Boolean = false) {
    fun look() = InventoryButtonIcons.ItemLook(item, skin, model, glint)

    companion object {
        fun of(look: InventoryButtonIcons.ItemLook) = ForgeLook(look.item, look.skin, look.model, look.glint)
    }
}

/** Where a recipe sits in the Forge's own book: its category page and slot (mock `order` = page × 100 + slot). */
@Serializable
data class ForgeBookEntry(val category: String, val order: Int, val name: String? = null, val look: ForgeLook? = null)

/**
 * The `forgeBook` stats namespace, one global bucket: the Forge's categories and each recipe's place as
 * read from any profile's category pages (the same on every profile), plus the output's look for icons.
 * Learned only from menus, never a fixed list.
 */
@Serializable
data class ForgeBookData(
    val version: Int = 1,
    /** Category names in the order of the Select Process menu. */
    val categories: List<String> = emptyList(),
    val recipes: Map<String, ForgeBookEntry> = emptyMap(),
    /** `<category>|<page>|<slot>` → output id, to name locked placeholders. */
    val positions: Map<String, String> = emptyMap(),
    /** K opens the tab used last (approved mock): `search` | `forge`. */
    val lastMode: String = "search",
    /** Recipe id → the exact look of a real stack seen in any menu or the inventory (icons for ids not owned). */
    val looks: Map<String, ForgeLook> = emptyMap(),
)

object ForgeStore {
    const val NAMESPACE = "forge"
    const val BOOK_NAMESPACE = "forgeBook"

    private val profile: StatsHandle<ForgeProfileData> by lazy {
        PersistentStats.namespace(NAMESPACE, ForgeProfileData.serializer(), { ForgeProfileData() }) {
            SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY
        }
    }

    private val book: StatsHandle<ForgeBookData> by lazy {
        PersistentStats.namespace(BOOK_NAMESPACE, ForgeBookData.serializer(), { ForgeBookData() }) { "book" }
    }

    /** This profile's data; empty while the profile is not known. */
    fun read(): ForgeProfileData = if (SkyblockProfileState.current == null) ForgeProfileData() else profile.read()

    /** Writes only with a known profile: nothing forge-related may land in another profile's bucket. */
    fun update(block: ForgeProfileData.() -> ForgeProfileData) {
        if (SkyblockProfileState.current == null) return
        profile.update(block)
    }

    fun book(): ForgeBookData = book.read()

    fun updateBook(block: ForgeBookData.() -> ForgeBookData) = book.update(block)
}
