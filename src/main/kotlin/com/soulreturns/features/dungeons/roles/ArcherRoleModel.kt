package com.soulreturns.features.dungeons.roles

import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.boss.BossPhase
import com.soulreturns.features.dungeons.leap.TeammateLocations
import com.soulreturns.util.MessageDetector

data class RoleObjective(val id: String, val label: String, val x: Double, val y: Double, val z: Double)
data class ArcherCue(
    val phase: String,
    val text: String,
    val detail: String,
    val target: DungeonClass? = null,
    val targetName: String? = null,
    val objective: RoleObjective? = null,
    val waiting: Boolean = false,
)

/** Observations advance this route; elapsed time never completes an objective or a leap. */
class ArcherRoleModel {
    var step = -1
        private set
    private var completed = 0
    private var pickups = 0
    private var placed = 0
    private var carrying = false
    private var slotPowered: Boolean? = null
    private var lastPhase = BossPhase.NONE
    private var sectionUnknown = false
    private var published: ArcherCue? = null
    private var publishedStep = -2
    private var publishedOpen = -1
    private var publishedName: String? = null
    private var publishedKnown = false
    private var publishedProtection = -1

    fun reset() {
        step = -1
        completed = 0
        pickups = 0
        placed = 0
        carrying = false
        slotPowered = null
        lastPhase = BossPhase.NONE
        sectionUnknown = false
        published = null
        publishedStep = -2
    }

    fun sync(phase: Int, openSection: Int, ownArea: Int, maxorEmpty: Boolean, leftPurple: Boolean, necronFive: Boolean, gateThree: Boolean) {
        if (phase < lastPhase && phase == BossPhase.MAXOR) reset()
        sectionUnknown = phase == BossPhase.GOLDOR && openSection == 0
        lastPhase = maxOf(lastPhase, phase)
        if (phase == BossPhase.MAXOR && step < 0) step = 0
        if (maxorEmpty && step in 0..7) step = 8
        if (phase >= BossPhase.STORM && step < 9) step = 9
        if (leftPurple && step == 9) step = 10
        if (phase >= BossPhase.STORM_DONE && step < 12) step = 12
        if (phase >= BossPhase.GOLDOR && phase < BossPhase.CORE && openSection in 1..4) {
            if (step < 13 && ownArea == 1) step = 13
            val first = SECTION_START[openSection]
            if (openSection > 1 && step < first) step = first
        }
        if (gateThree) complete("s3.gate")
        if (phase >= BossPhase.CORE && step < 26) step = 26
        if (phase >= BossPhase.NECRON && necronFive && step == 26) step = 27
        skipCompleted()
    }

    fun chat(line: String, self: String, targetClass: DungeonClass?, actorX: Double?, actorY: Double?, actorZ: Double?) {
        if (MessageDetector.isPlayerMessage(line)) return
        if (line.startsWith("[BOSS] Maxor:") && step < 0) step = 0
        if (line == "$self picked up an Energy Crystal!" && step <= 7) {
            if (carrying) return
            if (pickups < 2) pickups++
            carrying = true
            step = if (pickups <= 1) 1 else 5
            return
        }
        if (line.startsWith("You have teleported to ") && TELEPORT.matches(line)) {
            val expected = STEPS.getOrNull(step)?.target
            if (expected != null && expected == targetClass) {
                step++
                skipCompleted()
            }
            return
        }
        val match = COMPLETION.matchEntire(line) ?: return
        val n = match.groupValues[3].toIntOrNull() ?: return
        val of = match.groupValues[4].toIntOrNull() ?: return
        if (n !in 1..of || of !in 7..8 || actorX == null || actorY == null || actorZ == null) return
        val kind = if (match.groupValues[2] == "activated a terminal") TERMINAL else if (match.groupValues[2] == "activated a lever") LEVER else return
        val nearest = nearestObjective(kind, actorX, actorY, actorZ) ?: return
        complete(nearest.id)
    }

    fun crystalSlot(powered: Boolean, nearSlot: Boolean, crystalRespawned: Boolean) {
        if (carrying && nearSlot && powered && slotPowered == false && step in 1..6) {
            carrying = false
            placed++
            step = if (placed < 2) 3 else 7
        }
        if (step == 3 && crystalRespawned) step = 4
        slotPowered = powered
    }

    /** Being at the actual destination is also an arrival anchor (walking or a missed earlier leap line). */
    fun arrived(x: Double, y: Double, z: Double) {
        if (target != null && destination(x, y, z)) {
            step++
            skipCompleted()
        }
    }

    fun complete(id: String) {
        val index = STEPS.indexOfFirst { it.objective?.id == id }
        if (index < 13) return
        completed = completed or (1 shl index)
        skipCompleted()
    }

    private fun skipCompleted() {
        while (step in 13..25 && completed and (1 shl step) != 0) step++
    }

    fun cue(openSection: Int, name: String?, destinationKnown: Boolean, protection: Int): ArcherCue? {
        if (step !in STEPS.indices || step == 28) return null
        if (sectionUnknown) return UNKNOWN_SECTION
        if (publishedStep == step && publishedOpen == openSection && publishedName == name &&
            publishedKnown == destinationKnown && publishedProtection == protection
        ) return published
        publishedStep = step
        publishedOpen = openSection
        publishedName = name
        publishedKnown = destinationKnown
        publishedProtection = protection
        val s = STEPS[step]
        val entry = when (step) { 15 -> 2; 18 -> 3; 23 -> 4; else -> 0 }
        val ahead = entry > 0 && openSection < entry
        val objectiveLocked = s.section > 0 && s.objective != null && openSection < s.section
        val text: String
        val detail: String
        val waiting: Boolean
        if (objectiveLocked) {
            text = "Wait · S${s.section} not open"
            detail = "Requires the live section-open signal"
            waiting = true
        } else if (ahead && !destinationKnown) {
            text = "Wait · ${s.target!!.displayName} location ?"
            detail = "S$entry not open · destination unconfirmed"
            waiting = true
        } else if (ahead && protection != DeathSaveTracker.USABLE) {
            text = "Wait for S$entry to open"
            detail = if (protection == DeathSaveTracker.SWAP) "Ready protection needs a manual swap · verify before preleap" else if (protection == DeathSaveTracker.SPENT) "All observed protection spent" else "Protection unknown"
            waiting = true
        } else if (s.target != null) {
            text = "${if (ahead) "Preleap" else "Leap"} → ${s.target.displayName}"
            detail = if (ahead) "Protection ready now · not a guarantee for later checks" else if (destinationKnown) s.destination else "Destination unknown · ?"
            waiting = false
        } else {
            text = s.label
            detail = if (s.objective != null) "${number(s.objective.x)} · ${number(s.objective.y)} · ${number(s.objective.z)}" else "Waiting for a live signal"
            waiting = s.objective == null
        }
        return ArcherCue(s.phase, text, detail, s.target, name, if (objectiveLocked) null else s.objective, waiting).also { published = it }
    }

    val target: DungeonClass? get() = STEPS.getOrNull(step)?.target

    fun destination(x: Double, y: Double, z: Double): Boolean = when (step) {
        1, 5 -> x in 90.0..98.0 && y in 220.0..228.0 && z in 37.0..45.0
        8 -> x in 68.0..84.0 && y in 160.0..200.0 && z in 32.0..49.0
        10 -> x in 30.0..62.0 && y in 165.0..175.0 && z in 60.0..75.0
        12 -> x in 104.0..113.0 && y in 117.0..125.0 && z in 88.0..99.0
        15 -> x in 55.0..74.0 && y in 103.0..138.0 && z in 128.0..146.0
        18 -> x in -1.0..8.0 && y in 105.0..121.0 && z in 97.0..115.0
        23, 25 -> x in 50.0..60.0 && y in 110.0..122.0 && z in 44.0..65.0
        27 -> x in 49.0..61.0 && y in 2.0..10.0 && z in 70.0..82.0
        else -> false
    }

    /** A fresh own location report is usable; movement intentions and inferred leap destinations are not. */
    fun destination(location: TeammateLocations.Location): Boolean {
        if (location.source == TeammateLocations.Source.NONE || location.stale || !location.atSpot ||
            location.ageMs !in 0..TeammateLocations.FRESH_MS
        ) return false
        return when (step) {
            12 -> location.label == "SS"
            15 -> location.label == "ee2" || location.label == "Lights"
            18 -> location.label == "ee3"
            23, 25 -> location.label == "Core"
            27 -> location.label == "P5"
            else -> false
        }
    }

    class Step(val phase: String, val label: String, val objective: RoleObjective? = null, val target: DungeonClass? = null, val destination: String = "", val section: Int = 0)
    class LocatedObjective(val id: String, val kind: Int, val x: Double, val y: Double, val z: Double)

    companion object {
        const val TERMINAL = 1
        const val LEVER = 2
        val TELEPORT = Regex("^You have teleported to (\\w{1,16})!$")
        val COMPLETION = Regex("^(\\w{1,16}) (activated a terminal|activated a lever|completed a device)! \\((\\d+)/(\\d+)\\)$")
        private val SECTION_START = intArrayOf(0, 13, 16, 19, 24)
        private val UNKNOWN_SECTION = ArcherCue("Goldor", "Wait · section unknown", "Requires a live section-open signal", waiting = true)
        private fun objective(id: String, label: String, x: Double, y: Double, z: Double, section: Int = 0) = Step(if (section == 0) "Maxor" else "S$section", label, RoleObjective(id, label, x, y, z), section = section)
        private fun leap(phase: String, target: DungeonClass, destination: String) = Step(phase, target.displayName, target = target, destination = destination)
        val STEPS = arrayOf(
            objective("p1.left1", "LEFT crystal", 83.0, 238.0, 51.0),
            leap("Maxor", DungeonClass.TANK, "Crystal slot"),
            objective("p1.place1", "Place crystal", 94.0, 224.0, 41.0),
            Step("Maxor", "Wait for next crystal cycle"),
            objective("p1.left2", "LEFT crystal", 83.0, 238.0, 51.0),
            leap("Maxor", DungeonClass.TANK, "Crystal slot"),
            objective("p1.place2", "Place crystal", 94.0, 224.0, 41.0),
            Step("Maxor", "Wait for Maxor"),
            leap("Maxor", DungeonClass.BERSERK, "Storm entry"),
            Step("Storm", "Wait for Storm to leave purple"),
            leap("Storm", DungeonClass.MAGE, "Yellow pillar"),
            Step("Storm", "Wait for Storm"),
            leap("Transition", DungeonClass.HEALER, "SS"),
            objective("s1.t4", "S1 T4", 89.0, 122.0, 101.0, 1),
            objective("s1.t3", "S1 T3", 89.0, 112.0, 92.0, 1),
            leap("S1 → S2", DungeonClass.MAGE, "ee2"),
            objective("s2.t5", "S2 T5", 39.0, 108.0, 143.0, 2),
            objective("s2.lever", "Bottom lever", 27.0, 124.0, 127.0, 2),
            leap("S2 → S3", DungeonClass.HEALER, "ee3"),
            objective("s3.t4", "S3 T4", -3.0, 109.0, 77.0, 3),
            objective("s3.left", "Left lever", 2.0, 122.0, 55.0, 3),
            objective("s3.gate", "S3 gate", 8.5, 122.5, 51.5, 3),
            objective("s3.right", "Right lever", 14.0, 122.0, 55.0, 3),
            leap("S3 → S4", DungeonClass.MAGE, "Core"),
            objective("s4.t2", "S4 T2", 44.0, 121.0, 29.0, 4),
            leap("S4 → Core", DungeonClass.MAGE, "Core"),
            Step("Necron", "Wait for Necron 5%"),
            leap("Necron", DungeonClass.HEALER, "P5 floor"),
            Step("Route complete", "No further role cues"),
        )
        val OBJECTIVES = arrayOf(
            LocatedObjective("s1.t1", TERMINAL, 111.0, 113.0, 73.0), LocatedObjective("s1.t2", TERMINAL, 111.0, 119.0, 79.0),
            LocatedObjective("s1.t3", TERMINAL, 89.0, 112.0, 92.0), LocatedObjective("s1.t4", TERMINAL, 89.0, 122.0, 101.0),
            LocatedObjective("s1.l1", LEVER, 94.0, 124.0, 113.0), LocatedObjective("s1.l2", LEVER, 106.0, 124.0, 113.0),
            LocatedObjective("s2.t1", TERMINAL, 68.0, 109.0, 121.0), LocatedObjective("s2.t2", TERMINAL, 59.0, 120.0, 122.0),
            LocatedObjective("s2.t3", TERMINAL, 47.0, 109.0, 121.0), LocatedObjective("s2.t4", TERMINAL, 40.0, 124.0, 122.0),
            LocatedObjective("s2.t5", TERMINAL, 39.0, 108.0, 143.0), LocatedObjective("s2.lever", LEVER, 27.0, 124.0, 127.0),
            LocatedObjective("s2.top", LEVER, 23.0, 132.0, 138.0),
            LocatedObjective("s3.t1", TERMINAL, -3.0, 109.0, 112.0), LocatedObjective("s3.t2", TERMINAL, -3.0, 119.0, 93.0),
            LocatedObjective("s3.t3", TERMINAL, 19.0, 123.0, 93.0), LocatedObjective("s3.t4", TERMINAL, -3.0, 109.0, 77.0),
            LocatedObjective("s3.left", LEVER, 2.0, 122.0, 55.0), LocatedObjective("s3.right", LEVER, 14.0, 122.0, 55.0),
            LocatedObjective("s4.t1", TERMINAL, 41.0, 109.0, 29.0), LocatedObjective("s4.t2", TERMINAL, 44.0, 121.0, 29.0),
            LocatedObjective("s4.t3", TERMINAL, 67.0, 109.0, 29.0), LocatedObjective("s4.t4", TERMINAL, 72.0, 115.0, 48.0),
            LocatedObjective("s4.l1", LEVER, 84.0, 121.0, 34.0), LocatedObjective("s4.l2", LEVER, 86.0, 128.0, 46.0),
        )

        fun nearestObjective(kind: Int, x: Double, y: Double, z: Double): LocatedObjective? {
            var best: LocatedObjective? = null
            var distance = 4.5 * 4.5
            for (o in OBJECTIVES) {
                if (o.kind != kind) continue
                val d = (o.x - x) * (o.x - x) + (o.y - y) * (o.y - y) + (o.z - z) * (o.z - z)
                if (d < distance) { distance = d; best = o }
            }
            return best
        }

        private fun number(n: Double): String = if (n == n.toInt().toDouble()) n.toInt().toString() else n.toString()
    }
}
