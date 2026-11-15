package com.soulreturns.features.dev

import com.google.common.collect.MapMaker
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.particles.ParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import net.minecraft.network.syncher.EntityDataSerializer
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.StateHolder
import java.lang.reflect.Modifier
import java.util.Base64
import java.util.IdentityHashMap
import java.util.Locale
import java.util.Optional
import java.util.OptionalDouble
import java.util.OptionalInt
import java.util.OptionalLong
import java.util.UUID

/**
 * Packets as JSON for [PacketLogger] and the dev bridge: a reflective dump (records by component,
 * other classes by field) with readable forms for components (`§` codes kept), items (id, count,
 * name, lore, SkyBlock `custom_data` as SNBT), ids, enums and raw bytes. Unknown custom payloads
 * keep their bytes (base64), which vanilla would discard.
 *
 * Call on the network thread, before the game can mutate objects the packet references.
 */
object PacketJson {
    private const val MAX_DEPTH = 8
    private const val MAX_ITEMS = 256
    private const val MAX_RAW_BYTES = 8192
    private const val MAX_STRING = 4096

    /** Chatty packet types (ids without namespace): chunks, light, movement, time, keep-alive, attributes, animations, particles. */
    val NOISY: Set<String> =
        setOf(
            "level_chunk_with_light", "light_update", "forget_level_chunk", "chunks_biomes", "chunk_batch_start",
            "chunk_batch_finished", "chunk_batch_received", "move_entity_pos", "move_entity_pos_rot", "move_entity_rot",
            "rotate_head", "set_entity_motion", "teleport_entity", "entity_position_sync", "set_time", "keep_alive",
            "ping", "pong", "bundle_delimiter", "move_player_pos", "move_player_pos_rot", "move_player_rot",
            "move_player_status_only", "client_tick_end", "player_input", "move_minecart_along_track",
            "update_attributes", "hurt_animation", "animate", "block_destruction", "swing", "level_particles",
        )

    /** Raw bytes of unknown custom payloads, keyed by payload identity (records compare by id). */
    private val discardedBytes: MutableMap<DiscardedPayload, ByteArray> = MapMaker().weakKeys().makeMap()

    /** From [com.soulreturns.mixin.DiscardedPayloadMixin] while [PacketTap] is active. */
    @JvmStatic
    fun rememberDiscarded(payload: DiscardedPayload, bytes: ByteArray) {
        discardedBytes[payload] = bytes
    }

    internal fun forgetDiscarded() = discardedBytes.clear()

    fun customPayload(packet: Packet<*>): CustomPacketPayload? =
        if (packet is ClientboundCustomPayloadPacket) packet.payload() else if (packet is ServerboundCustomPayloadPacket) packet.payload() else null

    /** The logged form `{t, dir, type, channel?, data}`; [dir] is `in` or `out`. */
    fun packet(dir: String, packet: Packet<*>, t: Long = System.currentTimeMillis()): JsonObject {
        val payload = customPayload(packet)
        val channel = payload?.type()?.id()?.toString()
        return JsonObject(
            buildMap {
                put("t", JsonPrimitive(t))
                put("dir", JsonPrimitive(dir))
                put("type", JsonPrimitive(packet.type().id().toString()))
                if (channel != null) put("channel", JsonPrimitive(channel))
                put("data", if (payload != null) payload(payload) else dump(packet))
            },
        )
    }

    private fun payload(payload: CustomPacketPayload): JsonElement {
        if (payload is DiscardedPayload) {
            val bytes = discardedBytes[payload] ?: return JsonObject(mapOf("unknown" to JsonPrimitive(true)))
            return JsonObject(mapOf("unknown" to JsonPrimitive(true), "bytes" to raw(bytes)))
        }
        return dump(payload)
    }

    fun dump(value: Any?): JsonElement = dump(value, 0, IdentityHashMap())

    private fun dump(value: Any?, depth: Int, seen: IdentityHashMap<Any, Unit>): JsonElement {
        if (value == null) return JsonNull
        when (value) {
            is String -> return JsonPrimitive(value.take(MAX_STRING))
            is Number -> return JsonPrimitive(value)
            is Boolean -> return JsonPrimitive(value)
            is Char -> return JsonPrimitive(value.toString())
            is Enum<*> -> return JsonPrimitive(value.name)
            is UUID, is Identifier, is ResourceKey<*> -> return JsonPrimitive(value.toString())
            is Component -> return JsonPrimitive(legacy(value))
            is ItemStack -> return item(value)
            is ByteArray -> return raw(value)
            is Optional<*> -> return if (value.isPresent) dump(value.get(), depth, seen) else JsonNull
            is OptionalInt -> return if (value.isPresent) JsonPrimitive(value.asInt) else JsonNull
            is OptionalLong -> return if (value.isPresent) JsonPrimitive(value.asLong) else JsonNull
            is OptionalDouble -> return if (value.isPresent) JsonPrimitive(value.asDouble) else JsonNull
            is Holder<*> -> return value.unwrapKey().map<JsonElement> { JsonPrimitive(it.identifier().toString()) }.orElseGet { dump(value.value(), depth, seen) }
            // Game objects whose reflective dump is megabytes of shapes, lambdas and state tables.
            is StateHolder<*, *> -> return JsonPrimitive(value.toString())
            is Block -> return JsonPrimitive(BuiltInRegistries.BLOCK.getKey(value).toString())
            is Item -> return JsonPrimitive(BuiltInRegistries.ITEM.getKey(value).toString())
            is EntityType<*> -> return JsonPrimitive(BuiltInRegistries.ENTITY_TYPE.getKey(value).toString())
            is ParticleType<*> -> return JsonPrimitive(BuiltInRegistries.PARTICLE_TYPE.getKey(value).toString())
            is SoundEvent -> return JsonPrimitive(value.location().toString())
            is MobEffect -> return JsonPrimitive(BuiltInRegistries.MOB_EFFECT.getKey(value).toString())
            is Attribute -> return JsonPrimitive(BuiltInRegistries.ATTRIBUTE.getKey(value).toString())
            is SynchedEntityData.DataValue<*> ->
                return JsonObject(mapOf("id" to JsonPrimitive(value.id()), "value" to dump(value.value(), depth + 1, seen)))
        }
        // Codecs and serializers carry no data, only behavior.
        if (value is EntityDataSerializer<*> || value.javaClass.name.contains("\$\$Lambda")) return JsonNull
        if (depth >= MAX_DEPTH || seen.containsKey(value)) return JsonPrimitive(value.javaClass.simpleName)
        seen[value] = Unit
        try {
            return when (value) {
                is Map<*, *> -> JsonObject(value.entries.take(MAX_ITEMS).associate { (k, v) -> k.toString() to dump(v, depth + 1, seen) })
                is Iterable<*> -> list(value.asSequence(), depth, seen)
                is IntArray -> list(value.asSequence(), depth, seen)
                is LongArray -> list(value.asSequence(), depth, seen)
                is ShortArray -> list(value.asSequence(), depth, seen)
                is FloatArray -> list(value.asSequence(), depth, seen)
                is DoubleArray -> list(value.asSequence(), depth, seen)
                is BooleanArray -> list(value.asSequence(), depth, seen)
                is Array<*> -> list(value.asSequence(), depth, seen)
                else -> fields(value, depth, seen)
            }
        } finally {
            seen.remove(value)
        }
    }

    private fun list(items: Sequence<Any?>, depth: Int, seen: IdentityHashMap<Any, Unit>): JsonElement {
        val out = ArrayList<JsonElement>()
        var extra = 0
        for (item in items) {
            if (out.size < MAX_ITEMS) out += dump(item, depth + 1, seen) else extra++
        }
        if (extra > 0) out += JsonPrimitive("… $extra more")
        return JsonArray(out)
    }

    /** Records by component, everything else by declared instance fields (superclasses included). */
    private fun fields(value: Any, depth: Int, seen: IdentityHashMap<Any, Unit>): JsonElement {
        val cls = value.javaClass
        // JDK and library internals: their toString is the useful part, and reflection into them is closed.
        if (cls.name.startsWith("java.") || cls.name.startsWith("jdk.") || cls.name.startsWith("io.netty.") || cls.name.startsWith("com.mojang.")) {
            return JsonPrimitive(value.toString().take(MAX_STRING))
        }
        val out = LinkedHashMap<String, JsonElement>()
        out["@"] = JsonPrimitive(cls.simpleName.ifEmpty { cls.name.substringAfterLast('.') })
        if (cls.isRecord) {
            for (component in cls.recordComponents) {
                val accessor = component.accessor
                out[component.name] =
                    if (accessor.trySetAccessible()) dump(accessor.invoke(value), depth + 1, seen) else JsonPrimitive("?")
            }
            return JsonObject(out)
        }
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java && !c.name.startsWith("java.")) {
            for (field in c.declaredFields) {
                if (Modifier.isStatic(field.modifiers) || field.isSynthetic) continue
                val dumped = if (field.trySetAccessible()) dump(field.get(value), depth + 1, seen) else JsonPrimitive("?")
                if (dumped != JsonNull || field.type.isPrimitive) out[field.name] = dumped
            }
            c = c.superclass
        }
        return JsonObject(out)
    }

    private fun raw(bytes: ByteArray): JsonElement =
        JsonObject(
            mapOf(
                "length" to JsonPrimitive(bytes.size),
                "base64" to JsonPrimitive(Base64.getEncoder().encodeToString(if (bytes.size > MAX_RAW_BYTES) bytes.copyOf(MAX_RAW_BYTES) else bytes)),
            ),
        )

    /** `"empty"`, or `{id, count, name, lore?, custom_data?}` with `§` strings and SNBT custom data. */
    fun item(stack: ItemStack): JsonElement {
        if (stack.isEmpty) return JsonPrimitive("empty")
        val out = LinkedHashMap<String, JsonElement>()
        out["id"] = JsonPrimitive(BuiltInRegistries.ITEM.getKey(stack.item).toString())
        out["count"] = JsonPrimitive(stack.count)
        out["name"] = JsonPrimitive(legacy(stack.hoverName))
        stack.get(DataComponents.LORE)?.let { lore -> out["lore"] = JsonArray(lore.lines().map { JsonPrimitive(legacy(it)) }) }
        stack.get(DataComponents.CUSTOM_DATA)?.let { out["custom_data"] = JsonPrimitive(it.copyTag().toString().take(MAX_STRING)) }
        return JsonObject(out)
    }

    /** A component as a legacy `§` string: Hypixel features parse exactly this form. */
    fun legacy(component: Component): String {
        val sb = StringBuilder()
        component.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                if (text.isNotEmpty()) {
                    sb.append(codes(style))
                    sb.append(text)
                }
                Optional.empty()
            },
            Style.EMPTY,
        )
        return sb.toString().take(MAX_STRING)
    }

    private val LEGACY_RGB =
        intArrayOf(
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        )

    private fun codes(style: Style): String {
        val sb = StringBuilder("§r")
        style.color?.value?.let { rgb ->
            val index = LEGACY_RGB.indexOf(rgb)
            if (index >= 0) sb.append('§').append("0123456789abcdef"[index]) else sb.append(String.format(Locale.ROOT, "§#%06x", rgb))
        }
        if (style.isBold) sb.append("§l")
        if (style.isItalic) sb.append("§o")
        if (style.isUnderlined) sb.append("§n")
        if (style.isStrikethrough) sb.append("§m")
        if (style.isObfuscated) sb.append("§k")
        return sb.toString()
    }
}
