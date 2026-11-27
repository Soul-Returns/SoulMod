package com.soulreturns.features.islandchests

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.dungeons.DungeonCommandGuard
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.search.SearchItems
import com.soulreturns.ui.SlotMark
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.HudElement
import com.soulreturns.ui.hud.SoulHud
import com.soulreturns.util.KeyCombo
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

object IslandChestDisplay {
    /** How long "Warp home & locate" waits for the arrival on the player's own island. */
    private const val WARP_TIMEOUT_MS = 60_000L
    private var registered = false
    private var warpProfile: String? = null
    private var warpKey: String? = null
    private var warpSince = 0L
    private var locatingProfile: String? = null
    private var previousKey: String? = null
    private var cachedLevel: Any? = null
    private data class Shape(val state: BlockState, val bounds: List<AABB>)
    private val shapes = HashMap<ChestPos, Shape>()
    private val positions = HashMap<ChestPos, BlockPos>()
    private var shapeResult: ChestSearchResult? = null

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            arrivedHome()
            val result = IslandChests.locating()
            val view = IslandChests.view()
            if (result == null) {
                locatingProfile = null
                previousKey = null
                shapes.clear()
            } else {
                if (previousKey != result.key) {
                    previousKey = result.key
                    locatingProfile = view.profileId
                    shapes.clear()
                }
                if (!allowed(view)) {
                    IslandChests.clearLocating()
                    locatingProfile = null
                    previousKey = null
                    shapes.clear()
                } else updateShapes(mc, result)
            }
        }
        LevelRenderEvents.BEFORE_GIZMOS.register { renderWorld() }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) {
                ScreenEvents.afterBackground(screen).register { _, g, _, _, _ -> highlightSlots(screen, g) }
            }
        }
        SoulHud.add(LocatorHud)
    }

    /** Outline [key]'s chests; the player is on their own island. True when locating started. */
    internal fun locate(key: String): Boolean {
        locatingProfile = IslandChests.view().profileId
        previousKey = key
        IslandChests.startLocating(key)
        return IslandChests.locating() != null
    }

    /** Off-island hit (approved mock option): send /warp home, then outline [key]'s chests on arrival. */
    internal fun warpHomeAndLocate(key: String): Boolean {
        val profile = IslandChests.view().profileId ?: return false
        val connection = Minecraft.getInstance().connection ?: return false
        if (DungeonCommandGuard.blocks("warp home")) return false
        warpProfile = profile
        warpKey = key
        warpSince = System.currentTimeMillis()
        connection.sendCommand("warp home")
        return true
    }

    private fun arrivedHome() {
        val key = warpKey ?: return
        val view = IslandChests.view()
        val expired = System.currentTimeMillis() - warpSince > WARP_TIMEOUT_MS
        if (expired || !cfg.islandChests.enabled || (view.profileId != null && view.profileId != warpProfile)) {
            warpKey = null
            warpProfile = null
            return
        }
        if (!view.ownIsland || view.profileId != warpProfile) return
        warpKey = null
        warpProfile = null
        locate(key)
    }

    /** Escape while locating cancels it; the press is consumed so the pause menu stays closed. */
    fun cancelLocating(): Boolean {
        if (active() == null) return false
        IslandChests.clearLocating()
        return true
    }

    /** Stops locating for a return to search and hands back the located item's key. */
    internal fun takeLocating(): String? = IslandChests.locating()?.key.also { IslandChests.clearLocating() }

    private fun allowed(view: IslandChestView) = IslandChestLayout.canLocate(cfg.islandChests.enabled, view.ownIsland, view.profileId, locatingProfile)

    private fun active(): ChestSearchResult? {
        if (!allowed(IslandChests.view())) return null
        return IslandChests.locating()
    }

    private fun highlightSlots(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        if (!allowed(IslandChests.view())) return
        val matches = IslandChests.locatingSlots(screen.menu.containerId) ?: return
        for (index in matches.indices) {
            if (!matches[index]) continue
            val slot = screen.menu.slots.getOrNull(index) ?: continue
            val x = screen.leftPos + slot.x
            val y = screen.topPos + slot.y
            SlotMark.draw(g, x, y, MATCH)
        }
    }

    private fun updateShapes(mc: Minecraft, result: ChestSearchResult) {
        val level = mc.level ?: return
        if (cachedLevel !== level) {
            cachedLevel = level
            shapes.clear()
        }
        if (shapeResult !== result) {
            shapeResult = result
            val wanted = result.matches.flatMap { it.chest.blocks }.toSet()
            shapes.keys.retainAll(wanted)
            positions.keys.retainAll(wanted)
            for (block in wanted) positions.getOrPut(block) { BlockPos(block.x, block.y, block.z) }
        }
        for (match in result.matches) for (block in match.chest.blocks) {
            val pos = positions[block] ?: continue
            if (!level.hasChunkAt(pos)) { shapes.remove(block); continue }
            val state = level.getBlockState(pos)
            if (shapes[block]?.state !== state) {
                val chest = (state.block === Blocks.CHEST || state.block === Blocks.TRAPPED_CHEST) && BuiltInRegistries.BLOCK.getKey(state.block).toString() == match.chest.blockId
                shapes[block] = Shape(state, if (chest) state.getShape(level, pos).toAabbs().map { it.move(pos).inflate(0.003) } else emptyList())
            }
        }
    }

    private fun renderWorld() {
        val result = active() ?: return
        val mc = Minecraft.getInstance()
        if (mc.level == null) return
        val camera = mc.gameRenderer.mainCamera()
        val eye = camera.position()
        val newest = result.matches.maxOfOrNull { it.chest.observedAt } ?: return
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for ((index, match) in result.matches.withIndex()) {
                val older = match.chest.observedAt < newest
                val color = if (older) OLD else MATCH
                var hasShape = false
                for (block in match.chest.blocks) {
                    val cached = shapes[block] ?: continue
                    for (box in cached.bounds) Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, 2f, (color and 0xFFFFFF) or 0x18000000)).setAlwaysOnTop()
                    hasShape = hasShape || cached.bounds.isNotEmpty()
                }
                if (!hasShape) continue
                val at = center(match.chest) ?: continue
                val text = "${IslandChestLayout.marker(index)} · ${SearchItems.number(match.count)} · ${distance(match.chest)?.roundToInt()}m" + if (older) " · older check" else ""
                val scale = maxOf(0.5f, (eye.distanceTo(at) * 0.06).toFloat())
                Gizmos.billboardText(text, at.add(0.0, 1.1, 0.0), TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop()
            }
        }
    }

    private fun center(chest: StoredIslandChest): Vec3? {
        if (chest.blocks.isEmpty()) return null
        return Vec3(chest.blocks.sumOf { it.x + 0.5 } / chest.blocks.size, chest.blocks.sumOf { it.y + 0.5 } / chest.blocks.size, chest.blocks.sumOf { it.z + 0.5 } / chest.blocks.size)
    }

    internal fun distance(chest: StoredIslandChest): Double? {
        if (!IslandChests.view().ownIsland) return null
        val player = Minecraft.getInstance().player ?: return null
        return center(chest)?.distanceTo(Vec3(player.x, player.y, player.z))
    }

    private object LocatorHud : HudElement("island_chest_locator") {
        override val foreground: Boolean get() = true
        override fun isEnabled(): Boolean = Minecraft.getInstance().gui.screen() == null && active() != null

        override fun extract(g: GuiGraphicsExtractor) {
            val result = active() ?: return
            val mc = Minecraft.getInstance()
            val width = (mc.window.guiScaledWidth / SoulUi.factor()).toInt()
            val panelWidth = minOf(680, width - 2 * minOf(22, width / 12))
            val left = (width - panelWidth) / 2
            val right = left + panelWidth
            val compact = right - left < 660
            val titleY = 48
            val controlsY = if (compact) titleY + 76 else titleY + 15
            val h = if (compact) 135 else 103
            rect(g, left, titleY, right, titleY + h, 0xEB1A1A1A.toInt())
            SearchItems.icon(g, result.item, left + 15, titleY + 18, 28)
            text(g, "LOCATING CACHED CONTENTS", left + 53, titleY + 12, MUTED, 9)
            val title = SearchItems.name(result.item)
            text(g, fit(title, if (compact) right - left - 68 else (right - left) / 2 - 25, 19), left + 53, titleY + 27, SearchItems.color(result.item), 19)
            text(g, "${result.matches.size} matching ${IslandChestLayout.chests(result.matches.size)} · ${SearchItems.number(result.count)} last observed", left + 15, titleY + 57, 0xFFB9D5F8.toInt(), 12)
            val controlsX = if (compact) left + 15 else left + (right - left) / 2 + 12
            text(g, "${SearchEverywhere.backLabel()} [${KeyCombo.label(cfg.islandChests.searchKey)}]", controlsX, controlsY, WHITE, 11)
            text(g, "Cancel locating [Esc]", controlsX, controlsY + 19, WHITE, 11)
            text(g, "Both halves outlined · look at a chest for details · open it normally", left + 15, titleY + h - 20, MUTED, 10)
            text(g, "Blue: last observed match    Amber: checked before the newest matching chest", left, titleY + h + 8, MUTED, 9)
            val hit = mc.hitResult as? BlockHitResult ?: return
            val focused = result.matches.withIndex().firstOrNull { (_, match) -> match.chest.blocks.any { it.x == hit.blockPos.x && it.y == hit.blockPos.y && it.z == hit.blockPos.z } } ?: return
            val height = (mc.window.guiScaledHeight / SoulUi.factor()).toInt()
            val fy = height - 150
            val textW = minOf(350, right - left)
            val fx = (width - textW) / 2
            rect(g, fx, fy, fx + textW, fy + 63, 0xEB1A1A1A.toInt())
            text(g, "${IslandChestLayout.marker(focused.index)} · ${SearchItems.number(focused.value.count)} last observed", fx + 12, fy + 10, WHITE, 12)
            text(g, fit("Checked ${IslandChestLayout.age(focused.value.chest.observedAt, System.currentTimeMillis())} · by ${focused.value.chest.observerName ?: focused.value.chest.observerUuid.take(8)}", textW - 24, 10), fx + 12, fy + 29, MUTED, 10)
            text(g, "Information only. Open the chest normally to refresh.", fx + 12, fy + 47, MUTED, 9)
        }
    }

    private fun rect(g: GuiGraphicsExtractor, x: Int, y: Int, right: Int, bottom: Int, color: Int) =
        SoulPanel.draw(g, x - 1, y - 1, right + 1, bottom + 1, SoulPanel.MEDIUM, color, 0xFF3E4856.toInt())

    private fun text(g: GuiGraphicsExtractor, value: String, x: Int, y: Int, color: Int, size: Int) = SoulText.draw(g, value, x, y, SearchItems.em(size), color, MsdfFonts.menuRegular, forceCustom = true)

    private fun fit(value: String, width: Int, size: Int): String {
        val font = MsdfFonts.menuRegular
        val s = SearchItems.em(size)
        if (SoulText.width(value, s, font, forceCustom = true) <= width) return value
        var end = value.length
        while (end > 0 && SoulText.width(value.substring(0, end) + "…", s, font, forceCustom = true) > width) end--
        return value.substring(0, end) + "…"
    }

    private val MATCH = 0xFF72BBFF.toInt()
    private val OLD = 0xFFEFB75A.toInt()
    private val WHITE = 0xFFEEEEEE.toInt()
    private val MUTED = 0xFF888888.toInt()
}
