package com.soulreturns.features.sacks

import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/**
 * Authoritative sack-content snapshot path. When the player opens a Hypixel sack screen,
 * this reader scans every non-player-inventory slot and parses each item's lore for its
 * stored count, then calls [SackState.applySnapshot] with the resulting map.
 *
 * **Why a delay before scanning.** Hypixel populates slot items via inventory-update
 * packets *after* the screen object has been initialized — scanning inside
 * `ScreenEvents.AFTER_INIT` shows the placeholder pane (empty slots or the loading
 * "items will appear here" filler). [SCAN_DELAY_TICKS] = 3 client ticks (~150 ms) is
 * enough headroom on a normal connection. If the user closes the screen before the
 * delay elapses, the pending scan is dropped.
 *
 * **Title detection.** Sack screens have titles ending in `" Sack"` (singular) — e.g.
 * `"Combat Sack"`, `"Enchanted Mining Sack"`, `"Witch's Sack"`, `"Dragons Sack"`. The
 * Sack-of-Sacks menu and any category-tier submenus end in `"Sacks"` (plural) and are
 * excluded by the singular suffix check.
 *
 * **Player-inventory filter.** Reuses the same identity check (`slot.container ===
 * player.inventory`) the dev keybind dumper uses, so the bottom 36 hotbar+inventory+offhand
 * slots are skipped without per-menu-class dispatch.
 *
 * **Lore parse.** Each sack-item's lore contains a line like `§7Stored: §e12,677§7/22.2k`
 * (yellow when non-empty, dark-gray "0" when empty). Strip color codes then match
 * `^(?:Stored|Held):\s*([\d,]+)` to capture the count — supports both observed Hypixel
 * patterns. Capacity is ignored (not stored anywhere we care about).
 */
object SackGuiReader {
    private const val SCAN_DELAY_TICKS: Int = 3
    private val logger = SoulLogger("Soul/SackGui")
    private val STORED_COUNT_PATTERN = Regex("^(?:Stored|Held):\\s*([\\d,]+)")

    @Volatile private var pendingScreen: AbstractContainerScreen<*>? = null

    @Volatile private var pendingScanTick: Int = -1

    private var tickCounter: Int = 0

    @Volatile private var registered: Boolean = false

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is AbstractContainerScreen<*>) return@AfterInit
                val title = screen.title.string
                if (!isSackScreenTitle(title)) return@AfterInit
                pendingScreen = screen
                pendingScanTick = tickCounter + SCAN_DELAY_TICKS
            },
        )
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { mc ->
                tickCounter++
                val screen = pendingScreen ?: return@EndTick
                if (tickCounter < pendingScanTick) return@EndTick
                if (mc.screen !== screen) {
                    // Player navigated away before the scan window — drop without scanning.
                    pendingScreen = null
                    return@EndTick
                }
                try {
                    performScan(screen)
                } catch (t: Throwable) {
                    logger.warn("Sack scan threw: ${t.message}", t)
                } finally {
                    // One scan per AFTER_INIT — closing and reopening the same sack reschedules naturally.
                    pendingScreen = null
                }
            },
        )
    }

    private fun isSackScreenTitle(title: String): Boolean {
        // Exclude the meta menu ("Sack of Sacks") and category submenus ("Combat Sacks").
        // Real sack titles are always singular `… Sack`.
        if (!title.endsWith(" Sack")) return false
        if (title == "Sack of Sacks") return false
        return true
    }

    private fun performScan(screen: AbstractContainerScreen<*>) {
        val mc = Minecraft.getInstance()
        val playerInv: Inventory? = mc.player?.inventory
        val snapshot = HashMap<String, Long>()
        var skippedNoId = 0
        var skippedNoCount = 0
        for (slot in screen.menu.slots) {
            if (playerInv != null && slot.container === playerInv) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val id = SkyblockItemUtils.getSkyblockId(stack)
            if (id == null) {
                skippedNoId++
                continue
            }
            val count = parseStoredCount(stack)
            if (count == null) {
                skippedNoCount++
                continue
            }
            snapshot[id] = count
        }
        if (snapshot.isEmpty()) {
            logger.info(
                "Sack '${screen.title.string}' scan: no items captured (skippedNoId=$skippedNoId, skippedNoCount=$skippedNoCount)",
            )
            return
        }
        SackState.applySnapshot(snapshot, screen.title.string)
    }

    private fun parseStoredCount(stack: ItemStack): Long? {
        val lore = stack.get(DataComponents.LORE) ?: return null
        for (line in lore.lines) {
            val plain = MessageDetector.stripColorCodes(line.string).trim()
            val m = STORED_COUNT_PATTERN.find(plain) ?: continue
            return m.groupValues[1].replace(",", "").toLongOrNull()
        }
        return null
    }
}
