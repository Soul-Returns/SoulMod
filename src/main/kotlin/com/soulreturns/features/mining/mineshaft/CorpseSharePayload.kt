package com.soulreturns.features.mining.mineshaft

import com.soulreturns.util.MojangUuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.util.UUID

/** One located corpse on the wire: its listed [spot] (null for one outside the data), drawn [pos], type if known. */
data class SharedCorpse(val spot: CorpseSpot?, val pos: DoubleArray, val type: String?) {
    override fun equals(other: Any?): Boolean = other is SharedCorpse && spot == other.spot && pos.contentEquals(other.pos) && type == other.type

    override fun hashCode(): Int = (spot.hashCode() * 31 + pos.contentHashCode()) * 31 + type.hashCode()
}

/**
 * Party sharing of located corpses over the Soul backend (rule://mineshaft, rule://backend):
 * `POST /mineshaft/sync {serverId, recipients, message}`, delivered as realtime event
 * `mineshaft-sync` on each recipient's `user/<uuid>` topic with `message.uuid` (the authenticated
 * sender) and `message.serverId` stamped by the backend. Messages are versioned (`v` 1):
 * `hello` (a stay's layout is known) and `state` (the sender's full list of located corpses, so
 * every message is idempotent and a lost one costs nothing). Looted state is never on the wire.
 */
object CorpseSharePayload {
    const val VERSION = 1
    const val MAX_RECIPIENTS = 10
    const val MAX_CORPSES = 8
    private val TYPE = Regex("^[A-Za-z]{1,16}$")
    private val LAYOUT = Regex("^[A-Za-z0-9_]{1,16}$")

    /** Mineshaft coordinates (every layout uses one frame: spots x -196..-106, y 0..28, z -198..-154), with room for layouts not in the data. */
    private const val MIN_XZ = -320.0
    private const val MAX_XZ = 0.0
    private const val MIN_Y = -64.0
    private const val MAX_Y = 128.0

    sealed interface Inbound {
        val sender: UUID
    }

    data class Hello(override val sender: UUID, val layout: String) : Inbound

    data class State(override val sender: UUID, val layout: String, val corpses: List<SharedCorpse>) : Inbound

    fun hello(layout: String): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("kind", "hello")
        put("layout", layout)
    }

    fun state(layout: String, corpses: List<SharedCorpse>): JsonObject = buildJsonObject {
        put("v", VERSION)
        put("kind", "state")
        put("layout", layout)
        put("corpses", buildJsonArray {
            for (c in corpses.take(MAX_CORPSES)) {
                add(buildJsonObject {
                    put("spot", c.spot?.let { s -> buildJsonArray { add(JsonPrimitive(s.x)); add(JsonPrimitive(s.y)); add(JsonPrimitive(s.z)) } } ?: JsonNull)
                    put("pos", buildJsonArray { for (v in c.pos) add(JsonPrimitive(Math.round(v * 100) / 100.0)) })
                    put("type", c.type?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
    }

    /** The request body, or null when nobody but the player is in the party (nothing to send). */
    fun body(serverId: String, party: Collection<UUID>, self: UUID, message: JsonObject): JsonObject? {
        val recipients = party.filter { it != self }.distinct().take(MAX_RECIPIENTS)
        if (recipients.isEmpty()) return null
        return buildJsonObject {
            put("serverId", serverId)
            put("recipients", buildJsonArray { for (u in recipients) add(JsonPrimitive(MojangUuid.undashed(u))) })
            put("message", message)
        }
    }

    /** What an inbound event is, or why it is dropped. */
    sealed interface Parsed {
        data class Ok(val message: Inbound) : Parsed

        data class Drop(val reason: String) : Parsed
    }

    /**
     * Accept rules: addressed to the player's own topic, stamped sender a party member (not the
     * player), stamped server = the player's server, same layout, version 1, sane shape (finite
     * coordinates in the mineshaft frame, at most [MAX_CORPSES], type a short word). Anything else
     * is dropped with a reason.
     */
    fun parse(topic: String?, data: JsonObject, self: UUID, serverId: String?, layout: String?, party: Set<UUID>): Parsed {
        if (topic != "user/${MojangUuid.undashed(self)}") return Parsed.Drop("topic")
        if (serverId == null || layout == null) return Parsed.Drop("not in a mineshaft")
        val sender = (data["uuid"] as? JsonPrimitive)?.content?.let(MojangUuid::parse) ?: return Parsed.Drop("no sender")
        if (sender == self) return Parsed.Drop("own echo")
        if (sender !in party) return Parsed.Drop("sender not in party")
        if ((data["serverId"] as? JsonPrimitive)?.content != serverId) return Parsed.Drop("other server")
        if ((data["v"] as? JsonPrimitive)?.intOrNull != VERSION) return Parsed.Drop("version")
        val theirLayout = (data["layout"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (theirLayout == null || !LAYOUT.matches(theirLayout)) return Parsed.Drop("malformed layout")
        if (theirLayout != layout) return Parsed.Drop("other layout")
        val kind = (data["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (kind == "hello") return Parsed.Ok(Hello(sender, layout))
        if (kind != "state") return Parsed.Drop("kind")
        val list = data["corpses"] as? JsonArray ?: return Parsed.Drop("malformed corpses")
        if (list.size > MAX_CORPSES) return Parsed.Drop("too many corpses")
        val corpses = ArrayList<SharedCorpse>()
        for (e in list) corpses += corpse(e) ?: return Parsed.Drop("malformed corpse")
        return Parsed.Ok(State(sender, layout, corpses))
    }

    private fun corpse(e: JsonElement): SharedCorpse? {
        val o = e as? JsonObject ?: return null
        val pos = numbers(o["pos"]) ?: return null
        if (pos.size != 3 || pos.any { !it.isFinite() }) return null
        if (pos[0] !in MIN_XZ..MAX_XZ || pos[2] !in MIN_XZ..MAX_XZ || pos[1] !in MIN_Y..MAX_Y) return null
        val spotElement = o["spot"]
        val spot =
            if (spotElement == null || spotElement is JsonNull) {
                null
            } else {
                val ints = (spotElement as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.intOrNull ?: return null } ?: return null
                if (ints.size != 3) return null
                if (ints[0] !in MIN_XZ.toInt()..MAX_XZ.toInt() || ints[2] !in MIN_XZ.toInt()..MAX_XZ.toInt() || ints[1] !in MIN_Y.toInt()..MAX_Y.toInt()) return null
                CorpseSpot(ints[0], ints[1], ints[2])
            }
        val typeElement = o["type"]
        val type =
            if (typeElement == null || typeElement is JsonNull) {
                null
            } else {
                (typeElement as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { TYPE.matches(it) } ?: return null
            }
        return SharedCorpse(spot, pos, type)
    }

    private fun numbers(e: JsonElement?): DoubleArray? {
        val a = e as? JsonArray ?: return null
        return DoubleArray(a.size) { i -> (a[i] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null }
    }
}
