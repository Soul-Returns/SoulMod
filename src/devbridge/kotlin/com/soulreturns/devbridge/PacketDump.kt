package com.soulreturns.devbridge

import com.soulreturns.features.dev.DevBridgeApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.network.syncher.EntityDataSerializer
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.lang.reflect.Modifier
import java.util.Base64
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.OptionalLong
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The bridge's packet serializer: a bounded reflective dump. Every call carries a [Budget] (nodes,
 * characters, a deadline); a packet that exceeds it becomes a stub with its type and how far it got,
 * so no packet can cost more than the budget on any thread.
 *
 * Soul's own `PacketJson` walks fields without limits: a `block_entity_data` packet reached the
 * block entity *type* object (renderers, state tables, mixin fields) and came to 1.2 MB of JSON.
 * Here an object that is an entry of a vanilla registry becomes its id, NBT becomes compact SNBT,
 * the level, entities, block entities, client and server objects become their class name, and
 * mixin-added fields (`name$x`) are never read.
 */
object PacketDump {
    /** Over budget; no stack trace, thrown through the recursion and caught in [packet]. */
    class Overflow(val reason: String) : RuntimeException(reason, null, false, false)

    class Budget(val maxNodes: Int, val maxChars: Int, timeMillis: Long) {
        private val deadline = System.nanoTime() + timeMillis * 1_000_000
        var nodes = 0
            private set
        var chars = 0
            private set

        fun node() {
            if (++nodes > maxNodes) throw Overflow("nodes")
            if ((nodes and 31) == 0 && System.nanoTime() > deadline) throw Overflow("time")
        }

        fun chars(n: Int) {
            chars += n
            if (chars > maxChars) throw Overflow("size")
        }
    }

    const val MAX_NODES = 8_000
    const val MAX_CHARS = 160_000

    /** One packet, over time: sync (netty thread, items that the game mutates) or the worker thread. */
    const val SYNC_MILLIS = 2L
    const val WORKER_MILLIS = 20L

    private const val MAX_DEPTH = 7
    private const val MAX_ITEMS = 128
    private const val MAX_STRING = 2048
    private const val MAX_RAW_BYTES = 2048
    private const val MAX_NBT_BYTES = 65_536
    private const val MAX_NBT_CHARS = 16_384

    /** `{t, dir, type, channel?, data}` like Soul's logger; [data] is a stub `{"@":"stub",reason,…}` when the budget ran out. */
    fun packet(dir: String, packet: Packet<*>, t: Long, millis: Long, seq: Long = 0, bundle: Long = 0, stubbed: (String) -> Unit = {}): JsonObject {
        val type = packet.type().id().toString()
        val channel = DevBridgeApi.packetChannel(packet)
        val budget = Budget(MAX_NODES, MAX_CHARS, millis)
        val data: JsonElement =
            try {
                if (channel != null) payload(dir, packet, t, budget) else extras(packet, dump(packet, 0, IdentityHashMap(), budget))
            } catch (o: Overflow) {
                stubbed(o.reason)
                JsonObject(mapOf("@" to JsonPrimitive("stub"), "reason" to JsonPrimitive(o.reason), "nodes" to JsonPrimitive(budget.nodes), "chars" to JsonPrimitive(budget.chars)))
            }
        return JsonObject(
            buildMap {
                put("t", JsonPrimitive(t))
                put("dir", JsonPrimitive(dir))
                put("type", JsonPrimitive(type))
                if (seq > 0) put("seq", JsonPrimitive(seq))
                // A sub-packet of this bundle: a hook on `Connection.channelRead0` (Odin's tick timers) never sees it.
                if (bundle > 0) put("bundle", JsonPrimitive(bundle))
                if (channel != null) put("channel", JsonPrimitive(channel))
                put("data", data)
            },
        )
    }

    private val CHAT_OUT = setOf("chat", "chat_command", "chat_command_signed")
    private val SIGNING_KEY = Regex("signature|salt|lastseen|checksum|acknowledg", RegexOption.IGNORE_CASE)

    /** Commands whose arguments are credentials; the recording keeps the command word only. */
    private val SECRET_COMMANDS = setOf("login", "l", "log", "register", "reg", "password", "passwd", "changepassword", "authme", "auth", "2fa", "verify")

    /**
     * What the reflective dump cannot know: a falling block's `data` is a raw block-state id, resolved here to its
     * state string; and outbound chat/commands lose their signing material and the arguments of credential commands.
     */
    private fun extras(packet: Packet<*>, dumped: JsonElement): JsonElement {
        if (packet is ClientboundAddEntityPacket && dumped is JsonObject && BuiltInRegistries.ENTITY_TYPE.getKey(packet.type).path == "falling_block") {
            return JsonObject(dumped + ("state" to JsonPrimitive(state(Block.stateById(packet.data)))))
        }
        if (dumped is JsonObject && packet.type().id().path in CHAT_OUT) {
            val kept = dumped.filterKeys { !SIGNING_KEY.containsMatchIn(it) }.toMutableMap()
            val command = (kept["command"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (command != null && command.trimStart('/').substringBefore(' ').lowercase() in SECRET_COMMANDS) {
                kept["command"] = JsonPrimitive(command.trimStart('/').substringBefore(' ') + " <redacted>")
            }
            return JsonObject(kept)
        }
        return dumped
    }

    /** `minecraft:lever[face=wall,facing=north,powered=true]`: the registry key plus the sorted property list. */
    fun state(state: BlockState): String = BlockStateParser.serialize(state)

    /**
     * One section's block changes as a palette plus `[x, y, z, paletteIndex]` rows (absolute positions):
     * the reflective form was ~18 KB per packet and hit the node cap on the big ones.
     */
    private fun sectionBlocks(packet: ClientboundSectionBlocksUpdatePacket, b: Budget): JsonElement {
        val palette = LinkedHashMap<String, Int>()
        val rows = ArrayList<JsonElement>()
        packet.runUpdates { pos, st ->
            b.node()
            val name = state(st)
            val index = palette.getOrPut(name) { b.chars(name.length + 3); palette.size }
            b.chars(20)
            rows.add(JsonArray(listOf(JsonPrimitive(pos.x), JsonPrimitive(pos.y), JsonPrimitive(pos.z), JsonPrimitive(index))))
        }
        return JsonObject(
            mapOf(
                "@" to JsonPrimitive("SectionBlocksUpdate"),
                "count" to JsonPrimitive(rows.size),
                "states" to JsonArray(palette.keys.map { JsonPrimitive(it) }),
                "changes" to JsonArray(rows),
            ),
        )
    }

    /** Custom payloads keep Soul's form (unknown payloads keep their bytes there); they are small records. */
    private fun payload(dir: String, packet: Packet<*>, t: Long, budget: Budget): JsonElement {
        val data = DevBridgeApi.packetJson(dir == "in", packet)["data"] ?: JsonNull
        budget.chars(data.toString().length)
        return data
    }

    fun dump(value: Any?, budget: Budget): JsonElement = dump(value, 0, IdentityHashMap(), budget)

    private fun str(value: String, budget: Budget): JsonPrimitive {
        val s = if (value.length > MAX_STRING) value.substring(0, MAX_STRING) else value
        budget.chars(s.length)
        return JsonPrimitive(s)
    }

    private fun dump(value: Any?, depth: Int, seen: IdentityHashMap<Any, Unit>, b: Budget): JsonElement {
        b.node()
        if (value == null) return JsonNull
        when (value) {
            is String -> return str(value, b)
            is Number -> return JsonPrimitive(value)
            is Boolean -> return JsonPrimitive(value)
            is Char -> return JsonPrimitive(value.toString())
            is Enum<*> -> return JsonPrimitive(value.name)
            is UUID, is Identifier, is ResourceKey<*> -> return str(value.toString(), b)
            is Component -> return str(DevBridgeApi.legacy(value), b)
            is ItemStack -> return Items.item(value).also { b.chars(it.toString().length) }
            is BlockState -> return str(state(value), b)
            is ClientboundSectionBlocksUpdatePacket -> return sectionBlocks(value, b)
            is ByteArray -> return raw(value, b)
            // `set_equipment` is a list of (slot, stack) pairs; Mojang's Pair has no fields the reflective walk can read, it printed as "(HEAD, 1 minecraft:player_head)".
            is com.mojang.datafixers.util.Pair<*, *> -> return JsonArray(listOf(dump(value.first, depth + 1, seen, b), dump(value.second, depth + 1, seen, b)))
            is Tag -> return nbt(value, b)
            is BlockPos -> return JsonObject(mapOf("@" to JsonPrimitive("BlockPos"), "x" to JsonPrimitive(value.x), "y" to JsonPrimitive(value.y), "z" to JsonPrimitive(value.z)))
            is Optional<*> -> return if (value.isPresent) dump(value.get(), depth, seen, b) else JsonNull
            is OptionalInt -> return if (value.isPresent) JsonPrimitive(value.asInt) else JsonNull
            is OptionalLong -> return if (value.isPresent) JsonPrimitive(value.asLong) else JsonNull
            is OptionalDouble -> return if (value.isPresent) JsonPrimitive(value.asDouble) else JsonNull
            is Holder<*> -> return value.unwrapKey().map<JsonElement> { str(it.identifier().toString(), b) }.orElseGet { dump(value.value(), depth, seen, b) }
            is SynchedEntityData.DataValue<*> -> return JsonObject(mapOf("id" to JsonPrimitive(value.id()), "value" to dump(value.value(), depth + 1, seen, b)))
            is EntityDataSerializer<*> -> return JsonNull
            is Entity -> return JsonObject(mapOf("@" to JsonPrimitive("Entity"), "id" to JsonPrimitive(value.id), "type" to JsonPrimitive(BuiltInRegistries.ENTITY_TYPE.getKey(value.type).toString())))
            is BlockEntity -> return JsonObject(mapOf("@" to JsonPrimitive("BlockEntity"), "pos" to dump(value.blockPos, depth + 1, seen, b)))
        }
        val cls = value.javaClass
        if (cls.name.contains("\$\$Lambda")) return JsonNull
        opaque(value, cls)?.let { return JsonPrimitive(it) }
        registryKey(value, cls)?.let { return str(it, b) }
        if (depth >= MAX_DEPTH || seen.containsKey(value)) return JsonPrimitive(cls.simpleName)
        seen[value] = Unit
        try {
            return when (value) {
                is Map<*, *> -> {
                    val out = LinkedHashMap<String, JsonElement>()
                    for ((k, v) in value.entries) {
                        if (out.size >= MAX_ITEMS) break
                        out[k.toString().take(MAX_STRING).also { b.chars(it.length) }] = dump(v, depth + 1, seen, b)
                    }
                    JsonObject(out)
                }
                is Iterable<*> -> list(value.asSequence(), depth, seen, b)
                is IntArray -> list(value.asSequence(), depth, seen, b)
                is LongArray -> list(value.asSequence(), depth, seen, b)
                is ShortArray -> list(value.asSequence(), depth, seen, b)
                is FloatArray -> list(value.asSequence(), depth, seen, b)
                is DoubleArray -> list(value.asSequence(), depth, seen, b)
                is BooleanArray -> list(value.asSequence(), depth, seen, b)
                is Array<*> -> list(value.asSequence(), depth, seen, b)
                else -> fields(value, cls, depth, seen, b)
            }
        } finally {
            seen.remove(value)
        }
    }

    private fun list(items: Sequence<Any?>, depth: Int, seen: IdentityHashMap<Any, Unit>, b: Budget): JsonElement {
        val out = ArrayList<JsonElement>()
        var extra = 0
        for (item in items) {
            if (out.size < MAX_ITEMS) out += dump(item, depth + 1, seen, b) else extra++
        }
        if (extra > 0) out += JsonPrimitive("… $extra more")
        return JsonArray(out)
    }

    /** Records by component, everything else by declared instance fields; mixin fields (`x$y`) and synthetics are skipped. */
    private fun fields(value: Any, cls: Class<*>, depth: Int, seen: IdentityHashMap<Any, Unit>, b: Budget): JsonElement {
        if (cls.name.startsWith("java.") || cls.name.startsWith("jdk.") || cls.name.startsWith("io.netty.") || cls.name.startsWith("com.mojang.")) {
            return str(value.toString(), b)
        }
        val out = LinkedHashMap<String, JsonElement>()
        out["@"] = JsonPrimitive(cls.simpleName.ifEmpty { cls.name.substringAfterLast('.') })
        if (cls.isRecord) {
            for (component in cls.recordComponents) {
                val accessor = component.accessor
                out[component.name] = if (accessor.trySetAccessible()) dump(accessor.invoke(value), depth + 1, seen, b) else JsonPrimitive("?")
            }
            return JsonObject(out)
        }
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java && !c.name.startsWith("java.")) {
            for (field in c.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic || field.name.contains('$')) continue
                val dumped = if (field.trySetAccessible()) dump(field.get(value), depth + 1, seen, b) else JsonPrimitive("?")
                if (dumped != JsonNull || field.type.isPrimitive) out[field.name] = dumped
            }
            c = c.superclass
        }
        return JsonObject(out)
    }

    /** Objects that must never be walked: their class name says what they were. */
    private fun opaque(value: Any, cls: Class<*>): String? {
        val name = cls.name
        if (value is Level || value is Registry<*> || value is Class<*> || value is ClassLoader || value is Thread ||
            name.startsWith("net.minecraft.client.") || name.startsWith("net.minecraft.server.") || name.startsWith("com.mojang.blaze3d") ||
            name.startsWith("net.minecraft.core.RegistryAccess") || name.startsWith("net.minecraft.core.HolderLookup") || name.startsWith("net.minecraft.core.HolderSet")
        ) {
            return "<" + cls.simpleName.ifEmpty { name.substringAfterLast('.') } + ">"
        }
        return null
    }

    // ---------- registry entries as ids ----------

    private object NoRegistry

    private val registries: List<Registry<*>> by lazy { BuiltInRegistries.REGISTRY.toList() }
    private val registryOf = ConcurrentHashMap<Class<*>, Any>()

    /** The id of a vanilla registry entry (block entity type, menu type, potion, …), or null. Records and packets never are one. */
    private fun registryKey(value: Any, cls: Class<*>): String? {
        if (cls.isRecord || value is Packet<*> || !cls.name.startsWith("net.minecraft.")) return null
        return when (val known = registryOf[cls]) {
            NoRegistry -> null
            is Registry<*> -> keyIn(known, value)
            else -> {
                for (registry in registries) {
                    val key = keyIn(registry, value)
                    if (key != null) {
                        registryOf[cls] = registry
                        return key
                    }
                }
                registryOf[cls] = NoRegistry
                null
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun keyIn(registry: Registry<*>, value: Any): String? = (registry as Registry<Any>).getResourceKey(value).map { it.identifier().toString() }.orElse(null)

    // ---------- leaves ----------

    /** Compact SNBT, or the size when it is huge. */
    private fun nbt(tag: Tag, b: Budget): JsonElement {
        val size = tag.sizeInBytes()
        if (size > MAX_NBT_BYTES) return JsonObject(mapOf("@" to JsonPrimitive("nbt"), "bytes" to JsonPrimitive(size)))
        val text = tag.toString()
        val cut = if (text.length > MAX_NBT_CHARS) text.substring(0, MAX_NBT_CHARS) + "…" else text
        b.chars(cut.length)
        return JsonPrimitive(cut)
    }

    private fun raw(bytes: ByteArray, b: Budget): JsonElement {
        b.chars(minOf(bytes.size, MAX_RAW_BYTES) * 4 / 3)
        return JsonObject(
            mapOf(
                "length" to JsonPrimitive(bytes.size),
                "base64" to JsonPrimitive(Base64.getEncoder().encodeToString(if (bytes.size > MAX_RAW_BYTES) bytes.copyOf(MAX_RAW_BYTES) else bytes)),
            ),
        )
    }
}
