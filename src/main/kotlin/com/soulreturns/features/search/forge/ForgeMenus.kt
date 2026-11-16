package com.soulreturns.features.search.forge

import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.recipes.RecipeCatalogClient
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.abs

/**
 * Reads the player's forge state from Hypixel's menus while they are open (research §4b): The Forge (slot
 * states), Select Process (category order), category / search result pages (each recipe's place, this
 * profile's locks, Quick Forge from shown ÷ base duration) and Heart of the Mountain (the Quick Forge perk's
 * `by X%.` line). Everything is remembered per profile in [ForgeStore]. Client thread; a menu is parsed
 * again only when one of its stacks changed.
 */
object ForgeMenus {
    private var screenSeen: AbstractContainerScreen<*>? = null
    private var stacks: Array<ItemStack?> = emptyArray()
    private var lastSlotWrite = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { scan() })
    }

    private fun scan() {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        val ready = screen != null && Forge.enabled() && SkyblockState.onSkyblock && SkyblockProfileState.current != null
        if (!ready) {
            screenSeen = null
            return
        }
        val slots = screen.menu.slots.filter { it.container !is Inventory }
        var changed = screen !== screenSeen || stacks.size != slots.size
        if (changed) stacks = arrayOfNulls(slots.size)
        for ((i, slot) in slots.withIndex()) if (stacks[i] !== slot.item) { stacks[i] = slot.item; changed = true }
        screenSeen = screen
        if (!changed) return
        val title = MessageDetector.stripColorCodes(screen.title.string).trim()
        val items = slots.associate { it.index to it.item }
        val now = System.currentTimeMillis()
        if (title == ForgeParsing.FORGE_TITLE) readForge(items, now)
        else if (title == ForgeParsing.HOTM_TITLE) readHotm(items, now)
        else if (ForgeParsing.isPicker(title)) readPicker(items)
        else readRecipePage(title, items, now)
        learnLooks(screen.menu.slots.map { it.item })
    }

    /** The exact look of every recipe id seen in any menu or the inventory: what Hypixel itself draws (item model, skin). */
    private fun learnLooks(stacks: List<ItemStack>) {
        val ids = RecipeCatalogClient.index.ids
        if (ids.isEmpty()) return
        val known = ForgeStore.book().looks
        var learned: HashMap<String, ForgeLook>? = null
        for (stack in stacks) {
            if (stack.isEmpty) continue
            val id = ForgeItems.keyOf(stack) ?: continue
            if (id !in ids) continue
            val look = ForgeLook.of(InventoryButtonIcons.look(stack))
            if (known[id] == look || learned?.get(id) == look) continue
            (learned ?: HashMap<String, ForgeLook>().also { learned = it })[id] = look
        }
        learned?.let { fresh -> ForgeStore.updateBook { copy(looks = looks + fresh) } }
    }

    private fun name(stack: ItemStack) = MessageDetector.stripColorCodes(stack.hoverName.string).trim()

    private fun lore(stack: ItemStack): List<String> =
        stack.get(DataComponents.LORE)?.lines()?.map { MessageDetector.stripColorCodes(it.string) }.orEmpty()

    private fun readForge(items: Map<Int, ItemStack>, now: Long) {
        // Wait for the contents: every forge slot holds something once the menu is filled.
        if (ForgeParsing.FORGE_SLOTS.any { items[it]?.isEmpty != false }) return
        val qf = (ForgeStore.read().quickForge ?: 0.0) / 100
        val reads = ForgeParsing.FORGE_SLOTS.mapIndexed { i, index ->
            val stack = items.getValue(index)
            val parsed = ForgeParsing.forgeSlot(SkyblockItemUtils.getSkyblockId(stack), name(stack), lore(stack))
            if (parsed is ForgeParsing.Busy) {
                ForgeSlotRead(i + 1, "busy", ForgeItems.keyOf(stack) ?: parsed.outputId, parsed.name,
                    Math.round(parsed.remainingSeconds / (1 - qf).coerceAtLeast(0.01)), now)
            } else ForgeSlotRead(i + 1, if (parsed == ForgeParsing.Idle) "idle" else "unknown", readAt = now)
        }
        val old = ForgeStore.read().slots
        val same = old.size == reads.size && old.indices.all { old[it].state == reads[it].state && old[it].outputId == reads[it].outputId }
        // The countdown changes the lore every second; rewrite only on a state change or every 30 s.
        if (same && now - lastSlotWrite < 30_000L) return
        lastSlotWrite = now
        ForgeStore.update { copy(slots = reads, slotsAt = now) }
    }

    private fun readHotm(items: Map<Int, ItemStack>, now: Long) {
        for (stack in items.values) {
            if (stack.isEmpty || name(stack) != "Quick Forge") continue
            val material = BuiltInRegistries.ITEM.getKey(stack.item).toString()
            val qf = ForgeParsing.quickForge(material, lore(stack)) ?: return
            val data = ForgeStore.read()
            if (data.quickForge == qf.percent && data.quickForgeLevel == qf.level && data.quickForgeFrom == "hotm") return
            ForgeStore.update { copy(quickForge = qf.percent, quickForgeLevel = qf.level, quickForgeFrom = "hotm", quickForgeAt = now) }
            return
        }
    }

    private fun readPicker(items: Map<Int, ItemStack>) {
        val names = items.entries.sortedBy { it.key }.map { it.value }
            .filter { !it.isEmpty && !BuiltInRegistries.ITEM.getKey(it.item).path.endsWith("glass_pane") }
            .filter { lore(it).isNotEmpty() }
            .map(::name)
            .filter { it.isNotEmpty() && it != "Search" && it != "Go Back" && it != "Close" }
        if (names.size < 2 || names == ForgeStore.book().categories) return
        ForgeStore.updateBook { copy(categories = names) }
    }

    private fun readRecipePage(title: String, items: Map<Int, ItemStack>, now: Long) {
        val entries = ForgeParsing.GRID.mapNotNull { slot ->
            val stack = items[slot]?.takeIf { !it.isEmpty } ?: return@mapNotNull null
            ForgeParsing.entry(SkyblockItemUtils.getSkyblockId(stack), name(stack), lore(stack))?.let { Triple(slot, stack, it) }
        }
        if (entries.isEmpty()) return
        val page = ForgeParsing.pageTitle(title)
        val category = page.category.takeIf { title != ForgeParsing.RESULTS_TITLE }
        val index = RecipeCatalogClient.index
        val book = ForgeStore.book()
        val recipes = HashMap(book.recipes)
        val positions = HashMap(book.positions)
        val unlocked = HashSet<String>()
        val locked = HashMap<String, String>()
        val durations = ArrayList<Pair<Long, Long>>()
        for ((slot, stack, entry) in entries) {
            val position = category?.let { "$it|${page.page}|$slot" }
            if (entry is ForgeParsing.Recipe) {
                val id = ForgeItems.keyOf(stack) ?: entry.outputId
                unlocked += id
                if (category != null) {
                    recipes[id] = ForgeBookEntry(category, page.page * 100 + slot, entry.name, ForgeLook.of(InventoryButtonIcons.look(stack)))
                    positions[position!!] = id
                }
                val base = index.forge[id]?.baseSeconds
                if (base != null && entry.shownSeconds != null) durations += entry.shownSeconds to base
            } else if (entry is ForgeParsing.Locked) {
                val id = position?.let { positions[it] } ?: lockedByName(entry.name)
                if (id != null) locked[id] = entry.requirement
            }
        }
        if (recipes != book.recipes || positions != book.positions) ForgeStore.updateBook { copy(recipes = recipes, positions = positions) }
        val data = ForgeStore.read()
        val nextLocks = data.locked - unlocked + locked
        if (nextLocks != data.locked) ForgeStore.update { copy(locked = nextLocks, lockedAt = now) }
        val percent = ForgeParsing.quickForgeFromPage(durations)
        if (percent != null && (data.quickForge == null || abs(data.quickForge - percent) > 0.01 || data.quickForgeFrom != "recipes")) {
            ForgeStore.update { copy(quickForge = percent, quickForgeLevel = null, quickForgeFrom = "recipes", quickForgeAt = now) }
        }
    }

    /** A locked placeholder's name, when no other profile showed its slot: the forge output with that display name. */
    private fun lockedByName(name: String): String? {
        val wanted = name.lowercase(Locale.ROOT)
        ItemCatalogClient.byDisplayName(name)?.id?.takeIf { it in RecipeCatalogClient.index.forge }?.let { return it }
        return ForgeStore.book().recipes.entries.firstOrNull { it.value.name?.lowercase(Locale.ROOT) == wanted }?.key
    }
}
