package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/** One provider subscription: every [intervalTicks] client ticks, or only on change. */
class StateSub(val provider: String, val intervalTicks: Int, val onChange: Boolean) {
    fun describe(): String = if (onChange) "$provider:change" else "$provider:${20.0 / intervalTicks}Hz"
}

/** `entities` provider bounds: radius, type words, name word, max count. */
class EntityQuery(val radius: Double, val types: List<String>, val name: String?, val max: Int) {
    val key: String = "r=$radius;t=${types.joinToString(",")};n=$name;m=$max"

    companion object {
        val DEFAULT = EntityQuery(32.0, emptyList(), null, 64)

        /** `ent=radius:30,type:armor_stand|zombie,name:Keeper,max:100`. */
        fun parse(spec: String?): EntityQuery {
            if (spec.isNullOrBlank()) return DEFAULT
            var q = DEFAULT
            for (part in spec.split(',')) {
                val k = part.substringBefore(':').trim()
                val v = part.substringAfter(':', "").trim()
                q =
                    when (k) {
                        "radius" -> EntityQuery(v.toDoubleOrNull()?.coerceIn(1.0, 256.0) ?: q.radius, q.types, q.name, q.max)
                        "type" -> EntityQuery(q.radius, v.split('|').filter { it.isNotEmpty() }, q.name, q.max)
                        "name" -> EntityQuery(q.radius, q.types, v.ifEmpty { null }, q.max)
                        "max" -> EntityQuery(q.radius, q.types, q.name, v.toIntOrNull()?.coerceIn(1, MAX_ENTITIES) ?: q.max)
                        else -> q
                    }
            }
            return q
        }

        const val MAX_ENTITIES = 512
    }
}

/**
 * What one stream or recording wants. Event kinds ([kinds]); raw packets by type/channel word
 * (`types`/`channels` imply `packet` and let noisy types through, `all` passes every type, `dir`
 * in|out|both); provider frames ([state], `state=self:10,players,world`: Hz or on change).
 */
class Filter(
    val kinds: Set<String>,
    val types: List<String>,
    /** Outbound packet types (exact ids) for `dir` out/both; empty = [types] matched by name like inbound. */
    val outTypes: List<String>,
    val channels: List<String>,
    val inbound: Boolean,
    val outbound: Boolean,
    val all: Boolean,
    val state: List<StateSub>,
    val entities: EntityQuery,
    val soul: List<String>,
    val backlog: Boolean,
) {
    fun wants(kind: String): Boolean = kind in kinds

    fun wantsPacket(inbound: Boolean, path: String, channel: String?): Boolean {
        if (if (inbound) !this.inbound else !outbound) return false
        if (!inbound && outTypes.isNotEmpty()) return path in outTypes
        if (types.isEmpty() && channels.isEmpty()) return all || path !in DevBridgeApi.noisyPackets()
        return types.any { path.contains(it) } || (channel != null && channels.any { channel.contains(it) })
    }

    fun describe(): Map<String, Any?> =
        linkedMapOf(
            "kinds" to kinds.sorted(),
            "types" to types,
            "out_types" to outTypes,
            "channels" to channels,
            "dir" to if (inbound && outbound) "both" else if (outbound) "out" else "in",
            "all" to all,
            "state" to state.map { it.describe() },
            "entities" to entities.key,
            "soul" to soul,
            "backlog" to backlog,
        )

    companion object {
        val DEFAULT_KINDS = setOf("screen_open", "screen_contents", "slot_update", "page", "screen_close", "click", "chat", "mark", "bridge")
        val ALL_KINDS =
            DEFAULT_KINDS +
                setOf(
                    "inventory_contents", "inventory_slot", "actionbar", "sidebar", "tab", "packet", "title", "bossbar", "held",
                    "location", "connection", "tracking", "record", "screenshot",
                )

        /** Everything except raw packets; what a recording takes by default. */
        val RECORD_KINDS = ALL_KINDS - "packet"

        /** Packet types a recording keeps by default: entity spawns/names, boss bars, sounds, blocks. Never movement or particles. */
        val RECORD_PACKETS =
            listOf(
                "add_entity", "remove_entities", "set_entity_data", "set_equipment", "boss_event", "sound", "block_update",
                "section_blocks_update", "explode", "player_position", "set_health", "entity_event", "take_item_entity",
            )

        /**
         * `preset=full`: everything needed to reconstruct a fight from a recording. Entity life (spawn, remove,
         * metadata, equipment, movement, teleport, head rotation, velocity, passengers, links, events, damage,
         * hurt/animate, effects, attributes, health), blocks (updates, sections, block events, destruction, block
         * entity data), explosions, particles, sounds, level events, boss bars, the player's own position and
         * pickups. Never chunks, light or keep-alives. Each packet is still bounded ([PacketDump]) and the bridge
         * sheds a type that costs the game too much.
         */
        val FULL_PACKETS =
            listOf(
                "add_entity", "remove_entities", "set_entity_data", "set_equipment", "move_entity_pos", "move_entity_rot",
                "entity_position_sync", "teleport_entity", "rotate_head", "set_entity_motion", "set_passengers", "set_entity_link",
                "entity_event", "damage_event", "hurt_animation", "animate", "update_mob_effect", "remove_mob_effect",
                "update_attributes", "set_health", "take_item_entity", "player_position", "block_update", "section_blocks_update",
                "block_event", "block_destruction", "block_entity_data", "explode", "level_particles", "level_event", "sound",
                "boss_event", "set_time", "ping",
            )

        /**
         * What `dir=out|both` records with `preset=full` (and in a default recording): the client's own actions as the
         * server sees them. Item use, use on block, entity interaction and attacks (`attack` is the left click on an
         * entity in 26.2, `interact` the right click), digging/dropping/releasing, swings, hotbar changes, middle-click picks,
         * container clicks and closes, sneak/sprint/jump input, movement and its teleport acknowledgement, chat and
         * commands. Matched by exact id so `chat` does not pull in `chat_session_update`. Observed, never sent.
         */
        val FULL_OUT_PACKETS =
            listOf(
                "use_item", "use_item_on", "interact", "attack", "player_action", "swing", "set_carried_item", "container_click",
                "container_close", "container_button_click", "player_input", "player_command", "move_player_pos",
                "move_player_pos_rot", "move_player_rot", "move_player_status_only", "accept_teleportation", "chat",
                "chat_command", "chat_command_signed", "move_vehicle", "pick_item_from_block", "pick_item_from_entity",
            )

        /** Frame rates for a recording: positions fast enough to judge standing spots, the rest on change. */
        const val RECORD_STATE = "self:10,players:10,party:5,world,map,soul,screen"

        fun parse(query: Map<String, String>, recording: Boolean = false): Filter {
            fun words(key: String) = query[key]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
            var types = words("types")
            val channels = words("channels")
            val all = query["all"] == "1" || query["all"] == "true"
            val explicit = words("kinds").flatMap { if (it == "all") ALL_KINDS else listOf(it) }.toMutableSet()
            val kinds =
                if (explicit.isNotEmpty()) explicit else if (recording) RECORD_KINDS.toMutableSet() else DEFAULT_KINDS.toMutableSet()
            val dirWord = query["dir"] ?: "in"
            // `out=1` (the old opt-in) and `dir=out|both` both mean: observe the client's own packets.
            val outbound = query["out"] == "1" || query["out"] == "true" || dirWord == "out" || dirWord == "both"
            var outTypes = emptyList<String>()
            if (query["preset"] == "full" && types.isEmpty() && channels.isEmpty() && !all) {
                val named = query["packets"]?.takeIf { it.isNotBlank() && it != "default" && it != "full" }?.split(',')?.map { it.trim() }
                types = named ?: FULL_PACKETS
                if (outbound && named == null) outTypes = FULL_OUT_PACKETS
            } else if (recording && query["packets"] != "none" && types.isEmpty() && channels.isEmpty() && !all) {
                val named = query["packets"]?.takeIf { it.isNotBlank() && it != "default" }?.split(',')?.map { it.trim() }
                types = named ?: RECORD_PACKETS
                if (outbound && named == null) outTypes = FULL_OUT_PACKETS
            }
            if (types.isNotEmpty() || channels.isNotEmpty() || all) kinds += "packet"
            val dir = if (outbound && dirWord == "in") "both" else dirWord
            val stateSpec = query["state"] ?: if (recording) RECORD_STATE else ""
            val state =
                stateSpec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.flatMap { part ->
                    val name = part.substringBefore(':')
                    val rate = part.substringAfter(':', "")
                    val names = if (name == "all") Providers.names() else listOf(name)
                    names.filter { Providers.get(it) != null }.map { n ->
                        val p = Providers.get(n)!!
                        val hz = rate.toDoubleOrNull()
                        if (rate == "change" || (hz == null && p.defaultHz == null)) {
                            StateSub(n, CHANGE_CHECK_TICKS, true)
                        } else {
                            StateSub(n, (20.0 / (hz ?: p.defaultHz!!).coerceIn(0.05, 20.0)).toInt().coerceAtLeast(1), false)
                        }
                    }
                }.distinctBy { it.provider }
            return Filter(
                kinds, types, outTypes, channels, dir != "out", dir == "out" || dir == "both", all, state,
                EntityQuery.parse(query["ent"]), words("soul"), query["backlog"] == "1" || (recording && query["backlog"] != "0"),
            )
        }

        /** On-change providers are re-read twice a second and sent only when they differ. */
        const val CHANGE_CHECK_TICKS = 10
    }
}

/** A connected stream or a recording: a bounded queue its own thread drains. */
class Listener(val id: Int, val filter: Filter, val label: String) {
    val queue = ArrayBlockingQueue<String>(QUEUE_CAPACITY)
    val sent = AtomicLong()
    val dropped = AtomicLong()
    val droppedTotal = AtomicLong()

    /** Packets this listener's filter accepted at the hook, per direction (seen even when later stubbed or dropped), and outbound per type. */
    val packetsIn = AtomicLong()
    val packetsOut = AtomicLong()
    val outByType = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    fun countPacket(inbound: Boolean, path: String) {
        if (inbound) {
            packetsIn.incrementAndGet()
        } else {
            packetsOut.incrementAndGet()
            outByType.computeIfAbsent(path) { AtomicLong() }.incrementAndGet()
        }
    }

    /** `{in, out, out_types}`: a recording that asked for `dir` both but shows `out: 0` is missing its direction. */
    fun packetCounts(): Map<String, Any?> = linkedMapOf("in" to packetsIn.get(), "out" to packetsOut.get(), "out_types" to outByType.mapValues { it.value.get() }.toSortedMap())
    val since = System.currentTimeMillis()

    /** Last data hash per on-change provider; client thread only. */
    val lastHash = HashMap<String, Int>()

    /** Never blocks the caller (netty or client thread); a full queue counts a drop instead. */
    fun offer(line: String) {
        // Bounded by lines and by memory: a few huge lines must not fill the heap before the count limit does.
        if (queuedChars.get() + line.length > MAX_QUEUED_CHARS || !queue.offer(line)) {
            dropped.incrementAndGet()
            droppedTotal.incrementAndGet()
            return
        }
        queuedChars.addAndGet(line.length.toLong())
    }

    /** Characters waiting in [queue]. */
    val queuedChars = AtomicLong()

    fun poll(): String? = queue.poll()?.also { queuedChars.addAndGet(-it.length.toLong()) }

    fun poll(timeout: Long, unit: java.util.concurrent.TimeUnit): String? = queue.poll(timeout, unit)?.also { queuedChars.addAndGet(-it.length.toLong()) }

    fun describe(): JsonObject =
        JsonObject(
            mapOf(
                "id" to json(id),
                "kind" to json(label),
                "since" to json(since),
                "sent" to json(sent.get()),
                "queued" to json(queue.size),
                "queued_chars" to json(queuedChars.get()),
                "dropped" to json(droppedTotal.get()),
                "packets" to json(packetCounts()),
                "filter" to json(filter.describe()),
            ),
        )

    private companion object {
        const val QUEUE_CAPACITY = 8192

        /** 32 M characters, about 64 MB of heap. */
        const val MAX_QUEUED_CHARS = 32L * 1024 * 1024
    }
}
