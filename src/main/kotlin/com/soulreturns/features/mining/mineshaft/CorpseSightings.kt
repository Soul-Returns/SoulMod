package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.util.MobSpotter
import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import kotlin.math.sqrt

/** A corpse stand the player saw this visit: exact position and its helmet's type ([CorpseLook]). */
data class CorpseSighting(val entityId: Int, val x: Double, val y: Double, val z: Double, val type: String)

/** A helmeted stand in sight this scan whose helmet no corpse wears (decor, or an `other` corpse). */
data class OtherStand(val entityId: Int, val x: Double, val y: Double, val z: Double)

/**
 * The one line-of-sight scan for corpse stands in a mineshaft (rule://mineshaft). It runs while the
 * Corpse locator **or** "Log mineshaft visits" is on: the locator places corpses from it, the visit
 * upload carries the stands seen ([forUpload]), and stands on unlisted spots are learned
 * ([CorpseSpotMemory]). With the locator off nothing is drawn; the scan is the same.
 *
 * Corpse armor stands are on the client through walls, so an entity is read only once the player
 * can see it ([MobSpotter], ToS). Corpse helmets are sight-tested before decor (`WILL_O_WISP`,
 * `GLACITE_HELMET` stands exist in mineshafts) and each corpse stand only until it was seen.
 */
object CorpseSightings {
    /** Cadence of the scan. */
    const val SCAN_TICKS = 10

    /** Stands within this many blocks of the player, at most [SCAN_LIMIT] sight tests per scan. */
    private const val SCAN_RADIUS = 32.0
    private const val SCAN_LIMIT = 8

    /** Added to a non-corpse helmet's rank: beyond any squared distance in the scan box. */
    private const val OTHER_RANK = 1.0e6

    /** The visit upload carries at most this many stands. */
    const val MAX_UPLOAD = 12

    /** Two sightings this close are the same stand (a re-sent entity gets a new id). */
    private const val SAME_STAND = 0.5

    private val TYPE = Regex("^[A-Za-z]{1,16}$")

    private val visit = ArrayList<CorpseSighting>()
    private val done = HashSet<Int>()
    private var learned = 0
    private var ticks = 0

    /** Corpse stands seen this visit, in the order seen. */
    val corpses: List<CorpseSighting> get() = visit

    private fun wanted(): Boolean = cfg.mining.mineshaft.corpseLocator || cfg.mining.mineshaft.logVisits

    /** A new visit (also before the first): nothing seen yet. */
    fun clear() {
        visit.clear()
        done.clear()
        learned = 0
        ticks = 0
    }

    /**
     * From [MineshaftTick], inside the mineshaft area. Learns this visit's sightings once the layout
     * is known; every [SCAN_TICKS] scans. Returns the non-corpse helmeted stands in sight this scan
     * (null on other ticks) for the locator; corpse stands land in [corpses].
     */
    fun tick(): List<OtherStand>? {
        if (!wanted()) return null
        MineshaftState.serverInstance?.let { layout ->
            while (learned < visit.size) visit[learned++].let { CorpseSpotMemory.record(layout, it.x, it.y, it.z, it.type) }
        }
        if (++ticks % SCAN_TICKS != 0) return null
        val p = Minecraft.getInstance().player ?: return null
        val r = SCAN_RADIUS
        val box = AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r)
        val visible =
            MobSpotter.findAllVisibleIn(box, SCAN_LIMIT) {
                if (it !is ArmorStand || it.id in done) return@findAllVisibleIn null
                val head = it.getItemBySlot(EquipmentSlot.HEAD)
                if (head.isEmpty) return@findAllVisibleIn null
                val d = it.distanceToSqr(p.x, p.y, p.z)
                if (CorpseLook.typeOf(SkyblockItemUtils.getSkyblockId(head)) == CorpseLook.OTHER) d + OTHER_RANK else d
            }
        val others = ArrayList<OtherStand>()
        for (stand in visible) {
            val type = CorpseLook.typeOf(SkyblockItemUtils.getSkyblockId((stand as ArmorStand).getItemBySlot(EquipmentSlot.HEAD)))
            if (type == CorpseLook.OTHER) {
                others += OtherStand(stand.id, stand.x, stand.y, stand.z)
                continue
            }
            done += stand.id
            visit += CorpseSighting(stand.id, stand.x, stand.y, stand.z, type)
        }
        return others
    }

    /** An `other` stand the locator used: no sight test again this visit. */
    fun resolve(entityId: Int) {
        done += entityId
    }

    /**
     * The visit upload's `corpseSpots`: distinct stands seen this visit, at most [MAX_UPLOAD], each
     * within the backend's bounds (a malformed entry makes the whole visit a 400): finite,
     * |x|, |z| <= 1024, -64 <= y <= 320; a type outside `^[A-Za-z]{1,16}$` is left out.
     */
    fun forUpload(): List<SeenCorpseSpot> = forUpload(visit)

    internal fun forUpload(sightings: List<CorpseSighting>): List<SeenCorpseSpot> {
        val out = ArrayList<CorpseSighting>()
        for (s in sightings) {
            if (out.size >= MAX_UPLOAD) break
            if (!s.x.isFinite() || !s.y.isFinite() || !s.z.isFinite() || Math.abs(s.x) > 1024 || Math.abs(s.z) > 1024 || s.y < -64 || s.y > 320) continue
            if (out.none { sqrt((it.x - s.x) * (it.x - s.x) + (it.y - s.y) * (it.y - s.y) + (it.z - s.z) * (it.z - s.z)) <= SAME_STAND }) out += s
        }
        return out.map { s -> SeenCorpseSpot(listOf(round(s.x), round(s.y), round(s.z)), s.type.takeIf { TYPE.matches(it) }) }
    }

    private fun round(v: Double): Double = Math.round(v * 1000) / 1000.0
}
