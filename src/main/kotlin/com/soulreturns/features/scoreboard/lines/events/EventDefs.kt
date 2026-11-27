package com.soulreturns.features.scoreboard.lines.events

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.SourceLine

/**
 * The 28 events of the Events line and of the Events group, in the mock's order (the default priority).
 *
 * Provenance per event is in the comment above it: **real** = the pattern was written from a Hypixel
 * sidebar Soul recorded (`run/soul-dev/recordings`), **reference** = from SkyHanni's Custom Scoreboard
 * patterns (LGPL-2.1, attribution under About › Used Software) and its test lines, never seen live by
 * Soul. Hypixel's lines are matched by their text without colors, so a color change does not break one.
 */
object EventDefs {
    // ---- Dungeon run: real (Keys, Time Elapsed, Cleared, Solo, Auto-closing in, Starting in); reference (boss dragons, F3 guardians).
    private val DUNGEON_LINES =
        listOf(
            re("No Alive Dragons"),
            re("- [\\w ]+ Dragon [\\w,.]+.*"),
            re("Auto-closing in: [\\d:]+"),
            re("Starting in: [\\d:]+"),
            re("Keys: ■ [✓✗] ■ \\d+x"),
            re("Time Elapsed: [\\w ]+"),
            re("Cleared: [\\w,.]+% \\([\\w,.]+\\)"),
            re("Solo"),
            re("- (?:Healthy|Reinforced|Laser|Chaos) [\\w,.]*.*"),
        )

    val dungeons =
        EventDef(
            "evDungeons", "Dungeon run", "Keys, time and cleared %.",
            listOf("Time Elapsed: §a08m 12s", "Cleared: §c64% §8(312)"), "Dungeons",
            LineColors.HeadingLabels(0xFFFFFF, 0x55FF55),
        ) { c ->
            if (!c.inDungeon) return@EventDef null
            Collector(c.view).apply { DUNGEON_LINES.forEach(::scan) }.read()
        }

    // ---- Kuudra run: reference only (no capture exists; Phase/Supplies of the mock are not on Hypixel's sidebar as far as the references know).
    private val KUUDRA_LINES =
        listOf(
            re("Auto-closing in: [\\d:]+"),
            re("Starting in: [\\d:]+"),
            re("Time Elapsed: [\\w ]+"),
            re("Instance Shutdown In: [\\w ]+"),
            re("Wave: \\d+(?: - \\d+:\\d+)?"),
            re("Tokens: [\\w,]+"),
            re("Submerges In: [\\w ?]+"),
        )

    val kuudra =
        EventDef(
            "evKuudra", "Kuudra run", "Time, phase and supplies.",
            listOf("Time Elapsed: §a04m 31s", "Wave: §c2 §8- §a0:09"), "Kuudra",
            LineColors.HeadingLabels(0xFFFFFF, 0x55FF55),
        ) { c ->
            if (!c.inKuudra) return@EventDef null
            Collector(c.view).apply { KUUDRA_LINES.forEach(::scan) }.read()
        }

    // ---- Mining events: real (Nearby Players on the Forge sidebar); reference (the rest). Explanatory lines are claimed, not drawn.
    private val WIND_COMPASS = re("Wind Compass")
    private val WIND_ARROW = re("(?:[⋖⋗≈]\\s*)+")
    private val NEARBY = re("Nearby Players: (?:\\d+|N/A)(?: MAX)?")
    private val ZONE = re("Zone: .+")
    private val RAFFLE_USELESS = re("Find tickets on the|ground and bring them|to the raffle box")
    private val MINING_LINES =
        listOf(
            re("Tickets: \\d+ \\(\\d+(?:\\.\\d)?%\\)"),
            re("Pool: \\d+"),
            re("Remaining: (?:\\d+ Tasty Mithril|FULL)"),
            re("Your Tasty Mithril: \\d+.*"),
            re("Your kills: \\d+ ☠(?: \\(\\+\\d+\\))?"),
            re("Remaining: \\d+ goblins?"),
            re("Event Bonus: \\+\\d+.*"),
            re("Fossil Dust: [\\d.,]+.*"),
        )
    private val MINING_USELESS = re("Give Tasty Mithril to Don!|Kill goblins!|Not started.*")
    private val EVENT_HEAD = re("Event: .+")

    val mining =
        EventDef(
            "evMining", "Mining events", "Raffle, Goblin Raid, Mithril Gourmand and more.",
            listOf("Event: §6§lRAFFLE", "Tickets: §a8 §7(17.4%)", "Pool: §646"), "Mining islands",
            LineColors.HeadingLabels(0xFFAA00, 0x55FF55),
            standalonePrefix = true,
        ) { c ->
            val v = c.view
            val col = Collector(v)
            val compass = v.first(WIND_COMPASS)
            if (compass >= 0) {
                col.add(compass)
                v.after(compass, 1).firstOrNull { WIND_ARROW.matches(v.text[it]) }?.let(col::add)
            }
            col.scan(NEARBY)
            // `Event: RAFFLE` only counts in bold, the way Hypixel draws a zone event.
            val head = v.all(EVENT_HEAD).firstOrNull { LegacyText.runs(v.legacy(it)).any { r -> r.bold } }
            if (head != null) {
                col.add(head, if (c.prefix) v.legacy(head) else afterColon(v.legacy(head)))
                v.first(ZONE).takeIf { it >= 0 }?.let { z -> col.add(z, "in " + afterColon(v.legacy(z))) }
            }
            MINING_LINES.forEach(col::scan)
            col.scanClaim(RAFFLE_USELESS)
            col.scanClaim(MINING_USELESS)
            col.read()
        }

    // ---- Server closing: reference. Hypixel appends the server id after the time (`§8m77A`); cut there.
    private val SERVER_CLOSING = re("Server closing.*")

    val server =
        EventDef(
            "evServer", "Server closing", "Countdown when the server restarts.",
            listOf("§cServer closing: 00:59"), "Before a restart",
            LineColors.Text(0xFF5555),
        ) { c ->
            val v = c.view
            val i = v.first(SERVER_CLOSING)
            if (i < 0) return@EventDef null
            val legacy = v.legacy(i)
            val cut = legacy.indexOf("§8")
            Collector(v).apply { add(i, if (cut > 0) legacy.substring(0, cut).trimEnd() else legacy) }.read()
        }

    // ---- Election voting: reference.
    private val VOTING_LINES = listOf(re("Year \\d+ Votes"), re("\\|+ .+"), re("Waiting for|your vote\\.\\.\\."))

    val voting =
        EventDef(
            "evVoting", "Election voting", "Votes at the election booth.",
            listOf("§6Year 384 Votes", "§f||||||||||||||| §aFoxy"), "Election booth",
            LineColors.HeadingLabels(0xFFAA00, 0x55FF55),
        ) { c -> Collector(c.view).apply { VOTING_LINES.forEach(::scan) }.read() }

    // ---- Dojo: reference. Only with its `Challenge:` line (Time: and Points: are too common alone).
    private val DOJO_CHALLENGE = re("Challenge: .+")
    private val DOJO_LINES = listOf(re("Difficulty: .+"), re("Points: .+"), re("Time: .+"))

    val dojo =
        EventDef(
            "evDojo", "Dojo", "Challenge, difficulty, points and time.",
            listOf("Challenge: §6Force", "Points: §a10 §7(§a+10§7)"), "The Dojo",
            LineColors.HeadingLabels(0xFFFFFF, 0xFFAA00),
        ) { c ->
            val v = c.view
            if (!v.has(DOJO_CHALLENGE)) return@EventDef null
            Collector(v).apply {
                scan(DOJO_CHALLENGE)
                DOJO_LINES.forEach(::scan)
            }.read()
        }

    // ---- Dark Auction: reference.
    private val AUCTION_CURRENT = re("Current Item:")
    private val AUCTION_LINES = listOf(re("Starting in: [\\d:]+"), re("Time Left: [\\w:,.\\s]+"))

    val darkAuction =
        EventDef(
            "evDarkAuction", "Dark Auction", "Time left and the current item.",
            listOf("Time Left: §b11", "Current Item:", " §5Travel Scroll to Sirius"), "Dark Auction",
            LineColors.HeadingLabels(0xFFFFFF, 0x55FFFF),
        ) { c ->
            val v = c.view
            if (c.inDungeon || c.inKuudra) return@EventDef null
            val inAuction = v.has(AUCTION_CURRENT) || c.s.area?.contains("Dark Auction", ignoreCase = true) == true ||
                c.s.subLocation?.contains("Dark Auction", ignoreCase = true) == true
            if (!inAuction) return@EventDef null
            Collector(v).apply {
                AUCTION_LINES.forEach(::scan)
                block(AUCTION_CURRENT, 1)
            }.read()
        }

    // ---- Jacob's contest: reference (the heading and up to three lines under it).
    private val JACOB = re("Jacob's Contest")

    val jacob =
        EventDef(
            "evJacob", "Jacob's contest", "Your crop, time left and collected.",
            listOf("§eJacob's Contest", "§e○ §fCarrot §a18m17s", " Collected §e8,264"), "During a contest",
            LineColors.HeadingLabels(0xFFFF55, 0xFFFFFF),
        ) { c -> Collector(c.view).apply { block(JACOB, 3) }.read() }

    // ---- Jacob's medals: reference (can show on any island after calling Anita).
    private val MEDALS = re("(?:GOLD|SILVER|BRONZE) medals: [\\d.,]+")

    val medals =
        EventDef(
            "evMedals", "Jacob's medals", "Gold, silver and bronze medals.",
            listOf("§6§lGOLD §fmedals: §613"), "The Garden",
            LineColors.Text(0xFFAA00),
        ) { c -> Collector(c.view).apply { scan(MEDALS) }.read() }

    // ---- Trapper: reference.
    private val PELTS = re("Pelts: [\\d,]+.*")
    private val TRACKER_LOCATION = re("Tracker Mob Location:")

    val trapper =
        EventDef(
            "evTrapper", "Trapper", "Pelts and the tracked animal.",
            listOf("Pelts: §5711", "Tracker Mob Location:", "§bMushroom Gorge"), "Farming Islands",
            LineColors.HeadingLabels(0xFFFFFF, 0xAA00AA),
        ) { c ->
            Collector(c.view).apply {
                scan(PELTS)
                block(TRACKER_LOCATION, 1)
            }.read()
        }

    // ---- Garden: reference. `Locked` alone is only the garden's (any island could print it otherwise).
    private val GARDEN_LINES = listOf(re("Plot - .*"), re("(?:Barn )?Pasting: [\\d,.]+%?"), re("Cleanup: .+"))
    private val GARDEN_LOCKED = re("Locked.*")

    val garden =
        EventDef(
            "evGarden", "Garden", "Plot, pests, cleanup and pasting.",
            listOf(" §aPlot §7- §b3 §c✖ x8", " §fCleanup§7: §e0.3%"), "The Garden",
            LineColors.HeadingLabels(0x55FF55, 0xFFFF55),
        ) { c ->
            Collector(c.view).apply {
                GARDEN_LINES.forEach(::scan)
                if (c.activity("garden")) scan(GARDEN_LOCKED)
            }.read()
        }

    // ---- Flight duration: reference.
    private val FLIGHT = re("Flight Duration: .+")

    val flight =
        EventDef(
            "evFlight", "Flight duration", "Flight time left on your island.",
            listOf("Flight Duration: §a10m 0s"), "Private island",
            LineColors.LabelValue(0xFFFFFF, 0x55FF55),
        ) { c -> Collector(c.view).apply { scan(FLIGHT) }.read() }

    // ---- Jerry's Workshop: reference (lines ending in "Soon!" are Hypixel's placeholder and dropped).
    private val WINTER_LINES =
        listOf(
            re("Event Start: [\\d:]+"),
            re("Next Wave: (?:[\\d:]+|Soon!)"),
            re("Wave \\d+"),
            re("Magma Cubes Left: -?\\d+"),
            re("Your Total Damage: [\\d+,.]+.*"),
            re("Your Cube Damage: [\\d+,.]+"),
        )

    val winter =
        EventDef(
            "evWinter", "Jerry's Workshop", "Waves and magma cubes left.",
            listOf("§cWave 5", "Magma Cubes Left: §c3"), "Jerry's Workshop",
            LineColors.HeadingLabels(0xFF5555, 0xFF5555),
        ) { c ->
            val v = c.view
            Collector(v).apply {
                for (r in WINTER_LINES) for (i in v.all(r)) if (v.text[i].endsWith("Soon!")) claim(i) else add(i)
            }.read()
        }

    // ---- New Year: reference.
    private val NEW_YEAR = re("New Year Event! [\\d:]+")

    val newYear =
        EventDef(
            "evNewYear", "New Year", "New Year celebration timer.",
            listOf("§dNew Year Event!§f 24:25"), "New Year",
            LineColors.Text(0xFF55FF),
        ) { c -> Collector(c.view).apply { scan(NEW_YEAR) }.read() }

    // ---- Spooky Festival: reference. The candy line comes from the tab list footer; without it only the timer shows.
    private val SPOOKY = re("Spooky Festival [\\d:]+")

    val spooky =
        EventDef(
            "evSpooky", "Spooky Festival", "Timer and your candy.",
            listOf("§6Spooky Festival§f 50:54", "§7Your Candy:", "§a1 Green§7, §50 Purple"), "Spooky Festival",
            LineColors.HeadingLabels(0xFFAA00, 0x55FF55),
        ) { c ->
            val v = c.view
            val i = v.first(SPOOKY)
            if (i < 0) return@EventDef null
            val candy = c.s.tabFooter.firstOrNull { it.text.trim().startsWith("Your Candy:") }
            Collector(v).apply { add(i) }.read(if (candy == null) emptyList() else listOf("§7Your Candy:", afterColon(candy.legacy)))
        }

    // ---- Broodmother: reference (sidebar line; the tab list's Broodmother widget when the sidebar has none).
    private val BROOD = re("Broodmother: (?:Slain|Dormant|Soon|Awakening|Imminent|Alive!)")

    val brood =
        EventDef(
            "evBrood", "Broodmother", "Broodmother state.",
            listOf("§4Broodmother§7: §eDormant"), "Spider's Den",
            LineColors.Text(0xAA0000),
        ) { c ->
            val v = c.view
            val i = v.first(BROOD)
            if (i >= 0) return@EventDef Collector(v).apply { add(i) }.read()
            val widget = c.s.widget("Broodmother") ?: return@EventDef null
            EventRead(emptyList(), widget.lines.map { it.legacy.trim() })
        }

    // ---- Galatea & foraging: reference.
    private val WHISPERS = re("Whispers: [\\w,.]+.*")
    private val HOTF = re("HOTF: [\\w,.]+.*")
    private val AGATHA = re("Agatha's Contest .+")
    private val MIRIA = re("Miria's Contest .+")

    val galatea =
        EventDef(
            "evGalatea", "Galatea & foraging", "Whispers and Agatha's contest.",
            listOf("Whispers: §317.5k§b (+50)", "§eAgatha's Contest §a5m28s"), "Galatea",
            LineColors.HeadingLabels(0xFFFFFF, 0x00AAAA),
        ) { c ->
            Collector(c.view).apply {
                scan(WHISPERS)
                scan(HOTF)
                block(AGATHA, 2)
                block(MIRIA, 2)
            }.read()
        }

    // ---- Safari: reference.
    private val SAFARI = re("Captured Mobs: \\d+")

    val safari =
        EventDef(
            "evSafari", "Safari", "Captured mobs.",
            listOf("Captured Mobs: §e14"), "Safari",
            LineColors.LabelValue(0xFFFFFF, 0xFFFF55),
        ) { c -> Collector(c.view).apply { scan(SAFARI) }.read() }

    // ---- Dragon fight: reference.
    private val DAMAGE_LINES = listOf(re("(?:Protector|Dragon) HP: [\\d,.]+ ?❤?.*"), re("Your Damage: [\\d,.]+"))

    val damage =
        EventDef(
            "evDamage", "Dragon fight", "Dragon HP and your damage.",
            listOf("Dragon HP: §a6,180,925 §c❤", "Your Damage: §c375,298.5"), "Dragon's Nest",
            LineColors.HeadingLabels(0xFFFFFF, 0xFF5555),
        ) { c -> Collector(c.view).apply { DAMAGE_LINES.forEach(::scan) }.read() }

    // ---- Magma Boss: reference.
    private val MAGMA_LINES =
        listOf(
            re("Boss: \\d+%"),
            re("Damage Soaked:"),
            re("Kill the Magmas:"),
            re("▎+.*"),
            re("The boss is (?:re)?forming!"),
            re("Boss Health:"),
            re("[\\w,.]+/10M❤?"),
        )

    val magma =
        EventDef(
            "evMagma", "Magma Boss", "Boss HP and damage soaked.",
            listOf("§7Boss: §c45%", "§a▎▎▎▎▎§7▎▎▎▎▎▎▎▎▎▎"), "Magma Chamber",
            LineColors.HeadingLabels(0xAAAAAA, 0xFF5555),
        ) { c -> Collector(c.view).apply { MAGMA_LINES.forEach(::scan) }.read() }

    // ---- Carnival: reference. Only under its `Carnival h:mm:ss` heading.
    private val CARNIVAL = re("Carnival \\d+(?::\\d+)*")
    private val CARNIVAL_LINES =
        listOf(
            re("Catch a Fish|Fruit Digging|Zombie Shootout"),
            re("Carnival Tokens: [\\d,]+"),
            re("Fruits: \\d+/\\d+"),
            re("Score: \\d+.*"),
            re("Catch Streak: \\d+"),
            re("Accuracy: \\d+(?:\\.\\d+)?%"),
            re("Kills: \\d+"),
            re("Time Left: [\\w:,.\\s]+"),
        )

    val carnival =
        EventDef(
            "evCarnival", "Carnival", "Timer, tokens and game stats.",
            listOf("§eCarnival§f 85:33:57", "§fCarnival Tokens: §e129"), "Hub, Carnival",
            LineColors.HeadingLabels(0xFFFF55, 0xFFFF55),
        ) { c ->
            val v = c.view
            if (!v.has(CARNIVAL)) return@EventDef null
            Collector(v).apply {
                scan(CARNIVAL)
                CARNIVAL_LINES.forEach(::scan)
            }.read()
        }

    // ---- Rift: reference (effigy hearts are left out: their glyph row is not known).
    private val RIFT_DIMENSION = re("Rift Dimension")
    private val RIFT_LINES =
        listOf(
            re("Hot Dog Contest"),
            re("Time Left: [\\w:,.\\s]+"),
            re("Eaten: \\d+/\\d+"),
            re("Time spent sitting|with Ävaeìkx: .*"),
            re("Hay Eaten: [\\d,.]+/[\\d,.]+"),
            re("Clues: \\d+/\\d+"),
            re("First Up|Find and talk with Barry"),
            re("Protestors handled: \\d+/\\d+"),
            re("TIME SLICED!"),
            re("Big damage in: [\\w\\s]+"),
        )

    val rift =
        EventDef(
            "evRift", "Rift", "Rift dimension lines.",
            listOf(" §fRift Dimension", "Hay Eaten: §e2,477/3,000"), "The Rift",
            LineColors.HeadingLabels(0xFFFFFF, 0xFFFF55),
        ) { c ->
            val v = c.view
            if (!c.activity("rift") && !v.has(RIFT_DIMENSION)) return@EventDef null
            Collector(v).apply {
                scan(RIFT_DIMENSION)
                RIFT_LINES.forEach(::scan)
            }.read()
        }

    // ---- Dragon essence: reference.
    private val ESSENCE = re("[\\w ]*Essence: -?[\\d,.]+")

    val essence =
        EventDef(
            "evEssence", "Dragon essence", "Essence from the dragon fight.",
            listOf("Dragon Essence: §d1,285"), "Dragon's Nest",
            LineColors.LabelValue(0xFFFFFF, 0xFF55FF),
        ) { c -> Collector(c.view).apply { scan(ESSENCE) }.read() }

    // ---- Queue: reference. `Tier:` only with `Queued:` above it.
    private val QUEUED = re("Queued: .*")
    private val QUEUE_LINES = listOf(re("Position: #\\d+ Since: .*"), re("Waiting on party leader!"))
    private val QUEUE_TIER = re("Tier: .+")

    val queue =
        EventDef(
            "evQueue", "Queue", "Queue position for dungeons or mineshafts.",
            listOf("Queued: §aThe Catacombs", "Position: §b#2 §fSince: §a00:01"), "While queued",
            LineColors.HeadingLabels(0xFFFFFF, 0x55FF55),
        ) { c ->
            val v = c.view
            if (!v.has(QUEUED)) return@EventDef null
            Collector(v).apply {
                scan(QUEUED)
                scan(QUEUE_TIER)
                QUEUE_LINES.forEach(::scan)
            }.read()
        }

    // ---- Anniversary: reference.
    private val ANNIVERSARY = re("(?:\\d+(?:st|nd|rd|th) Anniversary|Century Raffle) [\\d:]+")

    val anniversary =
        EventDef(
            "evAnniversary", "Anniversary", "Anniversary and Century Raffle timers.",
            listOf("§d5th Anniversary§f 167:59:54"), "Anniversary",
            LineColors.Text(0xFF55FF),
        ) { c -> Collector(c.view).apply { scan(ANNIVERSARY) }.read() }

    // ---- Tab list events: the tab list's `Event:` widget (`Event: Hoppity's Hunt`, ` Ends In: 26h`), reference.
    // Events the sidebar already shows (and the ones that run on every island) are left to their own lines.
    private val TAB_BLOCKED = listOf("Spooky Festival", "Carnival", "Anniversary", "New Year Celebration", "Traveling Zoo")
    private val ENDS = re("Ends In: (.*)")
    private val STARTS = re("Starts In: (.*)")

    private fun tabEvent(c: EventCtx, timeLine: Regex, word: String, blocked: Boolean): EventRead? {
        val widget = c.s.widget("Event") ?: return null
        val head = widget.lines.firstOrNull() ?: return null
        val name = afterColon(head.legacy)
        if (name.isBlank()) return null
        val plain = LegacyText.strip(name)
        if (blocked && TAB_BLOCKED.any { plain.contains(it, ignoreCase = true) }) return null
        val time = widget.lines.drop(1).firstNotNullOfOrNull { l: SourceLine -> timeLine.matchEntire(l.text.trim())?.groupValues?.get(1) } ?: return null
        return EventRead(emptyList(), listOf((if (c.prefix) "§fEvent: " else "") + name, " §f$word: §e$time"))
    }

    val activeTab =
        EventDef(
            "evActiveTab", "Active events (tab list)", "Events running now, from the tab list.",
            listOf("§dHoppity's Hunt", " §fEnds in: §e26h"), null,
            LineColors.HeadingLabels(0xFF55FF, 0xFFFF55),
            tab = true,
        ) { c -> tabEvent(c, ENDS, "Ends in", blocked = true) }

    val soonTab =
        EventDef(
            "evSoonTab", "Starting soon (tab list)", "Events starting soon, from the tab list.",
            listOf("§6Mining Fiesta", " §fStarts in: §e52min"), null,
            LineColors.HeadingLabels(0xFFAA00, 0xFFFF55),
            tab = true,
        ) { c -> tabEvent(c, STARTS, "Starts in", blocked = false) }

    // ---- Redstone: reference.
    private val REDSTONE = re("⚡ Redstone: \\d+%")

    val redstone =
        EventDef(
            "evRedstone", "Redstone", "Redstone usage on your island.",
            listOf(" §e§l⚡ §cRedstone: §e§b7%"), "Private island",
            LineColors.Text(0xFF5555),
        ) { c -> Collector(c.view).apply { scan(REDSTONE) }.read() }

    /** Every event, in the default priority order (the mock's). */
    val all: List<EventDef> =
        listOf(
            dungeons, kuudra, mining, server, voting, dojo, darkAuction, jacob, medals, trapper, garden, flight, winter, newYear,
            spooky, brood, galatea, safari, damage, magma, carnival, rift, essence, queue, anniversary, activeTab, soonTab, redstone,
        )

    private val byId: Map<String, EventDef> = all.associateBy { it.id }

    fun of(id: String): EventDef? = byId[id]
}
