package com.soulreturns.features.combo

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack

/**
 * Drives the exact live combo count (and re-anchors the expiration timer) *between* the every-5-kills
 * chat milestones, by reading a kill-counter tag off an item in the player's inventory and diffing it
 * each tick. Hypixel pushes the updated item NBT to the client on each kill, so the counter climbs
 * live — we watch it and feed the delta to [ComboTracker.onKillDetected].
 *
 * The counter is the **source of truth** for the count; the chat milestones are only a *floor* — they
 * snap the count up if the counter fell behind, but never add a kill (see [setFloor] / the grace
 * window, and [ComboTracker.applyFloor]). This is what keeps the milestone that triggered a kill from
 * being counted twice.
 *
 * Counter source, highest priority first — the first one present in the inventory is the **sole**
 * source (a lower one is ignored entirely, so the two never mix):
 *  1. **Blood God Crest / Sigil** accessory — `blood_god_kills`. Counts *every* mob killed while
 *     worn, so it catches AoE / aura / ability kills that a weapon counter never sees, and it
 *     increments cleanly by one per kill — the accurate source.
 *  2. **Book of Stats** weapon — `stats_book`. Only kills credited to that weapon, AND Hypixel
 *     sometimes reports it in a lumpy way (e.g. +2 on one kill, +0 on the next — net-correct but
 *     transiently off by one), so the count/timer can be slightly inaccurate. Used only when no
 *     Blood God item is present; the config note flags this.
 *
 * If neither is in the inventory the countdown stays milestone-anchored (no per-kill reset) — the
 * documented fallback. The counter item must be in the main inventory/hotbar (where Hypixel streams
 * live NBT updates), not tucked away in the Accessory Bag, for the reset to fire.
 *
 * Reads own-inventory NBT only — no entity or line-of-sight interaction. Gated on the combo timer
 * toggle so it costs nothing when the feature is off.
 */
object ComboKillCounter {
    /** Counter keys in `CUSTOM_DATA`, highest priority first. */
    private val COUNTER_KEYS = listOf("blood_god_kills", "stats_book")

    /** The counter key currently in use (so a source switch re-baselines instead of firing kills). */
    private var activeKey: String? = null

    /** item uuid → last seen counter value, for the active key only. */
    private val seen = HashMap<String, Int>()

    /** Whether the last tick found a usable counter item (so chat handling knows to defer to it). */
    private var counterActive = false

    /**
     * A milestone value the chat announced, waiting to be applied as a *floor* (0 = none). The `+N`
     * chat and the triggering kill's counter NBT are independent packets, so we don't apply the floor
     * immediately — we wait [FLOOR_GRACE_TICKS] first. A merely-lagging counter catches up on its own
     * in that window (floor becomes a no-op, so the kill is never double-counted); a counter that
     * genuinely fell behind (e.g. 28 when the chat says +30) is snapped up. See [ComboTracker.applyFloor].
     */
    private var pendingFloor = 0
    private var floorDelay = 0

    private const val FLOOR_GRACE_TICKS = 4

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    /** Whether a kill counter is currently driving the count (chat should defer to it, not set the count). */
    fun hasCounter(): Boolean = counterActive

    /** A milestone chat announced [n] — apply it as a delayed floor (never counts as a kill). */
    fun setFloor(n: Int) {
        pendingFloor = n
        floorDelay = FLOOR_GRACE_TICKS
    }

    /** Drop any pending floor (e.g. the combo just expired). */
    fun cancelFloor() {
        pendingFloor = 0
        floorDelay = 0
    }

    private fun tick(mc: Minecraft) {
        // Runs when the combo overlay is on and either the live count or the timer would use it.
        if (!cfg.combo.enabled || !(cfg.combo.showCount || cfg.combo.showExpireTimer)) return clear()
        val player = mc.player ?: return clear()
        val items = player.inventory.getNonEquipmentItems()

        // Highest-priority counter any inventory item carries (Blood God overrides Book of Stats).
        val key = COUNTER_KEYS.firstOrNull { k -> items.any { readInt(it, k) != null } } ?: return clear()
        counterActive = true
        if (key != activeKey) {
            activeKey = key
            seen.clear() // new source → establish a fresh baseline, don't count the jump as kills
        }

        var kills = 0
        val present = HashSet<String>()
        for (st in items) {
            val value = readInt(st, key) ?: continue
            val uuid = readString(st, "uuid") ?: continue
            present += uuid
            val prev = seen.put(uuid, value)
            if (prev != null && value > prev) kills += value - prev
        }
        seen.keys.retainAll(present) // forget items that left the inventory
        if (kills > 0) ComboTracker.onKillDetected(kills)

        // Apply the milestone floor only after the counter has had a few ticks to catch up on its own.
        if (pendingFloor > 0 && --floorDelay <= 0) {
            ComboTracker.applyFloor(pendingFloor)
            pendingFloor = 0
        }
    }

    private fun clear() {
        activeKey = null
        counterActive = false
        if (seen.isNotEmpty()) seen.clear()
    }

    /** Read a non-negative int [key] from the stack's `CUSTOM_DATA`, or null if absent/invalid. */
    private fun readInt(stack: ItemStack, key: String): Int? {
        if (stack.isEmpty) return null
        return try {
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
            tag.getIntOr(key, -1).takeIf { it >= 0 }
        } catch (_: Exception) {
            null
        }
    }

    private fun readString(stack: ItemStack, key: String): String? {
        return try {
            val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
            tag.getStringOr(key, "").ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}
