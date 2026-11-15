package com.soulreturns.features.dungeons.leap

/**
 * Where-am-I messages in party chat (research `docs/research/spirit-leap-2026-11-13.md` §3.2-3.4, spec
 * `mocks/leap-menu/SPEC.md` §7): a teammate's call-out (`at ee3!`), coordinates (`x: 92, y: 122, z: 101`) or leap
 * line (`Leaped to go9o!`), and the named spots of the F7/M7 boss room. Pure: no Minecraft types.
 */
object PartyCallouts {
    sealed class Callout {
        data class Spot(val label: String, val arrived: Boolean = true) : Callout()
        data class Coords(val x: Double, val y: Double, val z: Double) : Callout()
        data class Leap(val target: String) : Callout()
    }

    /** `Party > [MVP+] go9o: at ss!` (color-stripped) → sender and body. */
    private val PARTY = Regex("^Party > (?:\\[[^\\]]{1,12}] )?(\\w{1,16}): (.+)$")

    fun partyLine(stripped: String): Pair<String, String>? = PARTY.matchEntire(stripped.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    /** Our labels and the words players use for them. Closed on purpose: only these words make a call-out, so `at least they said` never matches. */
    val SPOTS: List<Pair<String, List<String>>> = listOf(
        "SS" to listOf("ss", "simon", "simon says", "ss dev"),
        "ee2" to listOf("ee2", "ee 2", "early enter 2"),
        "ee3" to listOf("ee3", "ee 3", "early enter 3"),
        "ee4" to listOf("ee4", "ee 4", "early enter 4"),
        "Core" to listOf("core", "core entrance"),
        "Lights" to listOf("levers dev", "levers", "lights", "lights dev", "lever dev"),
        "Arrows" to listOf("arrows dev", "arrows", "arrow align", "align", "arrow dev"),
        "i4" to listOf("i4", "4th dev", "fourth dev", "sharpshooter", "ss4"),
        "Mid" to listOf("mid", "necron mid", "middle"),
        "P5" to listOf("p5", "dragons", "drags", "wither king"),
        "Pre 2" to listOf("pre2", "pre 2", "pre-2"),
        "Pre 3" to listOf("pre3", "pre 3", "pre-3"),
        "Pre 4" to listOf("pre4", "pre 4", "pre-4", "predev", "pre dev"),
        "S1" to listOf("s1", "section 1"),
        "S2" to listOf("s2", "section 2"),
        "S3" to listOf("s3", "section 3"),
        "S4" to listOf("s4", "section 4"),
        "P1" to listOf("p1"),
        "P2" to listOf("p2"),
        "P3" to listOf("p3"),
        "P4" to listOf("p4"),
        "Purple pad" to listOf("purple", "purple pad"),
        "Yellow pad" to listOf("yellow", "yellow pad"),
        "Green pad" to listOf("green", "green pad"),
        "Red pad" to listOf("red", "red pad"),
    )
    private val WORD: Map<String, String> = SPOTS.flatMap { (label, words) -> words.map { it to label } }.toMap()

    /** Mod tags and fun prefixes in front of the body: `[Skyblocker] `, `[Soul] `, `Boo! ` (recordings 2026-11-08 and 2026-11-12). */
    private val PREFIX = Regex("^(?:\\[[^\\]]{1,20}]\\s*|boo!\\s*)+", RegexOption.IGNORE_CASE)
    private val LEAP = Regex("^(?:leaped|leapt|yeeted|teleported) to (\\w{1,16})!?$", RegexOption.IGNORE_CASE)
    private val COORDS = Regex("^x:\\s*(-?\\d+(?:\\.\\d+)?),?\\s*y:\\s*(-?\\d+(?:\\.\\d+)?),?\\s*z:\\s*(-?\\d+(?:\\.\\d+)?)\\b", RegexOption.IGNORE_CASE)
    private val CALL = Regex("^(?:i'?m |i am |im )?(?:at |@ ?|in |inside |going |omw to |omw |on )?(?:the )?(.+?)$")
    private val TRAVELLING = Regex("^(?:i'?m |i am |im )?(?:going |omw to |omw )")
    private val TRAIL = Regex("[!.?\\s]+$")
    private val SPACES = Regex("\\s+")

    /** One party chat body (after `Party > [RANK] Name: `) → what it says about where the sender is, or null. */
    fun parse(body: String): Callout? {
        val s = body.trim().replace(PREFIX, "").trim()
        LEAP.matchEntire(s)?.let { return Callout.Leap(it.groupValues[1]) }
        COORDS.find(s)?.let { return Callout.Coords(it.groupValues[1].toDouble(), it.groupValues[2].toDouble(), it.groupValues[3].toDouble()) }
        val t = s.lowercase().replace(TRAIL, "").replace(SPACES, " ")
        val word = CALL.matchEntire(t)?.groupValues?.get(1) ?: return null
        return WORD[word]?.let { Callout.Spot(it, arrived = !TRAVELLING.containsMatchIn(t)) }
    }

    // ---------------------------------------------------------------- the F7/M7 boss room

    private class Box(val label: String, val x1: Int, val x2: Int, val y1: Int, val y2: Int, val z1: Int, val z2: Int)

    /** First matching box wins (the user's marks, `f7-boss-spots-2026-11-11`, and the M7 tracks; research §3.4). */
    private val BOXES = listOf(
        Box("Crystal W", 57, 70, 234, 242, 45, 57), Box("Crystal E", 76, 90, 234, 242, 45, 57), Box("P1", 34, 112, 200, 255, 10, 90),
        Box("Purple pad", 108, 120, 166, 174, 87, 100), Box("Yellow pad", 26, 38, 166, 174, 88, 100),
        Box("Purple", 94, 106, 163, 176, 58, 72), Box("Yellow", 36, 62, 163, 176, 58, 72), Box("Green", 38, 54, 163, 176, 33, 49), Box("Red", 92, 108, 163, 176, 33, 49),
        Box("Pre-dev", 8, 24, 126, 170, 66, 78), Box("Acacia", 81, 93, 163, 170, 83, 95), Box("P2", 20, 120, 160, 200, 28, 110),
        Box("i4", 58, 70, 120, 140, 30, 50), Box("SS", 104, 114, 117, 124, 88, 104),
        Box("ee2", 88, 102, 117, 126, 115, 126), Box("Lights", 54, 66, 128, 136, 134, 146),
        Box("ee3", -3, 10, 105, 120, 95, 112), Box("Arrows", -3, 8, 117, 124, 73, 85), Box("Core", 44, 66, 108, 122, 36, 72),
        Box("Mid", 44, 66, 58, 76, 62, 92), Box("P4", 38, 70, 55, 76, 8, 125), Box("P5", 0, 110, 0, 40, 20, 140),
    )

    /** A boss-room position → its spot label; Goldor's sections where no box matched (y 100..136); null outside. */
    fun spotAt(x: Double, y: Double, z: Double): String? {
        for (b in BOXES) if (x >= b.x1 && x <= b.x2 && y >= b.y1 && y <= b.y2 && z >= b.z1 && z <= b.z2) return b.label
        if (y in 100.0..136.0) {
            if (z >= 115 && x > 19) return "S2"
            if (x <= 19) return "S3"
            if (x >= 89 && z >= 60) return "S1"
            if (z <= 60) return "S4"
        }
        return null
    }

    // ---------------------------------------------------------------- lines after which everyone moves

    private val PHASE_LINES = setOf(
        "[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!",
        "[BOSS] Storm: Pathetic Maxor, just like expected.",
        "The Core entrance is opening!",
        "[BOSS] Necron: You went further than any human before, congratulations.",
    )
    private val SECTION_DONE = Regex("^\\w{1,16} (?:activated|completed) a (?:terminal|lever|device)! \\((\\d)/(\\d)\\)$")

    /**
     * A boss line after which every earlier report is stale (spec §6). Not `Who dares`, `You... again?` or Storm's end
     * line: `at ss!` and `at p5!` come just before them (M7 2026-11-13: `at ss!` 0.8–4.8 s before Storm's end line in
     * 6/6 runs, and the menu for the leap to the healer at SS opened 0.2 s before to 2.1 s after it).
     */
    fun isPhaseLine(stripped: String): Boolean {
        val s = stripped.trim()
        if (s in PHASE_LINES) return true
        val m = SECTION_DONE.matchEntire(s) ?: return false
        return m.groupValues[1] == m.groupValues[2]
    }
}
