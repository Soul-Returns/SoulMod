package com.soulreturns.features.inventory

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.hypixel.LegacyItemDecoder
import com.soulreturns.data.hypixel.path
import com.soulreturns.data.hypixel.string
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SelfProfileLoaded
import com.soulreturns.data.skyblock.SelfProfileSnapshot
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * The four SkyBlock equipment slots (necklace, cloak, belt, gloves) in a slot column outside the
 * player inventory, left of the armor column, with the item tooltip on hover.
 *
 * Prefilled from the self profile's `equipment_contents` (`rift.inventory.equipment_contents` for
 * the Rift), then replaced by what the equipment and loadouts menus show while open: both keep the
 * current equipment in their second column, rows 1-4. A menu read wins over every later API
 * prefill on the same profile, since Hypixel's API lags by minutes. Session memory only.
 */
object EquipmentDisplay {
    private const val SIZE = 4
    private const val MAIN = "main"

    /** Islands with their own equipment set (Location API modes); everything else is [MAIN]. */
    private val SEPARATE_WORLDS = setOf("rift", "safari")

    /** Container slots of the equipment column in both menus (column 1, rows 1-4). */
    private val MENU_SLOTS = intArrayOf(10, 19, 28, 37)
    private val LOADOUTS_TITLE = Regex("(?:\\(\\d+/\\d+\\) )?Loadouts")

    // Slots are cut from the inventory texture, so Dark UI recolors them like the armor slots.
    private const val SLOT = 18
    private const val GAP = 2

    // Joined frame: the inventory's border grows 18 px left (7 px border + margin, then a flush slot), 86 px tall.
    private const val FLUSH_INSET = 11
    private const val FRAME_EXTENSION = 18
    private const val FRAME_HEIGHT = 86
    private const val CORNER = 7
    private const val INVENTORY_WIDTH = 176
    private const val INVENTORY_HEIGHT = 166
    private val SLOT_HIGHLIGHT_BACK = Identifier.withDefaultNamespace("container/slot_highlight_back")
    private val SLOT_HIGHLIGHT_FRONT = Identifier.withDefaultNamespace("container/slot_highlight_front")

    private class EquipmentSet(val items: List<ItemStack>, val fromMenu: Boolean)

    private val sets = HashMap<String, EquipmentSet>()
    private var profile: String? = null
    private var prefilledFrom: SelfProfileSnapshot? = null

    /** Last drawn panel (x0, y0, x1, y1) in GUI px; clicks there are consumed. */
    private var panelRect: IntArray? = null

    fun active(): Boolean = cfg.general.equipmentDisplay && SkyblockState.onSkyblock

    fun register() {
        Events.subscribe<ProfileChanged> { event ->
            // Leaving SkyBlock (-> null) keeps the sets; only another profile replaces them.
            val now = event.current ?: return@subscribe
            if (now == profile) return@subscribe
            profile = now
            sets.clear()
            prefilledFrom = null
        }
        Events.subscribe<SelfProfileLoaded> { prefill(it.snapshot) }

        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen is InventoryScreen) {
                    SkyblockSelfProfile.snapshot?.let(::prefill)
                    ScreenEvents.afterBackground(screen).register(
                        ScreenEvents.AfterBackground { _, g, mouseX, mouseY, _ -> draw(g, screen, mouseX, mouseY) },
                    )
                    // Vanilla drops the carried stack on a click outside the frame; the panel is not outside.
                    ScreenMouseEvents.allowMouseClick(screen).register(
                        ScreenMouseEvents.AllowMouseClick { _, click -> !inPanel(click.x(), click.y()) },
                    )
                } else if (screen is AbstractContainerScreen<*> && isEquipmentMenu(screen.title.string)) {
                    ScreenEvents.afterTick(screen).register(ScreenEvents.AfterTick { read(screen) })
                }
            },
        )
    }

    private fun world(): String = LocationApi.mode?.takeIf { it in SEPARATE_WORLDS } ?: MAIN

    private fun isEquipmentMenu(rawTitle: String): Boolean {
        val title = MessageDetector.stripColorCodes(rawTitle).trim()
        if (LOADOUTS_TITLE.matches(title)) return true
        // "Your Equipment and Stats" / "Stats & Equipment"; not the "Equipment Sets" wardrobe.
        return title.contains("Equipment", ignoreCase = true) && title.contains("Stats", ignoreCase = true)
    }

    private fun prefill(snapshot: SelfProfileSnapshot) {
        if (!cfg.general.equipmentDisplay || prefilledFrom === snapshot) return
        prefilledFrom = snapshot
        val raw = snapshot.member.raw
        val blobs = mapOf(
            MAIN to raw.path("inventory", "equipment_contents")?.string("data"),
            "rift" to raw.path("rift", "inventory", "equipment_contents")?.string("data"),
        )
        // The data fixer is slow on first use; never on the client thread.
        SoulScope.io.launch {
            val decoded = blobs.mapNotNull { (world, blob) -> blob?.let { world to padded(LegacyItemDecoder.decode(it)) } }
            Minecraft.getInstance().execute {
                if (!snapshot.profileName.equals(SkyblockProfileState.current, ignoreCase = true)) return@execute
                for ((world, items) in decoded) {
                    if (sets[world]?.fromMenu != true) sets[world] = EquipmentSet(items, fromMenu = false)
                }
            }
        }
    }

    private fun padded(items: List<ItemStack>): List<ItemStack> = List(SIZE) { items.getOrNull(it) ?: ItemStack.EMPTY }

    private fun read(screen: AbstractContainerScreen<*>) {
        if (!cfg.general.equipmentDisplay) return
        val slots = screen.menu.slots
        if (slots.size <= MENU_SLOTS.last() || slots[MENU_SLOTS.last()].container is Inventory) return
        val shown = MENU_SLOTS.map { slots[it].item }
        // Hypixel fills every position, a placeholder where nothing is equipped; air means the
        // contents have not arrived yet.
        if (shown.any { it.isEmpty }) return
        // Worn equipment carries its SkyBlock id; placeholders and filler do not.
        val items = shown.map { if (SkyblockItemUtils.getSkyblockId(it) != null) it.copy() else ItemStack.EMPTY }
        val world = world()
        val current = sets[world]
        if (current != null && current.fromMenu && current.items.indices.all { ItemStack.matches(current.items[it], items[it]) }) return
        sets[world] = EquipmentSet(items, fromMenu = true)
    }

    private fun inPanel(x: Double, y: Double): Boolean {
        val r = panelRect ?: return false
        return x >= r[0] && x < r[2] && y >= r[1] && y < r[3]
    }

    /**
     * The GUI-px rect (x0, y0, x1, y1) the display covers beside an inventory frame at ([leftPos], [topPos])
     * on a [screenWidth]-wide screen: the slots, plus the extended frame when joined. Null when it is not
     * drawn (off, not in SkyBlock, recipe book open, or no room left of the frame). The one source for
     * [draw] and for the inventory buttons that must not sit under it.
     */
    fun occupiedRect(screenWidth: Int, leftPos: Int, topPos: Int, imageWidth: Int = INVENTORY_WIDTH): IntArray? {
        if (!active()) return null
        // An open recipe book shifts the inventory right and covers this side.
        if (leftPos != (screenWidth - imageWidth) / 2) return null
        val joined = cfg.general.equipmentJoinFrame
        val x0 = leftPos - if (joined) FLUSH_INSET else GAP + SLOT
        if (leftPos - (if (joined) FRAME_EXTENSION else 0) < 0 || x0 < 0) return null
        return if (joined) {
            intArrayOf(leftPos - FRAME_EXTENSION, topPos, leftPos, topPos + FRAME_HEIGHT)
        } else {
            intArrayOf(x0, topPos + 7, x0 + SLOT, topPos + 7 + SIZE * SLOT)
        }
    }

    /** Frame + slots with the cached equipment at an inventory frame at ([left], [top]), for the button editor's preview. */
    fun drawBackdrop(g: GuiGraphicsExtractor, left: Int, top: Int) {
        val joined = cfg.general.equipmentJoinFrame
        if (joined) drawFrame(g, left, top)
        val x0 = left - if (joined) FLUSH_INSET else GAP + SLOT
        drawSlots(g, x0, top + 7)
        val font = Minecraft.getInstance().font
        val items = sets[world()]?.items ?: return
        for (i in 0 until SIZE) {
            val stack = items[i]
            if (stack.isEmpty) continue
            g.item(stack, x0 + 1, top + 8 + i * 18)
            g.itemDecorations(font, stack, x0 + 1, top + 8 + i * 18)
        }
    }

    private fun draw(g: GuiGraphicsExtractor, screen: InventoryScreen, mouseX: Int, mouseY: Int) {
        panelRect = null
        if (occupiedRect(screen.width, screen.leftPos, screen.topPos, screen.imageWidth) == null) return
        val joined = cfg.general.equipmentJoinFrame
        val x0 = screen.leftPos - if (joined) FLUSH_INSET else GAP + SLOT
        val y0 = screen.topPos + 7
        if (joined) drawFrame(g, screen.leftPos, screen.topPos)
        drawSlots(g, x0, y0)
        panelRect = intArrayOf(x0, y0, x0 + SLOT, y0 + SIZE * SLOT)

        val mc = Minecraft.getInstance()
        val items = sets[world()]?.items
        val carrying = mc.player?.containerMenu?.carried?.isEmpty == false
        for (i in 0 until SIZE) {
            val sx = x0 + 1
            val sy = y0 + 1 + i * 18
            val hovered = mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16
            if (hovered) g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_BACK, sx - 4, sy - 4, 24, 24)
            val stack = items?.get(i) ?: ItemStack.EMPTY
            if (!stack.isEmpty) {
                g.item(stack, sx, sy)
                g.itemDecorations(mc.font, stack, sx, sy)
            }
            if (hovered) g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_HIGHLIGHT_FRONT, sx - 4, sy - 4, 24, 24)
            if (hovered && !stack.isEmpty && !carrying) g.setTooltipForNextFrame(mc.font, stack, mouseX, mouseY)
        }
    }

    /** Four vanilla slot backgrounds (inventory.png's armor slots, u 7 v 7, 18 px pitch) at (x, y). */
    private fun drawSlots(g: GuiGraphicsExtractor, x: Int, y: Int) {
        for (k in 0 until SIZE) {
            g.blit(RenderPipelines.GUI_TEXTURED, InventoryScreen.INVENTORY_LOCATION, x, y + k * 18, 7f, 7f + k * 18, 18, 18, 256, 256)
        }
    }

    /**
     * Extends the inventory's frame left around the column, cut from inventory.png so Dark UI
     * recolors it: corners, top/bottom edges (the bottom ends at the inventory's own left border,
     * which continues down and forms the inner corner), the left edge, and a fill over
     * the inventory's left border where it would cut through the column.
     */
    private fun drawFrame(g: GuiGraphicsExtractor, left: Int, top: Int) {
        val tex = InventoryScreen.INVENTORY_LOCATION
        val p = RenderPipelines.GUI_TEXTURED
        val x = left - FRAME_EXTENSION
        val bottom = top + FRAME_HEIGHT - CORNER
        val bv = (INVENTORY_HEIGHT - CORNER).toFloat()
        g.blit(p, tex, x, top, 0f, 0f, CORNER, CORNER, 256, 256)
        g.blit(p, tex, x + CORNER, top, 7f, 0f, SLOT, CORNER, 256, 256)
        g.blit(p, tex, x, top + CORNER, 0f, 7f, CORNER, SIZE * SLOT, 256, 256)
        g.blit(p, tex, x, bottom, 0f, bv, CORNER, CORNER, 256, 256)
        g.blit(p, tex, x + CORNER, bottom, 7f, bv, FRAME_EXTENSION - CORNER, CORNER, 256, 256)
        // The inventory's left border (x 0..2) over the column's rows becomes interior (u 3, v 79..82 is flat).
        var y = top + CORNER
        while (y < top + FRAME_HEIGHT - 3) {
            val h = minOf(4, top + FRAME_HEIGHT - 3 - y)
            g.blit(p, tex, left, y, 3f, 79f, 3, h, 256, 256)
            y += h
        }
    }
}
