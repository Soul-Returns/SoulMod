package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeCatalogClient
import com.soulreturns.data.recipes.RecipeIndex
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.SelfProfileLoaded
import com.soulreturns.data.skyblock.SelfProfileSnapshot
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.sacks.SackState
import com.soulreturns.features.search.SearchEverywhere
import net.minecraft.client.Minecraft

/**
 * The Forge tab of Search everywhere (`mocks/search-everywhere/`, forge.js): pick a forge recipe as this
 * profile's goal, see its tree down to base materials with what the player owns subtracted at every level,
 * what to gather and what can start now. Part of the Search everywhere card: available whenever search is.
 */
object Forge {
    /** The Forge tab exists whenever Search everywhere does (any search source's feature on). */
    fun enabled(): Boolean = SearchEverywhere.sources.any { it.enabled() }

    fun register() {
        ForgeMenus.register()
        com.soulreturns.data.items.ItemIcons.addSeen(ForgeItems.Seen)
        Events.subscribe<SelfProfileLoaded> { syncApi() }
        // Owned sacks are part of the Forge tab's counts: keep them counted while it can be opened.
        SackState.requireCounts { enabled() && SkyblockState.onSkyblock }
        com.soulreturns.features.dev.DevState.register("forge") {
            val data = ForgeStore.read()
            val book = ForgeStore.book()
            linkedMapOf(
                "enabled" to enabled(), "catalog" to RecipeCatalogClient.state(), "recipes" to RecipeCatalogClient.index.forge.size,
                "quickForge" to data.quickForge, "quickForgeLevel" to data.quickForgeLevel, "quickForgeFrom" to data.quickForgeFrom,
                "slots" to data.slots.map { "${it.slot}:${it.state}:${it.outputId ?: "-"}:${it.source}:${if (it.endsAt > 0) (it.endsAt - System.currentTimeMillis()) / 1000 else 0}s${if (ForgeProcesses.approximate(it)) "~" else ""}" },
                "locked" to data.locked.size, "goal" to data.goal, "categories" to book.categories, "placed" to book.recipes.size,
                "lastMode" to book.lastMode, "owned" to ForgeOwned.snapshot().known,
            )
        }
    }

    // ---------------------------------------------------------------- player state

    /** [fraction] 0.30 at perk level 20; unknown (never read on this profile) computes at 0 %. */
    class QuickForge(val fraction: Double, val known: Boolean, val level: Int?)

    fun quickForge(): QuickForge {
        val data = ForgeStore.read()
        val percent = data.quickForge ?: return QuickForge(0.0, false, null)
        val level = data.quickForgeLevel ?: levelOf(percent)
        return QuickForge(percent / 100, true, level)
    }

    /** 10 % + 0.5 % per level for levels 1–19, 30 % at 20 (research §4a); null for anything else (a boost). */
    private fun levelOf(percent: Double): Int? {
        if (percent == 0.0) return 0
        if (percent == 30.0) return 20
        val level = (percent - 10) * 2
        return if (level >= 1 && level <= 19 && level == Math.floor(level)) level.toInt() else null
    }

    sealed interface SlotView
    data object Idle : SlotView
    /** A running process finishing at the wall-clock [endsAt]; [approx] = estimated (API, older file), shown with `~`. */
    class Busy(val slot: Int, val outputId: String, val endsAt: Long, val approx: Boolean, val source: String) : SlotView {
        fun secondsLeft(now: Long): Long = Math.max(0L, (endsAt - now + 999) / 1000)
    }
    /** Finished, waiting to be claimed in The Forge. */
    class Done(val slot: Int, val outputId: String) : SlotView
    /** Never-captured contents (locked) or a slot nothing has told us about: not guessed. */
    class Unknown(val outputId: String?) : SlotView

    /**
     * The 7 slots as last known, empty when nothing has read them on this profile. A busy row whose end has passed
     * is [Done]; rows from before version 2 are interpreted at the current Quick Forge [qf].
     */
    fun slots(now: Long, qf: Double): List<SlotView> {
        val rows = ForgeStore.read().slots
        if (rows.isEmpty()) return emptyList()
        val byNumber = rows.associateBy { it.slot }
        return (1..ForgeEngine.SLOTS).map { n ->
            val s = byNumber[n] ?: return@map Unknown(null)
            if (s.state == "idle") Idle
            else if (s.state == "busy" && s.outputId != null) {
                val end = ForgeProcesses.endsAt(s, qf)
                if (end > now) Busy(n, s.outputId, end, ForgeProcesses.approximate(s), s.source) else Done(n, s.outputId)
            } else Unknown(s.outputId)
        }
    }

    /** Merges [incoming] slot observations into this profile's stored slots (see [ForgeProcesses.merge]). */
    fun observeSlots(incoming: List<ForgeSlotRead>, now: Long) {
        ForgeStore.update {
            val byNumber = slots.associateBy { it.slot }
            val merged = (1..ForgeEngine.SLOTS).mapNotNull { n ->
                val inc = incoming.firstOrNull { it.slot == n }
                if (inc == null) byNumber[n] else ForgeProcesses.merge(byNumber[n], inc)
            }
            if (merged == slots) this else copy(version = 2, slots = merged, slotsAt = now)
        }
    }

    /** The player confirmed a process in [slot]: its end is the recipe's base time at the Quick Forge known right now. */
    fun recordStart(slot: Int, id: String, name: String?, now: Long = System.currentTimeMillis()) {
        val base = RecipeCatalogClient.index.forge[id]?.baseSeconds ?: return
        val qf = quickForge()
        observeSlots(listOf(ForgeProcesses.fromStart(slot, id, name, base, qf.fraction, qf.known, now)), now)
    }

    /**
     * Applies the SkyBlock API's forge processes of the loaded self profile: the fallback for slots this client
     * never saw. Idempotent; a snapshot is applied once.
     */
    fun syncApi() {
        val snap = SkyblockSelfProfile.snapshot ?: return
        if (snap === lastApiSnapshot) return
        val profile = SkyblockProfileState.current ?: return
        if (!snap.profileName.equals(profile, ignoreCase = true)) return
        val index = RecipeCatalogClient.index
        if (index.forge.isEmpty()) return
        lastApiSnapshot = snap
        applyApi(ForgeProcesses.apiProcesses(snap.member.raw), snap.loadedAt.toEpochMilli(), ForgeProcesses.apiQuickForgeLevel(snap.member.raw))
    }

    /**
     * [procs] as the API's answer from [loadedAt]. The current Quick Forge percent applies to every process (the
     * level at the start is unknown); [apiLevel] stands in only while no menu has told the mod the percent.
     * `/soul dev forge api` feeds this with a synthetic answer.
     */
    fun applyApi(procs: List<ForgeProcesses.ApiProcess>, loadedAt: Long, apiLevel: Int?) {
        val index = RecipeCatalogClient.index
        val known = quickForge()
        val qf = if (known.known) known.fraction else ForgeProcesses.percentOfLevel(apiLevel ?: 0) / 100
        val stored = ForgeStore.read().slots
        val merged = ForgeProcesses.mergeApi(stored, procs, loadedAt, qf) { index.forge[it]?.baseSeconds }
        if (merged != stored) ForgeStore.update { copy(version = 2, slots = merged, slotsAt = loadedAt) }
    }

    private var lastApiSnapshot: SelfProfileSnapshot? = null

    fun goal(): String? = ForgeStore.read().goal

    fun setGoal(id: String) = ForgeStore.update { copy(goal = id) }

    fun lastMode(): String = ForgeStore.book().lastMode

    fun setMode(mode: String) {
        if (ForgeStore.book().lastMode != mode) ForgeStore.updateBook { copy(lastMode = mode) }
    }

    fun recipes(index: RecipeIndex = RecipeCatalogClient.index) = ForgeRecipes(index, ForgeStore.read().locked)

    /**
     * Opens [id]'s recipe in game for quick crafting: the one place `/viewrecipe` is sent (a raw protocol
     * command on the player's click, no [Soul] prefix). UNVERIFIED for every craftable id and every area.
     */
    fun viewRecipe(id: String) {
        val mc = Minecraft.getInstance()
        val connection = mc.player?.connection ?: return
        mc.gui.setScreen(null)
        connection.sendCommand("viewrecipe $id")
    }
}

/** Mock `compute()`: everything one goal view draws, rebuilt only when an input changed. */
class ForgeModel(
    val goal: String,
    val root: ForgeNode,
    val agg: List<ForgeStep>,
    val byId: Map<String, ForgeStep>,
    /** From nothing: the "of 372" denominator. */
    val totalProcesses: Long,
    val time: TimeLeft,
    /** At Quick Forge 20 (the best case shown when below 30 %). */
    val best: TimeLeft,
    val plan: ForgePlan,
    val gather: List<ForgeStep>,
    /** Base material id → its compacted forms the tree uses ([ForgeCompacted]). */
    val compacted: Map<String, List<ForgeStep>>,
) {
    companion object {
        private var key: List<Any?> = emptyList()
        private var cached: ForgeModel? = null

        fun of(goal: String?, index: RecipeIndex, locks: Map<String, String>, owned: OwnedSnapshot, qf: Double, free: Int): ForgeModel? {
            if (goal == null || goal !in index.forge) return null
            val k = listOf(goal, index, locks, owned, qf, free)
            if (k == key) cached?.let { return it }
            val book = ForgeRecipes(index, locks)
            val root = ForgeEngine.expand(goal, book, owned::have)
            val agg = ForgeEngine.aggregate(root)
            val scratch = ForgeEngine.expand(goal, book) { 0L }
            val total = ForgeEngine.timeLeft(scratch, ForgeEngine.aggregate(scratch), ForgeEngine.SLOTS, qf).processes
            val model = ForgeModel(goal, root, agg, agg.associateBy { it.id }, total,
                ForgeEngine.timeLeft(root, agg, ForgeEngine.SLOTS, qf), ForgeEngine.timeLeft(root, agg, ForgeEngine.SLOTS, ForgeEngine.QF_MAX),
                ForgeEngine.plan(root, agg, free, qf), ForgeEngine.gather(agg), ForgeCompacted.formsByBase(agg, book))
            key = k
            cached = model
            return model
        }
    }
}
