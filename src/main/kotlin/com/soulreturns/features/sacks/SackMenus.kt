package com.soulreturns.features.sacks

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.TrackedSackItem
import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.inventorybuttons.InventoryButtons
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.soulChat
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * Sack menus: exact counts and the tracked-list toggle.
 *
 * - **A sack menu** is a container titled `<Name> Sack` (`Fishing Sack`, `Enchanted Foraging Sack`;
 *   the `Sack of Sacks` overview ends in "Sacks" and holds sacks, not items). Its items carry their
 *   SkyBlock id and a `Stored: N/cap` lore line; anything else (borders, buttons, gemstone formats)
 *   is skipped.
 * - **Read every tick while open.** Hypixel pushes slot updates as counts change, so an open sack is a
 *   live exact source. Stacks are compared by identity (a slot update replaces the instance), so
 *   only changed slots are parsed.
 * - **Middle click** on a sack item toggles it on the tracked list; the click is consumed (it never
 *   reaches the server). It reads the raw button before slot handling, and stands down while the
 *   inventory buttons' icon pick owns the middle click ([InventoryButtons.picking]).
 * - Tracked items get a small gold corner marker, drawn from the container mixin's slot hook.
 */
object SackMenus {
    private val MARKER = 0xFFFFAA00.toInt()

    /** Container slot index → the parsed sack item there (render + tick thread = client thread). */
    private val slotItems = HashMap<Int, SackItem>()
    private val parsedFrom = HashMap<Int, ItemStack>()

    /** The screen [slotItems] belongs to; any other screen clears it. */
    private var screenSeen: AbstractContainerScreen<*>? = null

    private var trackedCache: List<TrackedSackItem>? = null
    private var trackedIds: Set<String> = emptySet()

    /** One readable sack item. */
    data class SackItem(val id: String, val name: String, val count: Long)

    fun register() {
        SackReporter.register()
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { scan() })
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is AbstractContainerScreen<*>) return@AfterInit
                ScreenMouseEvents.allowMouseClick(screen).register(
                    ScreenMouseEvents.AllowMouseClick { _, click -> !click(screen, click) },
                )
            },
        )
    }

    fun isSackMenu(screen: AbstractContainerScreen<*>): Boolean {
        val title = SackParsing.strip(screen.title.string).trim()
        return title.endsWith(" Sack")
    }

    /** The sack item [stack] shows, or null when it is not one (no id, no `Stored:` line). */
    fun read(stack: ItemStack): SackItem? {
        if (stack.isEmpty) return null
        val id = SkyblockItemUtils.getSkyblockId(stack) ?: return null
        val lore = stack.get(DataComponents.LORE)?.lines() ?: return null
        val count = SackParsing.storedCount(lore) ?: return null
        return SackItem(id, SackParsing.strip(stack.hoverName.string).trim(), count)
    }

    private fun active(): AbstractContainerScreen<*>? {
        if (!SackState.enabled() || !SkyblockState.onSkyblock) return null
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*> ?: return null
        return screen.takeIf { isSackMenu(it) }
    }

    private fun scan() {
        val screen = active()
        if (screen !== screenSeen) {
            slotItems.clear()
            parsedFrom.clear()
            screenSeen = screen
        }
        if (screen == null) return
        val now = System.currentTimeMillis()
        for (slot in screen.menu.slots) {
            if (slot.container is Inventory) continue
            val stack = slot.item
            val index = slot.index
            if (parsedFrom[index] !== stack) {
                parsedFrom[index] = stack
                val item = read(stack)
                if (item == null) slotItems.remove(index) else slotItems[index] = item
            }
            val item = slotItems[index] ?: continue
            SackState.onMenuRead(item.id, item.name, item.count, now)
        }
    }

    /** @return true when the click toggled a tracked item and must not reach the screen. */
    private fun click(screen: AbstractContainerScreen<*>, click: MouseButtonEvent): Boolean {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_MIDDLE || InventoryButtons.picking) return false
        // Tracking is the Sack tracker's own UX: other count consumers never take the middle click.
        if (!cfg.sacks.hud || active() !== screen) return false
        val slot = screen.hoveredSlot ?: return false
        if (slot.container is Inventory) return false
        val item = read(slot.item) ?: return false
        toggle(item, slot.item)
        return true
    }

    private fun toggle(item: SackItem, stack: ItemStack) {
        val tracked = cfg.sacks.tracked
        if (tracked.any { it.id == item.id }) {
            untrack(item.id)
            soulChat("§7No longer tracking §r${item.name}§7.")
            return
        }
        val look = InventoryButtonIcons.look(stack)
        val entry = TrackedSackItem(
            id = item.id,
            name = item.name,
            color = nameColor(stack),
            item = look.item,
            skin = look.skin,
            model = look.model,
            glint = look.glint,
        )
        ConfigManager.update { copy(sacks = sacks.copy(tracked = sacks.tracked + entry)) }
        SackState.onTracked(item.id)
        soulChat(String.format(Locale.ROOT, "§aTracking §r%s§a (%,d in sacks).", item.name, item.count))
    }

    /** Remove [id] from the tracked list (sack menu middle click or the panel's `×`). */
    fun untrack(id: String) {
        ConfigManager.update { copy(sacks = sacks.copy(tracked = sacks.tracked.filterNot { it.id == id })) }
        SackState.onUntracked(id)
    }

    fun isTracked(id: String): Boolean {
        val list = cfg.sacks.tracked
        if (list !== trackedCache) {
            trackedCache = list
            trackedIds = list.mapTo(HashSet()) { it.id }
        }
        return id in trackedIds
    }

    /** Container slot hook (`AbstractContainerScreenMixin`, after the item): the tracked marker. */
    @JvmStatic
    fun onExtractSlot(g: GuiGraphicsExtractor, slot: Slot) {
        val screen = screenSeen ?: return
        if (slot.container is Inventory || Minecraft.getInstance().gui.screen() !== screen) return
        if (!cfg.sacks.hud) return
        val item = slotItems[slot.index] ?: return
        if (!isTracked(item.id)) return
        // A 4 px corner triangle, top left: the count text owns the bottom right.
        for (row in 0 until 4) g.fill(slot.x, slot.y + row, slot.x + 4 - row, slot.y + row + 1, MARKER)
    }

    /** The first legacy color of the item name (styled or literal `§` codes), as ARGB. */
    private fun nameColor(stack: ItemStack): Int {
        val legacy = stack.hoverName.toLegacyText()
        var i = legacy.indexOf('§')
        while (i >= 0 && i + 1 < legacy.length) {
            val code = Character.digit(legacy[i + 1].lowercaseChar(), 16)
            if (code >= 0) return LEGACY_RGB[code] or 0xFF000000.toInt()
            i = legacy.indexOf('§', i + 2)
        }
        return 0xFFFFFFFF.toInt()
    }

    private val LEGACY_RGB =
        intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )
}
