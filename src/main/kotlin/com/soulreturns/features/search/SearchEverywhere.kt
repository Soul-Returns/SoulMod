package com.soulreturns.features.search

import com.mojang.blaze3d.platform.InputConstants
import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.islandchests.IslandChestDisplay
import com.soulreturns.features.islandchests.IslandChestSearchSession
import com.soulreturns.features.search.forge.Forge
import com.soulreturns.util.KeyCombo
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Search everywhere (`mocks/search-everywhere/`): one search over every item source Soul remembers.
 * The binding (`islandChests.searchKey`, default K) opens it during gameplay and returns to it from
 * locating or from a page it opened; `/soul search` and `/soul chests` open it too.
 */
object SearchEverywhere {
    /** Every source, in filter-chip order. A new source is one more entry here. */
    internal val sources: List<SearchSource> = listOf(IslandChestSource, StorageSource, SackSearchSource)
    internal val index = SearchIndex()
    private val session = IslandChestSearchSession()
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        com.soulreturns.features.sacks.SackState.requireCounts { SackSearchSource.enabled() && SkyblockState.onSkyblock }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            // Let GLFW finish the opening key's character callback before focusing search.
            if (session.takeRequest(available(), mc.player != null && mc.level != null, mc.gui.screen() != null, mc.isWindowActive)) {
                val search = session.restore(profile(), IslandChestDisplay.takeLocating())
                mc.setScreenAndShow(SearchEverywhereScreen().apply { restore(search.query, search.itemKey) })
            }
        }
    }

    /** Sources whose feature is on, in [sources] order. */
    internal fun active(): List<SearchSource> = sources.filter { it.enabled() }

    internal fun rows(active: List<SearchSource>): List<SearchRow> = index.update(active.map { it.id to it.entries() })

    fun available(): Boolean = SkyblockState.onSkyblock && sources.any { it.enabled() }

    internal fun profile(): String? = SkyblockProfileState.profileId

    /** Search from a command or menu entry, with the last search of this profile. */
    fun screen(parent: Screen? = null): Screen {
        val search = session.restore(profile(), null)
        return SearchEverywhereScreen(parent).apply { restore(search.query, search.itemKey) }
    }

    fun open(parent: Screen? = null) = Minecraft.getInstance().setScreenAndShow(screen(parent))

    /** What the binding returns to after locating or opening a page. */
    internal fun remember(query: String, itemKey: String) = session.remember(profile(), query, itemKey)

    /** An action left the screen for the world (locating, warping). */
    internal fun leave() {
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() is SearchEverywhereScreen) mc.gui.setScreen(null)
    }

    /** The text of "K Back to …" on the locator and page bars: the Forge tab is where K returns when it was used last. */
    internal fun backLabel(): String = if (Forge.enabled() && Forge.lastMode() == "forge") "Back to Forge" else "Back to search"

    /** Back to search from a screen this search led to (the storage page it opened). */
    internal fun requestFromScreen(): Boolean {
        val mc = Minecraft.getInstance()
        return session.request(available(), mc.player != null && mc.level != null, mc.gui.screen() != null, mc.isWindowActive)
    }

    internal fun matchesBinding(event: KeyEvent): Boolean {
        val combo = KeyCombo.cached(cfg.islandChests.searchKey) ?: return false
        return combo.key == InputConstants.getKey(event) && combo.isDown(Minecraft.getInstance().window)
    }

    /** Gameplay key presses (KeyboardHandlerMixin): Escape cancels locating, the binding opens search. */
    fun handleKey(event: KeyEvent): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !mc.isWindowActive) return false
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && IslandChestDisplay.cancelLocating()) return true
        return handleBinding(InputConstants.getKey(event), mc)
    }

    fun handleMouse(button: Int): Boolean {
        val mc = Minecraft.getInstance()
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || !mc.isWindowActive) return false
        return handleBinding(InputConstants.Type.MOUSE.getOrCreate(button), mc)
    }

    private fun handleBinding(key: InputConstants.Key, mc: Minecraft): Boolean {
        val combo = KeyCombo.cached(cfg.islandChests.searchKey) ?: return false
        if (combo.key != key || !combo.isDown(mc.window)) return false
        return session.request(available(), true, false, mc.isWindowActive)
    }
}
