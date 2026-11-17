package com.soulreturns.features.experiments

import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import kotlinx.serialization.Serializable
import net.minecraft.client.Minecraft

/**
 * Stats namespace `experimentsRng`, one bucket per Minecraft account: the RNG Meter's drop table (odds
 * look global, research §7.4) and, per SkyBlock profile, the selected drop (the selection is per profile).
 * Written whenever a `(n/N) Experimentation Table RNG` page is read; the rare set is recomputed from it.
 */
object RngMeterStore {
    const val NAMESPACE = "experimentsRng"

    /** [version] = payload schema; a change needs a load-time step (rule://stats). */
    @Serializable
    data class Cache(
        val version: Int = 1,
        val table: RareRewards.Table = RareRewards.Table(),
        val selected: Map<String, RareRewards.Selection> = emptyMap(),
    )

    private val handle: StatsHandle<Cache> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = Cache.serializer(),
            default = { Cache() },
            key = { Minecraft.getInstance().user.profileId.toString().replace("-", "") },
        )
    }

    private var keysOf: RareRewards.Table? = null
    private var keys: Set<String>? = null

    private fun profileKey(): String? = SkyblockProfileState.profileId ?: SkyblockProfileState.current

    fun onPage(page: Int, pages: Int, entries: List<RareRewards.Entry>) {
        val now = System.currentTimeMillis()
        val profile = profileKey()
        handle.update {
            val sel = profile?.let { p -> RareRewards.withSelection(selected[p], page, entries, now) }
            copy(
                table = RareRewards.withPage(table, page, pages, entries, now),
                selected = if (profile == null) selected else if (sel == null) selected - profile else selected + (profile to sel),
            )
        }
    }

    /** Rare keys from the cached table (null = the meter was never read completely). */
    fun rareKeys(): Set<String>? {
        val t = handle.read().table
        if (t !== keysOf) {
            keysOf = t
            keys = RareRewards.rareKeys(t)
        }
        return keys
    }

    fun selectedKeys(): List<String>? = profileKey()?.let { handle.read().selected[it]?.keys }

    /** The meter has been read at least once (all pages): rare warnings beyond Hypixel's own label work. */
    fun known(): Boolean = rareKeys() != null

    fun isRare(item: ExpItem): Boolean = RareRewards.isRare(item, rareKeys(), selectedKeys())
}
