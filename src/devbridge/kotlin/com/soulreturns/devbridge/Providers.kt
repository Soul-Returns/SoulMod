package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Named, independently selectable state sources. Each one is usable in the on-demand snapshot,
 * as a periodic or on-change stream frame (`state=`), and in recordings. All read on the client
 * thread, bounded, never blocking. Adding one = one [register] line plus its read function.
 */
class Provider(val name: String, val defaultHz: Double?, val about: String, val read: (Filter) -> JsonElement)

object Providers {
    private val all = LinkedHashMap<String, Provider>()

    /** Provider reads since start: the idle-cost proof (stays put while nothing is attached). */
    val reads = AtomicLong()

    private fun register(name: String, defaultHz: Double?, about: String, read: (Filter) -> JsonElement) {
        all[name] = Provider(name, defaultHz, about, read)
    }

    init {
        register("self", 10.0, "own position, rotation, velocity, movement flags, health, held item, hotbar, armor, dimension, ping, looked-at block, dungeon room") { self() }
        register("players", 10.0, "every other tracked player entity: name, uuid, real/NPC, position, rotation, held item, flags, distance, tracked since; recent untracks") { players() }
        register("entities", 2.0, "entities around you (ent=radius:R,type:a|b,name:W,max:N), nearest first, bounded") { entities(it.entities) }
        register("map", null, "dungeon map item markers as the map gives them (no players in the boss room)") { map() }
        register("party", 5.0, "one merged view per party member: class, dead, best position with source entity|map|none and age") { party() }
        register("world", null, "location readers, server, sidebar, tab list, action bar, title, boss bars") { world() }
        register("soul", null, "what Soul's features think (DevState providers; soul=name,… to pick)") { soul(it.soul) }
        register("screen", null, "open screen: class, title, container, every non-empty slot, carried item") { screen() }
    }

    fun names(): List<String> = all.keys.toList()

    fun get(name: String): Provider? = all[name]

    fun describe(): List<Map<String, Any?>> = all.values.map { linkedMapOf("name" to it.name, "default" to (it.defaultHz?.let { hz -> "${hz}Hz" } ?: "on change"), "about" to it.about) }

    /** One provider; a failing read yields its error instead of breaking the stream. */
    fun read(name: String, filter: Filter): JsonElement {
        val p = all[name] ?: return JsonNull
        reads.incrementAndGet()
        return try {
            p.read(filter)
        } catch (e: LinkageError) {
            throw e
        } catch (t: Throwable) {
            JsonObject(mapOf("error" to JsonPrimitive(t.toString())))
        }
    }

    /** Every provider (or [names]) at once, on the client thread; from another thread it waits ≤5 s. */
    fun snapshot(filter: Filter, names: List<String> = names()): JsonObject {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) return mc.submit<JsonObject> { snapshot(filter, names) }.get(5, TimeUnit.SECONDS)
        val out = LinkedHashMap<String, JsonElement>()
        out["player"] = JsonPrimitive(mc.user.name)
        out["server"] = JsonPrimitive(mc.currentServer?.ip)
        out["ticks"] = json(Ticks.describe())
        for (n in names) out[n] = read(n, filter)
        return JsonObject(out)
    }

    // ---------- self ----------

    private fun self(): JsonElement {
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return JsonNull
        val out = LinkedHashMap<String, Any?>()
        out["name"] = p.gameProfile.name
        out["uuid"] = p.uuid.toString()
        out["pos"] = listOf(p.x, p.y, p.z)
        out["yaw"] = Mth.wrapDegrees(p.yRot)
        out["pitch"] = p.xRot
        out["velocity"] = p.deltaMovement.let { listOf(it.x, it.y, it.z) }
        out["on_ground"] = p.onGround()
        out["sneaking"] = p.isShiftKeyDown
        out["sprinting"] = p.isSprinting
        out["health"] = p.health
        out["max_health"] = p.maxHealth
        out["absorption"] = p.absorptionAmount
        out["food"] = p.foodData.foodLevel
        out["hotbar"] = p.inventory.selectedSlot
        out["held"] = brief(p.mainHandItem)
        out["offhand"] = brief(p.offhandItem)
        out["armor"] = linkedMapOf(
            "head" to brief(p.getItemBySlot(EquipmentSlot.HEAD)),
            "chest" to brief(p.getItemBySlot(EquipmentSlot.CHEST)),
            "legs" to brief(p.getItemBySlot(EquipmentSlot.LEGS)),
            "feet" to brief(p.getItemBySlot(EquipmentSlot.FEET)),
        )
        out["vehicle"] = p.vehicle?.let { typeId(it) }
        out["dimension"] = mc.level?.dimension()?.identifier()?.toString()
        out["ping"] = p.connection.getPlayerInfo(p.uuid)?.latency
        out["looking_at"] = lookingAt()
        out["room"] = DevBridgeApi.dungeonRoom(p.x, p.z)
        return json(out)
    }

    /** The block or entity under the crosshair (vanilla's own hit result; nothing is raycast here). */
    fun lookingAt(): Map<String, Any?>? {
        val mc = Minecraft.getInstance()
        val hit = mc.hitResult ?: return null
        if (hit.type.name == "MISS") return null
        if (hit is BlockHitResult) {
            val pos = hit.blockPos
            val block = mc.level?.getBlockState(pos)?.block?.let { BuiltInRegistries.BLOCK.getKey(it).toString() }
            return linkedMapOf("block" to block, "pos" to listOf(pos.x, pos.y, pos.z), "face" to hit.direction.name.lowercase())
        }
        if (hit is EntityHitResult) {
            val e = hit.entity
            return linkedMapOf("entity" to typeId(e), "id" to e.id, "name" to name(e), "pos" to listOf(e.x, e.y, e.z))
        }
        return null
    }

    // ---------- players ----------

    private fun players(): JsonElement {
        val mc = Minecraft.getInstance()
        val me = mc.player ?: return JsonNull
        val level = mc.level ?: return JsonNull
        val list =
            level.players().filter { it !== me }.sortedBy { it.distanceToSqr(me) }.map { p ->
                val tracked = Tracking.since(p.uuid)
                linkedMapOf(
                    "name" to p.gameProfile.name,
                    "uuid" to p.uuid.toString(),
                    // Hypixel's NPCs and fake players carry version-2 uuids; real accounts version 4.
                    "real" to (p.uuid.version() == 4),
                    // Shown in the tab list (Hypixel lists only its fake widget rows, so usually false there).
                    "listed" to me.connection.listedOnlinePlayers.any { it.profile.id == p.uuid },
                    "pos" to listOf(p.x, p.y, p.z),
                    "yaw" to Mth.wrapDegrees(p.yRot),
                    "pitch" to p.xRot,
                    "held" to brief(p.mainHandItem),
                    "sneaking" to p.isShiftKeyDown,
                    "invisible" to p.isInvisible,
                    "distance" to Math.sqrt(p.distanceToSqr(me)),
                    "tracked_since" to tracked?.first,
                    "tracked_since_tick" to tracked?.second,
                )
            }
        return json(linkedMapOf("count" to list.size, "players" to list, "untracked_recent" to Tracking.recentUntracks()))
    }

    // ---------- entities ----------

    private fun entities(q: EntityQuery): JsonElement {
        val mc = Minecraft.getInstance()
        val me = mc.player ?: return JsonNull
        val level = mc.level ?: return JsonNull
        val r2 = q.radius * q.radius
        val matched = ArrayList<Pair<Double, Entity>>()
        for (e in level.entitiesForRendering()) {
            if (e === me) continue
            val d2 = e.distanceToSqr(me)
            if (d2 > r2) continue
            if (q.types.isNotEmpty()) {
                val path = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).path
                if (q.types.none { path.contains(it) }) continue
            }
            if (q.name != null && !name(e).contains(q.name, ignoreCase = true)) continue
            matched += d2 to e
        }
        matched.sortBy { it.first }
        val shown =
            matched.take(q.max).map { (d2, e) ->
                val out = linkedMapOf<String, Any?>(
                    "type" to typeId(e),
                    "id" to e.id,
                    "name" to name(e),
                    "pos" to listOf(e.x, e.y, e.z),
                    "distance" to Math.sqrt(d2),
                )
                if (e.isInvisible) out["invisible"] = true
                if (e is LivingEntity && e !is Player) {
                    val head = e.getItemBySlot(EquipmentSlot.HEAD)
                    if (!head.isEmpty) out["head"] = brief(head)
                    val hand = e.mainHandItem
                    if (!hand.isEmpty) out["held"] = brief(hand)
                }
                out
            }
        return json(linkedMapOf("query" to q.key, "matched" to matched.size, "shown" to shown.size, "entities" to shown))
    }

    // ---------- map / party ----------

    private fun map(): JsonElement {
        val markers = DevBridgeApi.mapMarkers()
        if (markers !is JsonObject) return JsonObject(mapOf("source" to JsonPrimitive("map"), "markers" to JsonNull))
        return JsonObject(
            markers + mapOf(
                "source" to JsonPrimitive("map"),
                "limit" to JsonPrimitive("the dungeon map shows no players inside the boss room; there only tracked entities exist"),
            ),
        )
    }

    private fun party(): JsonElement {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return JsonNull
        val soul = DevBridgeApi.party() as? JsonObject ?: return JsonNull
        val roster = (soul["dungeon_roster"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val names = LinkedHashSet<String>()
        roster.forEach { r -> (r["name"] as? JsonPrimitive)?.content?.let(names::add) }
        (soul["members"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.content?.let(names::add) }
        if (names.isNotEmpty()) mc.player?.gameProfile?.name?.let(names::add)
        val now = System.currentTimeMillis()
        val members =
            names.map { name ->
                val r = roster.firstOrNull { (it["name"] as? JsonPrimitive)?.content == name }
                val entity = level.players().firstOrNull { it.gameProfile.name == name }
                val out = linkedMapOf<String, Any?>("name" to name, "class" to r?.get("class"), "dead" to r?.get("dead"))
                val map = r?.get("map") as? JsonObject
                if (entity != null) {
                    out["source"] = "entity"
                    out["pos"] = listOf(entity.x, entity.y, entity.z)
                    out["yaw"] = Mth.wrapDegrees(entity.yRot)
                    out["age_ms"] = 0
                } else if (map != null) {
                    out["source"] = "map"
                    out["pos"] = listOf(map["x"], null, map["z"])
                    out["yaw"] = map["yaw"]
                    out["age_ms"] = map["age_ms"]
                } else {
                    out["source"] = "none"
                    Tracking.lastSeen(name)?.let { (t, pos) ->
                        out["last_entity_pos"] = pos
                        out["last_entity_age_ms"] = now - t
                    }
                }
                out
            }
        return json(
            linkedMapOf(
                "floor" to soul["floor"],
                "in_run" to soul["in_run"],
                "leader" to soul["leader"],
                "members" to members,
                "note" to "map positions are absent inside the boss room; in M7 boss teammates are tracked only within ~48 blocks horizontally",
            ),
        )
    }

    // ---------- world ----------

    private fun world(): JsonElement {
        val mc = Minecraft.getInstance()
        val hud = mc.gui.hud
        val bars = hud.bossOverlay.events.values.map { linkedMapOf("name" to DevBridgeApi.legacy(it.name), "progress" to it.progress, "color" to it.color.name) }
        val title = titleField?.get(hud) as? Component
        val subtitle = subtitleField?.get(hud) as? Component
        val titleTime = titleTimeField?.get(hud) as? Int ?: 0
        return json(
            linkedMapOf(
                "location" to DevBridgeApi.location(),
                "server" to mc.currentServer?.ip,
                "dimension" to mc.level?.dimension()?.identifier()?.toString(),
                "sidebar" to sidebar(),
                "tab" to tab(),
                "action_bar" to hud.overlayMessageString?.takeIf { hud.overlayMessageTime > 0 }?.let(DevBridgeApi::legacy),
                "title" to if (titleTime > 0) linkedMapOf("title" to title?.let(DevBridgeApi::legacy), "subtitle" to subtitle?.let(DevBridgeApi::legacy)) else null,
                "boss_bars" to bars,
            ),
        )
    }

    /** Sidebar title and lines top to bottom, team prefix/suffix applied, as `§` strings. */
    fun sidebar(): JsonObject? {
        val scoreboard = Minecraft.getInstance().player?.connection?.scoreboard() ?: return null
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        val lines =
            scoreboard.listPlayerScores(objective).sortedByDescending { it.value() }.map { entry ->
                val team = scoreboard.getPlayersTeam(entry.owner())
                val rendered = if (team == null) entry.ownerName() else PlayerTeam.formatNameForTeam(team, entry.ownerName())
                JsonPrimitive(DevBridgeApi.legacy(rendered))
            }
        return JsonObject(mapOf("title" to JsonPrimitive(DevBridgeApi.legacy(objective.displayName)), "lines" to JsonArray(lines)))
    }

    /** Tab list in vanilla's display order, plus header and footer. */
    fun tab(): JsonObject? {
        val mc = Minecraft.getInstance()
        val connection = mc.player?.connection ?: return null
        val players = connection.listedOnlinePlayers.let { list -> tabComparator?.let { list.sortedWith(it) } ?: list.toList() }
        val overlay = mc.gui.hud.tabList
        return JsonObject(
            mapOf(
                "header" to component(headerField?.get(overlay)),
                "footer" to component(footerField?.get(overlay)),
                "lines" to JsonArray(players.map { JsonPrimitive(DevBridgeApi.legacy(it.tabListDisplayName ?: Component.literal(it.profile.name))) }),
            ),
        )
    }

    // ---------- soul / screen ----------

    private fun soul(only: List<String>): JsonElement {
        val names = if (only.isEmpty()) DevBridgeApi.stateNames() else only
        return JsonObject(names.associateWith { DevBridgeApi.state(it) })
    }

    private fun screen(): JsonElement {
        val screen = Minecraft.getInstance().gui.screen() ?: return JsonNull
        val out = LinkedHashMap<String, JsonElement>()
        out["class"] = JsonPrimitive(screen.javaClass.simpleName)
        out["title"] = JsonPrimitive(DevBridgeApi.legacy(screen.title))
        if (screen is AbstractContainerScreen<*>) {
            val menu = screen.menu
            out["container_id"] = JsonPrimitive(menu.containerId)
            // The player's own inventory menu has no type and throws.
            out["menu_type"] = runCatching { BuiltInRegistries.MENU.getKey(menu.type)?.toString() }.getOrNull()?.let { JsonPrimitive(it) } ?: JsonNull
            out["slot_count"] = JsonPrimitive(menu.slots.size)
            out["container_slots"] = JsonPrimitive(MenuEvents.containerSlots(menu))
            if (!menu.carried.isEmpty) out["carried"] = MenuEvents.item(menu.carried)
            out["slots"] = JsonArray(menu.slots.withIndex().filter { it.value.hasItem() }.map { (i, s) -> MenuEvents.slot(menu, i, s.item) })
        }
        return JsonObject(out)
    }

    // ---------- helpers ----------

    /** id, SkyBlock id, name and count; no lore (frames run at 10 Hz). */
    fun brief(stack: ItemStack): Map<String, Any?>? {
        if (stack.isEmpty) return null
        val out = linkedMapOf<String, Any?>("id" to BuiltInRegistries.ITEM.getKey(stack.item).toString())
        stack.get(DataComponents.CUSTOM_DATA)?.copyTag()?.getString("id")?.orElse(null)?.let { out["sb_id"] = it }
        out["name"] = DevBridgeApi.legacy(stack.hoverName)
        if (stack.count != 1) out["count"] = stack.count
        Items.summary(stack)?.let { out["components"] = it }
        return out
    }

    fun typeId(e: Entity): String = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString()

    /** Custom name (armor stand tags carry Hypixel's names) or the plain name, as `§` text. */
    fun name(e: Entity): String = DevBridgeApi.legacy(e.customName ?: e.name)

    private fun component(value: Any?): JsonElement = if (value is Component) JsonPrimitive(DevBridgeApi.legacy(value)) else JsonNull

    // Private vanilla state; names are stable in unobfuscated 26.2.
    private fun field(owner: Class<*>, name: String): Field? = runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()

    private val headerField by lazy { field(PlayerTabOverlay::class.java, "header") }
    private val footerField by lazy { field(PlayerTabOverlay::class.java, "footer") }
    private val titleField by lazy { field(net.minecraft.client.gui.Hud::class.java, "title") }
    private val subtitleField by lazy { field(net.minecraft.client.gui.Hud::class.java, "subtitle") }
    private val titleTimeField by lazy { field(net.minecraft.client.gui.Hud::class.java, "titleTime") }

    @Suppress("UNCHECKED_CAST")
    private val tabComparator: Comparator<PlayerInfo>? by lazy {
        field(PlayerTabOverlay::class.java, "PLAYER_COMPARATOR")?.get(null) as? Comparator<PlayerInfo>
    }
}
