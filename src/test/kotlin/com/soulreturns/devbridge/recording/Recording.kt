package com.soulreturns.devbridge.recording

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/**
 * Reads a dev-bridge recording (`*.rec.ndjson.gz`, format `soul-devbridge-recording` v1 and v2) as typed
 * frames, so rule logic can be unit-tested against recorded real runs without the game. A v2 line is
 * `{t, tick, ping, kind, data}` (`tick` = server game time, the clock), a v1 line `{t, tick, gt, kind,
 * data}` (`tick` = ping count, `gt` = client game time; [Stamp] maps it to the same fields); kinds this
 * reader does not model come back as [Other]
 * with their raw JSON. A recording cut off mid-write (still running, crash) ends cleanly.
 */
object Recording {
    const val FORMAT = "soul-devbridge-recording"
    const val VERSION = 2

    private val json = Json { ignoreUnknownKeys = true }

    fun read(path: Path): List<Frame> = Files.newInputStream(path).use { read(it, path.toString().endsWith(".gz")) }

    fun read(input: InputStream, gzip: Boolean = true): List<Frame> {
        val reader = BufferedReader(InputStreamReader(if (gzip) GZIPInputStream(input) else input, StandardCharsets.UTF_8))
        val frames = ArrayList<Frame>()
        while (true) {
            val line =
                try {
                    reader.readLine()
                } catch (_: EOFException) {
                    null
                } ?: break
            if (line.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(line) as JsonObject }.getOrNull() ?: continue
            frames += parse(obj)
        }
        return frames
    }

    fun parse(obj: JsonObject): Frame {
        val s =
            if (obj.containsKey("gt")) Stamp(obj.long("t") ?: 0L, obj.long("gt"), obj.long("tick")) else Stamp(obj.long("t") ?: 0L, obj.long("tick"), obj.long("ping"))
        val kind = obj.string("kind") ?: ""
        val d = obj["data"] as? JsonObject ?: return Other(s, kind, obj["data"] ?: JsonNull)
        return when (kind) {
            "header" -> Header(s, d.string("format") ?: "", d.int("version") ?: 0, d.string("player"), d.string("server"), d)
            "self" ->
                Self(
                    s, d.string("name") ?: "", d.vec("pos") ?: Vec3.ZERO, d.double("yaw") ?: 0.0, d.double("pitch") ?: 0.0,
                    d.bool("on_ground") == true, d.bool("sneaking") == true, (d["held"] as? JsonObject)?.string("sb_id"),
                    (d["held"] as? JsonObject)?.string("id"), (d["room"] as? JsonObject)?.string("name"),
                )
            "players" ->
                Players(
                    s,
                    (d["players"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { p ->
                        TrackedPlayer(p.string("name") ?: "", p.string("uuid") ?: "", p.bool("real") == true, p.vec("pos") ?: Vec3.ZERO, p.double("yaw") ?: 0.0, p.double("distance") ?: 0.0)
                    },
                )
            "party" ->
                Party(
                    s,
                    (d["members"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { m ->
                        PartyMember(m.string("name") ?: "", m.string("class"), m.bool("dead") == true, m.string("source") ?: "none", m.vec("pos"), m.long("age_ms"))
                    },
                )
            "chat" -> Chat(s, d.string("text") ?: "")
            "title" -> Title(s, d.string("type") ?: "", d.string("text"))
            "bossbar" -> BossBar(s, d.string("op") ?: "", d.string("name"), d.double("progress"))
            "mark" -> Mark(s, d.int("n") ?: 0, d.string("label") ?: "", d.vec("pos") ?: Vec3.ZERO, d.double("yaw") ?: 0.0, d.double("pitch") ?: 0.0, d["looking_at"] as? JsonObject)
            "screen_open" -> Menu(s, true, d.string("title"))
            "screen_close" -> Menu(s, false, d.string("title"))
            "packet" -> Packet(s, d.string("dir") ?: "", d.string("type") ?: "", d["data"] ?: JsonNull)
            "snapshot" -> Snapshot(s, d)
            "end" -> End(s, d.string("reason"), d)
            else -> Other(s, kind, d)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    /** `[x, y, z]`; y may be null (map positions have none) and comes back as NaN. */
    private fun JsonObject.vec(key: String): Vec3? {
        val a = this[key] as? JsonArray ?: return null
        if (a.size < 3) return null
        fun c(i: Int) = (a[i] as? JsonPrimitive)?.doubleOrNull ?: Double.NaN
        return Vec3(c(0), c(1), c(2))
    }
}

/** [tick] is the clock to use, [ping] the raw ping count; a v1 line (`tick` = ping count, `gt` = client game time) maps onto the same two fields. */
data class Stamp(val t: Long, val tick: Long?, val ping: Long?)

data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun distance(o: Vec3): Double = Math.sqrt((x - o.x) * (x - o.x) + (y - o.y) * (y - o.y) + (z - o.z) * (z - o.z))

    /** Larger of |dx| and |dz| (how Hypixel's player tracking range behaves, see docs/research). */
    fun squareDistance(o: Vec3): Double = maxOf(Math.abs(x - o.x), Math.abs(z - o.z))

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

sealed class Frame {
    abstract val stamp: Stamp
    abstract val kind: String
    val t: Long get() = stamp.t

    /** The server tick to use (v2: `tick`; v1 lines carry it as `gt`, their `tick` being the ping count). */
    val clock: Long? get() = stamp.tick
}

data class Header(override val stamp: Stamp, val format: String, val version: Int, val player: String?, val server: String?, val raw: JsonObject) : Frame() {
    override val kind = "header"
}

data class Self(
    override val stamp: Stamp,
    val name: String,
    val pos: Vec3,
    val yaw: Double,
    val pitch: Double,
    val onGround: Boolean,
    val sneaking: Boolean,
    val heldSbId: String?,
    val heldId: String?,
    val room: String?,
) : Frame() {
    override val kind = "self"
}

data class TrackedPlayer(val name: String, val uuid: String, val real: Boolean, val pos: Vec3, val yaw: Double, val distance: Double)

data class Players(override val stamp: Stamp, val players: List<TrackedPlayer>) : Frame() {
    override val kind = "players"
}

data class PartyMember(val name: String, val dungeonClass: String?, val dead: Boolean, val source: String, val pos: Vec3?, val ageMs: Long?)

data class Party(override val stamp: Stamp, val members: List<PartyMember>) : Frame() {
    override val kind = "party"
}

data class Chat(override val stamp: Stamp, val text: String) : Frame() {
    override val kind = "chat"
    val stripped: String get() = text.replace(Regex("§(#[0-9a-fA-F]{6}|.)"), "")
}

data class Title(override val stamp: Stamp, val type: String, val text: String?) : Frame() {
    override val kind = "title"
}

data class BossBar(override val stamp: Stamp, val op: String, val name: String?, val progress: Double?) : Frame() {
    override val kind = "bossbar"
}

data class Mark(override val stamp: Stamp, val n: Int, val label: String, val pos: Vec3, val yaw: Double, val pitch: Double, val lookingAt: JsonObject?) : Frame() {
    override val kind = "mark"
}

data class Menu(override val stamp: Stamp, val open: Boolean, val title: String?) : Frame() {
    override val kind = if (open) "screen_open" else "screen_close"
}

data class Packet(override val stamp: Stamp, val dir: String, val type: String, val data: JsonElement) : Frame() {
    override val kind = "packet"
}

data class Snapshot(override val stamp: Stamp, val data: JsonObject) : Frame() {
    override val kind = "snapshot"
}

data class End(override val stamp: Stamp, val reason: String?, val raw: JsonObject) : Frame() {
    override val kind = "end"
}

data class Other(override val stamp: Stamp, override val kind: String, val data: JsonElement) : Frame()
