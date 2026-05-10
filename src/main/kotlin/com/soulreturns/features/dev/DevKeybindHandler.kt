package com.soulreturns.features.dev

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.InputConstants
import com.soulreturns.config.cfg
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps
import net.minecraft.world.item.ItemStack
import net.minecraft.world.scores.DisplaySlot
import org.lwjgl.glfw.GLFW

/**
 * Polls global keybinds defined under `dev.keybinds.*` once per client tick and triggers
 * clipboard-copy actions for diagnostic data dumps. Bypasses Minecraft's controls menu —
 * keys are stored as `key.keyboard.X` strings in the config and resolved here at runtime.
 */
object DevKeybindHandler {
    private val logger = SoulLogger("Soul/DevKeybinds")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    /** Keys that were pressed last tick; used to fire on the press *edge*, not while held. */
    private val held = mutableSetOf<String>()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
            val k = cfg.dev.keybinds
            check(client, "copyOpenedGui",          k.copyOpenedGui())          { copyOpenedGui() }
            check(client, "copyItemUnderCursor",    k.copyItemUnderCursor())    { copyItemUnderCursor() }
            check(client, "copyHeldItem",           k.copyHeldItem())           { copyHeldItem() }
            check(client, "copyScoreboard",         k.copyScoreboard())         { copyScoreboard() }
            check(client, "copyTablist",            k.copyTablist())            { copyTablist() }
        })
    }

    private inline fun check(
        client: Minecraft,
        slot: String,
        bindingName: String,
        action: () -> Unit
    ) {
        if (bindingName.isBlank()) {
            held.remove(slot)
            return
        }
        val key = try { InputConstants.getKey(bindingName) } catch (_: Throwable) {
            held.remove(slot); return
        }
        if (key.value < 0) { held.remove(slot); return }
        val window = client.window
        val isDown = when (key.type) {
            InputConstants.Type.KEYSYM -> InputConstants.isKeyDown(window, key.value)
            InputConstants.Type.MOUSE  -> GLFW.glfwGetMouseButton(window.handle(), key.value) == GLFW.GLFW_PRESS
            else -> false
        }
        val wasDown = slot in held
        if (isDown && !wasDown) {
            held.add(slot)
            try { action() } catch (t: Throwable) {
                logger.warn("Keybind action '$slot' threw", t)
                soulChat("§cKeybind '$slot' failed: ${t.message}")
            }
        } else if (!isDown) {
            held.remove(slot)
        }
    }

    // ───────────────────── actions ─────────────────────

    private fun copyOpenedGui() {
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (screen !is AbstractContainerScreen<*>) {
            soulChat("§7No container GUI open."); return
        }
        val menu = screen.menu
        val obj = JsonObject().apply {
            addProperty("title", screen.title.string)
            addProperty("menuClass", menu::class.java.simpleName)
            addProperty("slotCount", menu.slots.size)
        }
        val slotsArr = JsonArray()
        for ((idx, slot) in menu.slots.withIndex()) {
            val stack = slot.item
            if (stack.isEmpty) continue
            slotsArr.add(itemToJson(stack).apply { addProperty("slot", idx) })
        }
        obj.add("slots", slotsArr)
        copyToClipboard(obj, "container (${slotsArr.size()} non-empty slots)")
    }

    private fun copyItemUnderCursor() {
        val mc = Minecraft.getInstance()
        val screen = mc.screen
        if (screen !is AbstractContainerScreen<*>) {
            soulChat("§7No container GUI open."); return
        }
        val slot = screen.hoveredSlot
        if (slot == null || slot.item.isEmpty) {
            soulChat("§7No item under cursor."); return
        }
        copyToClipboard(itemToJson(slot.item), "item ‘${slot.item.hoverName.string}’")
    }

    private fun copyHeldItem() {
        val player = Minecraft.getInstance().player
        if (player == null) {
            soulChat("§7No player."); return
        }
        val stack = player.mainHandItem
        if (stack.isEmpty) {
            soulChat("§7Hand is empty."); return
        }
        copyToClipboard(itemToJson(stack), "held ‘${stack.hoverName.string}’")
    }

    private fun copyScoreboard() {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: run { soulChat("§7No world."); return }
        val objective = level.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (objective == null) {
            soulChat("§7No sidebar scoreboard."); return
        }
        val title = objective.displayName.string
        val scores = level.scoreboard.listPlayerScores(objective)
            .sortedByDescending { it.value() }
        val obj = JsonObject().apply { addProperty("title", title) }
        val linesArr = JsonArray()
        for (s in scores) {
            val line = JsonObject().apply {
                addProperty("owner", s.owner())
                addProperty("score", s.value())
                s.display()?.let { addProperty("display", it.string) }
            }
            linesArr.add(line)
        }
        obj.add("lines", linesArr)
        copyToClipboard(obj, "scoreboard (${linesArr.size()} lines)")
    }

    private fun copyTablist() {
        val mc = Minecraft.getInstance()
        val conn = mc.player?.connection ?: run { soulChat("§7Not connected."); return }
        val obj = JsonObject()
        mc.gui.tabList.let { tab ->
            // Header / footer aren't directly accessible via public API — use reflection sparingly,
            // skip if unavailable. Most callers only need the player rows anyway.
        }
        val playersArr = JsonArray()
        for (info in conn.listedOnlinePlayers) {
            val p = JsonObject().apply {
                addProperty("name", info.profile.name)
                addProperty("uuid", info.profile.id.toString())
                info.tabListDisplayName?.let { addProperty("displayName", it.string) }
                addProperty("latency", info.latency)
                addProperty("gameMode", info.gameMode.name)
            }
            playersArr.add(p)
        }
        obj.add("players", playersArr)
        copyToClipboard(obj, "tablist (${playersArr.size()} players)")
    }

    // ───────────────────── helpers ─────────────────────

    private fun itemToJson(stack: ItemStack): JsonObject {
        val obj = JsonObject().apply {
            addProperty("displayName", stack.hoverName.string)
            addProperty("count", stack.count)
            addProperty("itemId", BuiltInRegistries.ITEM.getKey(stack.item).toString())
        }
        SkyblockItemUtils.getSkyblockId(stack)?.let { obj.addProperty("skyblockId", it) }
        // Best-effort serialise the full stack (components + item) to NBT for reproducibility.
        try {
            val level = Minecraft.getInstance().level
            if (level != null) {
                val ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess())
                ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent { tag ->
                    obj.addProperty("nbt", tag.toString())
                }
            }
        } catch (_: Throwable) { /* component serialisation is version-sensitive — skip on failure */ }
        return obj
    }

    private fun copyToClipboard(obj: JsonObject, label: String) {
        val mc = Minecraft.getInstance()
        val text = gson.toJson(obj)
        mc.execute { mc.keyboardHandler.setClipboard(text) }
        soulChat("§aCopied §f$label§a to clipboard (${text.length} chars).")
    }
}
