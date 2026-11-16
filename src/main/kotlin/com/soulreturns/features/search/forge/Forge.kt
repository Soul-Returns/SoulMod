package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeCatalogClient
import com.soulreturns.data.recipes.RecipeIndex
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
        ForgeModels.register()
        // Owned sacks are part of the Forge tab's counts: keep them counted while it can be opened.
        SackState.requireCounts { enabled() && SkyblockState.onSkyblock }
        com.soulreturns.features.dev.DevState.register("forge") {
            val data = ForgeStore.read()
            val book = ForgeStore.book()
            linkedMapOf(
                "enabled" to enabled(), "catalog" to RecipeCatalogClient.state(), "recipes" to RecipeCatalogClient.index.forge.size,
                "quickForge" to data.quickForge, "quickForgeLevel" to data.quickForgeLevel, "quickForgeFrom" to data.quickForgeFrom,
                "slots" to data.slots.map { "${it.slot}:${it.state}:${it.outputId ?: "-"}:${it.remainingBase}" },
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
    /** [remainingBase] base seconds left now; the shown time is `shownSeconds(remainingBase, qf)`. */
    class Busy(val outputId: String, val remainingBase: Long) : SlotView
    /** Never-captured contents (finished / locked) or a process whose remembered time ran out: not guessed. */
    class Unknown(val outputId: String?) : SlotView

    /**
     * The 7 slots as last read, aged to [now] at the current Quick Forge (it follows the active HotM tree, also
     * for running processes); empty when The Forge was never opened on this profile.
     */
    fun slots(now: Long, qf: Double): List<SlotView> = ForgeStore.read().slots.map { s ->
        if (s.state == "idle") Idle
        else if (s.state == "busy" && s.outputId != null) {
            val shown = ForgeEngine.shownSeconds(s.remainingBase, qf) - (now - s.readAt) / 1000
            if (shown > 0) Busy(s.outputId, Math.round(shown / (1 - qf).coerceAtLeast(0.01))) else Unknown(s.outputId)
        } else Unknown(s.outputId)
    }

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
                ForgeEngine.plan(root, agg, free, qf), ForgeEngine.gather(agg))
            key = k
            cached = model
            return model
        }
    }
}
