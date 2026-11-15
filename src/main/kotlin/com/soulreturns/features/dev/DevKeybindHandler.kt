package com.soulreturns.features.dev

import com.soulreturns.config.cfg
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import com.soulreturns.util.toLegacyText
import com.soulreturns.util.MessageDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.RegistryOps
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerScoreEntry
import net.minecraft.world.scores.Scoreboard
import com.soulreturns.util.KeyCombo
import kotlin.math.sqrt

/**
 * Polls global keybinds defined under `dev.keybinds.*` once per client tick and triggers
 * clipboard-copy actions for diagnostic data dumps. Bypasses Minecraft's controls menu —
 * keys are stored as `key.keyboard.X` strings in the config and resolved here at runtime —
 * so the binds work everywhere, including inside container GUIs (that's the point: dump
 * the chest you're looking at).
 */
object DevKeybindHandler {
    // Every human-readable string in every dump goes through MessageDetector.escapeInvisible. Hypixel's
    // resource pack draws its icons from the Unicode private-use area, and those codepoints are dropped
    // by a copy-paste — so a dump pasted into a chat or an issue would quietly lose exactly the glyph a
    // parser needs to match. Readable symbols (❤, ☀) are left alone.
    private const val NEARBY_RADIUS = 30.0

    private val logger = SoulLogger("Soul/DevKeybinds")
    private val json = Json { prettyPrint = true }

    /** Keys that were pressed last tick; used to fire on the press *edge*, not while held. */
    private val held = mutableSetOf<String>()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Without developer access these are inert, as if unbound.
            if (!DevAccess.unlocked) {
                held.clear()
                return@register
            }
            // While ANY Soul screen is up these must not fire: in the config menu the user may
            // be CAPTURING one of these very keys, and elsewhere a dump of "the opened GUI"
            // would just be our own screen. Track held state (so no stale edge fires after
            // closing) but never trigger.
            val suppress = client.gui.screen() is SoulScreenBase
            val k = cfg.dev.keybinds
            check(client, suppress, "copyOpenedGui", k.copyOpenedGui) { copyOpenedGui() }
            check(client, suppress, "copyItemUnderCursor", k.copyItemUnderCursor) { copyItemUnderCursor() }
            check(client, suppress, "copyHeldItem", k.copyHeldItem) { copyHeldItem() }
            check(client, suppress, "copyScoreboard", k.copyScoreboard) { copyScoreboard() }
            check(client, suppress, "copyTablist", k.copyTablist) { copyTablist() }
            check(client, suppress, "copyNearbyEntities", k.copyNearbyEntities) { copyNearbyEntities() }
        }
    }

    private inline fun check(
        client: Minecraft,
        suppress: Boolean,
        slot: String,
        bindingName: String,
        action: () -> Unit,
    ) {
        val combo = KeyCombo.cached(bindingName)
        if (combo == null) {
            held.remove(slot)
            return
        }
        val isDown = combo.isDown(client.window)
        val wasDown = slot in held
        if (isDown && !wasDown) {
            held.add(slot)
            if (suppress) return
            try {
                action()
            } catch (t: Throwable) {
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
        val screen = mc.gui.screen()
        if (screen !is AbstractContainerScreen<*>) {
            soulChat("§7No container GUI open.")
            return
        }
        val menu = screen.menu
        // Identity-compare slot.container against the player's Inventory — every menu type
        // exposes the same Inventory instance for the bottom 36 hotbar+inventory+offhand
        // slots, so this filter is uniform across chests, anvils, beacons, and Hypixel-
        // custom GUIs without needing per-menu-class dispatch.
        val playerInv = mc.player?.inventory
        val slots = ArrayList<JsonObject>()
        for ((idx, slot) in menu.slots.withIndex()) {
            if (playerInv != null && slot.container === playerInv) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            slots.add(itemToJson(stack, slotIndex = idx))
        }
        if (slots.isEmpty()) {
            soulChat("§7Open container has no non-player slots.")
            return
        }
        val obj =
            buildJsonObject {
                put("title", MessageDetector.escapeInvisible(screen.title.string))
                put("menuClass", menu::class.java.simpleName)
                put("slotCount", menu.slots.size)
                put("playerInventoryIncluded", false)
                put("slots", buildJsonArray { slots.forEach { add(it) } })
            }
        copyToClipboard(obj, "container (${slots.size} non-empty chest slots)")
    }

    private fun copyItemUnderCursor() {
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen()
        if (screen !is AbstractContainerScreen<*>) {
            soulChat("§7No container GUI open.")
            return
        }
        val slot = screen.hoveredSlot // protected → widened via soul.accesswidener
        if (slot == null || slot.item.isEmpty) {
            soulChat("§7No item under cursor.")
            return
        }
        copyToClipboard(itemToJson(slot.item), "item '${slot.item.hoverName.string}'")
    }

    private fun copyHeldItem() {
        val player = Minecraft.getInstance().player
        if (player == null) {
            soulChat("§7No player.")
            return
        }
        val stack = player.mainHandItem
        if (stack.isEmpty) {
            soulChat("§7Hand is empty.")
            return
        }
        copyToClipboard(itemToJson(stack), "held '${stack.hoverName.string}'")
    }

    private fun copyScoreboard() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null) {
            soulChat("§7No world.")
            return
        }
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (objective == null) {
            soulChat("§7No sidebar scoreboard.")
            return
        }
        // Hypixel renders SkyBlock scoreboard top-to-bottom: highest score first.
        val scores = scoreboard.listPlayerScores(objective).sortedByDescending { it.value() }
        val obj =
            buildJsonObject {
                put("title", MessageDetector.escapeInvisible(objective.displayName.string))
                put(
                    "lines",
                    buildJsonArray {
                        for (s in scores) {
                            add(
                                buildJsonObject {
                                    put("score", s.value())
                                    put("owner", MessageDetector.escapeInvisible(s.owner()))
                                    put("text", MessageDetector.escapeInvisible(renderScoreLine(scoreboard, s)))
                                    s.display()?.let { put("display", MessageDetector.escapeInvisible(it.string)) }
                                },
                            )
                        }
                    },
                )
            }
        copyToClipboard(obj, "scoreboard (${scores.size} lines)")
    }

    /**
     * Reconstructs the visible scoreboard line text the same way vanilla's sidebar renderer does:
     * `team.prefix + team-formatted owner + team.suffix`. Hypixel uses placeholder `owner` strings
     * (`§y`, `§x`, ...) and stores the actual line content in the team's prefix/suffix.
     */
    private fun renderScoreLine(
        scoreboard: Scoreboard,
        entry: PlayerScoreEntry,
    ): String {
        val team = scoreboard.getPlayersTeam(entry.owner())
        val ownerName = entry.ownerName()
        return if (team == null) {
            ownerName.string
        } else {
            team.playerPrefix.copy().append(ownerName).append(team.playerSuffix).string
        }
    }

    private fun copyTablist() {
        val conn = Minecraft.getInstance().player?.connection
        if (conn == null) {
            soulChat("§7Not connected.")
            return
        }
        val players = conn.listedOnlinePlayers
        val obj =
            buildJsonObject {
                put(
                    "players",
                    buildJsonArray {
                        for (info in players) {
                            add(
                                buildJsonObject {
                                    put("name", info.profile.name)
                                    put("uuid", info.profile.id.toString())
                                    info.tabListDisplayName?.let { put("displayName", MessageDetector.escapeInvisible(it.string)) }
                                    put("latency", info.latency)
                                    put("gameMode", info.gameMode.name)
                                },
                            )
                        }
                    },
                )
            }
        copyToClipboard(obj, "tablist (${players.size} players)")
    }

    private fun copyNearbyEntities() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val level = mc.level
        if (player == null || level == null) {
            soulChat("§7No world.")
            return
        }
        val radiusSq = NEARBY_RADIUS * NEARBY_RADIUS
        val entities = ArrayList<JsonObject>()
        for (entity in level.entitiesForRendering()) {
            if (entity === player) continue
            if (entity.distanceToSqr(player) > radiusSq) continue
            entities.add(entityToJson(entity, player))
        }
        val obj =
            buildJsonObject {
                put("playerX", player.x)
                put("playerY", player.y)
                put("playerZ", player.z)
                put("radius", NEARBY_RADIUS)
                put("count", entities.size)
                put("entities", buildJsonArray { entities.forEach { add(it) } })
            }
        copyToClipboard(obj, "nearby entities (${entities.size} within ${NEARBY_RADIUS.toInt()} blocks)")
    }

    private fun entityToJson(
        entity: Entity,
        player: Player,
    ): JsonObject =
        buildJsonObject {
            put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString())
            put("id", entity.id)
            put("uuid", entity.uuid.toString())
            put("displayName", MessageDetector.escapeInvisible(entity.name.string))
            entity.customName?.let { put("customName", MessageDetector.escapeInvisible(it.string)) }
            put("x", entity.x)
            put("y", entity.y)
            put("z", entity.z)
            put("distance", sqrt(entity.distanceToSqr(player)))
            put("invisible", entity.isInvisible)
            if (entity is LivingEntity) {
                put("health", entity.health)
                put("maxHealth", entity.maxHealth)
            }
            // Hypixel SkyBlock mobs typically have an invisible armor stand as passenger holding
            // the nametag.
            val passengers = entity.passengers
            if (passengers.isNotEmpty()) {
                put(
                    "passengers",
                    buildJsonArray {
                        for (p in passengers) {
                            add(
                                buildJsonObject {
                                    put("type", BuiltInRegistries.ENTITY_TYPE.getKey(p.type).toString())
                                    put("uuid", p.uuid.toString())
                                    put("displayName", MessageDetector.escapeInvisible(p.name.string))
                                    p.customName?.let { put("customName", MessageDetector.escapeInvisible(it.string)) }
                                },
                            )
                        }
                    },
                )
            }
            entity.vehicle?.let { v -> put("vehicleUuid", v.uuid.toString()) }
        }

    // ───────────────────── helpers ─────────────────────

    private fun itemToJson(
        stack: ItemStack,
        slotIndex: Int? = null,
    ): JsonObject =
        buildJsonObject {
            put("displayName", MessageDetector.escapeInvisible(stack.hoverName.string))
            // Same display name with Hypixel §-color codes preserved. Useful for pets and other
            // items where rarity is encoded purely in the name's color (e.g. `§7[Lvl 1] §5Ender
            // Dragon` is EPIC, `§7[Lvl 1] §6Ender Dragon` is LEGENDARY — the plain `displayName`
            // collapses both to identical text).
            put("displayNameColored", MessageDetector.escapeInvisible(stack.hoverName.toLegacyText()))
            put("count", stack.count)
            put("itemId", BuiltInRegistries.ITEM.getKey(stack.item).toString())
            SkyblockItemUtils.getSkyblockId(stack)?.let { put("skyblockId", it) }
            // Best-effort serialise the full stack (components + item) to NBT. This is where an item's
            // LORE lives, and so where Hypixel's stat icons show up (`<U+E022> Speed`), which is why it
            // is escaped like every other text field — the trade is that the value is meant for reading
            // and is no longer byte-exact enough to parse back into an ItemStack verbatim.
            try {
                val level = Minecraft.getInstance().level
                if (level != null) {
                    val ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess())
                    ItemStack.CODEC.encodeStart(ops, stack).result().ifPresent { tag ->
                        put("nbt", MessageDetector.escapeInvisible(tag.toString()))
                    }
                }
            } catch (_: Throwable) {
                // component serialisation is version-sensitive — skip on failure
            }
            slotIndex?.let { put("slot", it) }
        }

    private fun copyToClipboard(
        obj: JsonObject,
        label: String,
    ) {
        val mc = Minecraft.getInstance()
        val text = json.encodeToString(JsonObject.serializer(), obj)
        mc.execute { mc.keyboardHandler.setClipboard(text) }
        soulChat("§aCopied §f$label§a to clipboard (${text.length} chars).")
    }
}
