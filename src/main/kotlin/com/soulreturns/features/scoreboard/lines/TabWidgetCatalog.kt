package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LegacyText

/**
 * The tab list widgets of the mock's "From the tab list" group as data: one row per widget, one
 * [TabWidgetLine] each. [headers] are the widget header texts Hypixel prints (the text before the
 * first colon, matched case-insensitively and never validated). A widget Hypixel renames simply shows
 * nothing; add the new header to the row.
 *
 * [verified]: the header appears in Soul's own captures (tab-list packet logs, `docs/research`, the
 * Dwarven Mines fixture). The rest come from SkyHanni's `TabWidget` patterns (LGPL-2.1, patterns only;
 * credited under About -> Used Software as "SkyHanni Custom Scoreboard patterns") and the mock.
 */
object TabWidgetCatalog {
    private class Row(
        val id: String,
        val name: String,
        val headers: List<String>,
        val description: String,
        val example: String,
        val where: String? = null,
        val headerColor: String = "§e",
        val verified: Boolean = false,
        val scope: TabWidgetLine.Scope = TabWidgetLine.Scope.ANYWHERE,
    )

    private fun row(
        id: String, name: String, headers: String, description: String, example: String, where: String? = null,
        hc: String = "§e", verified: Boolean = false, scope: TabWidgetLine.Scope = TabWidgetLine.Scope.ANYWHERE,
    ) = Row(id, name, headers.split('|'), description, example, where, hc, verified, scope)

    private val rows: List<Row> =
        listOf(
            row("tArea", "Area", "Area", "Area and server.", "Area: §bDwarven Mines|Server: §8mini26CH", hc = "§b", verified = true),
            row("tProfile", "Profile", "Profile", "Profile, SkyBlock level and bank.", "Profile: §aStrawberry ♲|SB Level: §b[171] §797/100 XP", verified = true),
            row("tPet", "Pet", "Pet", "Active pet with XP.", "Pet:| §6[Lv100] Golden Dragon", verified = true),
            row("tDaily", "Daily quests", "Daily Quests", "Today's quests.", "Daily Quests:| §fKill 50 Zombies §7(§e12/50§7)", verified = true),
            row("tSkills", "Skills", "Skills", "The skill you last trained.", "Skills: §aHunting 33: §e55.5%", verified = true),
            row("tElection", "Election", "Election", "Election time and leaders.", "Election: §e2d| §bDiana §f|||||||||| §7(26%)", verified = true),
            row("tBestiary", "Bestiary", "Bestiary", "Bestiary progress.", "Bestiary:| §aGhost §7(§e2,410§7/§a3,000§7)", verified = true),
            row("tCollection", "Collection", "Collection", "Collection progress.", "Collection:| §aMithril §7(§e2.1M§7/§a3M§7)"),
            row("tStats", "Stats", "Stats", "Combat or mining stats.", "Strength: §c❁5,734|Crit Damage: §9☠6,490", verified = true),
            row("tEvent", "Event", "Event", "The tab list's event box.", "Event: §dCarnival| Ends In: §e24h", verified = true, scope = TabWidgetLine.Scope.NOT_MINING),
            row("tEventTrackers", "Event trackers", "Event Trackers", "Progress in the running event.", "§dHoppity's Hunt| Eggs found: §a3§7/§a6"),
            row("tEffects", "Active effects", "Active Effects", "Effects count and buffs.", "Active Effects: §a8| §dCookie Buff §f2d 22h"),
            row("tTimers", "Timers", "Timers", "Running timers.", "Timers:| §cGod Potion §f12h"),
            row("tFireSale", "Fire sales", "Fire Sales", "Running fire sales.", "Fire Sales: §e(1)"),
            row("tMinions", "Minions", "Minions", "Minions placed.", "Minions: §a31§7/§a32", "Private island"),
            row("tPity", "Pity", "Pity", "Drop pity progress.", "Pity:| §dBurning Kuudra Core §7(§e62%§7)", verified = true),
            row("tSlayer", "Slayer", "Slayer", "Slayer widget.", "Slayer:| §5Voidgloom Seraph §7Lv7"),
            row("tForges", "Forges", "Forges", "What is in your forge slots.", "Forges:| §a1) §fRefined Mithril §e2h 14m", "Dwarven Mines", hc = "§f", verified = true),
            row("tComms", "Commissions", "Commissions", "Commission progress.", "Commissions:| §fGoblin Slayer: §a68%", "Mining islands", hc = "§9", verified = true),
            row("tPowders", "Powders", "Powders", "Powder amounts.", "Mithril: §266,017|Gemstone: §d13,076", "Mining islands", hc = "§f", verified = true),
            row("tCrystals", "Crystals", "Crystals", "Crystals found and placed.", "Crystals:| §6Amber: §a✔ Placed", "Crystal Hollows", verified = true),
            row("tMiningEvent", "Mining event", "Event", "Next mining event.", "Event: §6Raffle| Ends In: §e11m", "Mining islands", scope = TabWidgetLine.Scope.MINING),
            row("tCorpses", "Frozen corpses", "Frozen Corpses", "Mineshaft corpses looted.", "Frozen Corpses:| §9Lapis: §aLOOTED", "Glacite Mineshafts", hc = "§9"),
            row("tPickaxe", "Pickaxe ability", "Pickaxe Ability", "Ability cooldown.", "Pickaxe Ability:| §aPickobulus: §aAvailable", "Mining islands", verified = true),
            row("tWorms", "Worms", "Worms", "Worms around you.", "Worms: §d4", "Crystal Hollows"),
            row("tAgatha", "Agatha's contest", "Agatha's Contest", "Your contest milestone.", "Agatha's Contest:| §eYour Milestone: §6☠❸", "Galatea"),
            row("tStarborn", "Starborn Temple", "Starborn Temple", "Temple state.", "Starborn Temple: §aOpen", "Galatea"),
            row("tForestW", "Forest whispers", "Forest Whispers", "Forest whispers.", "Forest Whispers: §319.6k", "Galatea"),
            row("tDesertW", "Desert whispers", "Desert Whispers", "Desert whispers.", "Desert Whispers: §32.1k", "Galatea"),
            row("tMoonglade", "Moonglade beacon", "Moonglade Beacon", "Beacon stacks.", "Moonglade Beacon: §b2 Stacks", "Galatea"),
            row("tTorrhus", "Torrhus beacon", "Torrhus Beacon", "Beacon stacks.", "Torrhus Beacon: §b1 Stack", "Galatea"),
            row("tShardTraps", "Shard traps", "Full Traps", "Full traps.", "Full Traps: §a#1, #2, #3", "Galatea"),
            row("tSalts", "Salts", "Salts", "Active salts.", "Salts:| §bCuboa Salt §f12m", "Galatea"),
            row("tComposter", "Composter", "Composter", "Organic matter, fuel, compost.", "Organic Matter: §e2.4k|Fuel: §a101.5k", "The Garden"),
            row("tJacob", "Jacob's contest", "Jacob's Contest", "Next or running contest.", "Jacob's Contest:| §e○ §fCarrot §a18m17s", "The Garden"),
            row("tPests", "Pests", "Pests", "Alive pests and their plots.", "Pests:| §cAlive: §e3", "The Garden"),
            row("tPestTraps", "Pest traps", "Pest Traps", "Traps placed and full.", "Pest Traps: §a3§7/§a3", "The Garden"),
            row("tVisitors", "Visitors", "Visitors", "Visitors waiting and the next one.", "Visitors: §e(3)|Next Visitor: §b4m 29s", "The Garden"),
            row("tTrapper", "Trapper", "Trapper", "Trapper pelts.", "Trapper:| §5Pelts: §d711", "Farming Islands"),
            row("tCrops", "Crop milestones", "Crop Milestones", "Crop milestone progress.", "Crop Milestones:| §aWheat 46: §e62%", "The Garden"),
            row("tRep", "Reputation", "Barbarian Reputation|Mage Reputation", "Crimson Isle faction reputation.", "Mage Reputation: §510,402", "Crimson Isle"),
            row("tTrophy", "Trophy fish", "Trophy Fish", "Trophy fish progress.", "Trophy Fish:| §6Golden §7(4/18)", "Crimson Isle"),
            row("tFaction", "Faction quests", "Faction Quests", "Your faction quests.", "Faction Quests:| §fKill 30 Blazes §7(§e12§7/§a30§7)", "Crimson Isle"),
            row("tDragon", "Dragon", "Dragon|Eyes placed", "Dragon and eyes placed.", "Dragon: §cSuperior Dragon|Eyes placed: §e8/8", "Dragon's Nest", hc = "§b", verified = true),
            row("tFrogs", "Trophy frogs", "Trophy Frogs", "Trophy frogs caught.", "Trophy Frogs: §a12", "Backwater Bayou", hc = "§2", verified = true),
            row("tDowned", "Downed", "Downed", "Downed teammates and revive time.", "Downed: §cNONE|Revive: §eN/A", "Dungeons", hc = "§c", verified = true),
            row("tDeaths", "Team deaths", "Team Deaths", "Deaths this run.", "Team Deaths: §c0", "Dungeons", hc = "§c", verified = true),
            row("tDiscoveries", "Discoveries", "Discoveries", "Secrets and crypts found.", "Discoveries: §a14", "Dungeons", hc = "§a", verified = true),
            row("tPuzzles", "Puzzles", "Puzzles", "Puzzles and whether they are done.", "Puzzles: §e(4)| §fQuiz: §a[✔]", "Dungeons", verified = true),
            row("tRng", "RNG meter", "RNG Meter", "Your RNG meter.", "RNG Meter:| §dNecron's Handle §7(§e42%§7)"),
            row("tParty", "Party", "Party", "Party size.", "Party: §e(5/5)"),
            row("tDungeons", "Dungeons", "Dungeons", "Dungeon stats and unclaimed chests.", "Dungeons:| §fUnclaimed chests: §e12"),
            row("tEssence", "Essence", "Essence", "Essence amounts.", "Essence:| §dWither: §e2,493"),
            row("tGoodToKnow", "Good to know", "Good to know", "Hints like the commission bonus.", "Good to Know:| §fCommission Bonus: §aAvailable"),
            row("tShen", "Shen's auction", "Shen's Auction|Shen", "Shen's auction timer.", "Shen's Auction: §e3h"),
            row("tAd", "Store line", "Advertisement", "Hypixel's store advertisement.", "§aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET"),
        )

    /** Whether the row's header text is in Soul's own captures (see the class comment). */
    val verified: Set<String> = rows.filter { it.verified }.map { it.id }.toSet()

    val all: List<TabWidgetLine> by lazy {
        rows.map { r ->
            TabWidgetLine(
                r.id, r.headers.first(), r.name, r.description, example(r), r.where,
                alsoWidgets = r.headers.drop(1), scope = r.scope, verified = r.verified,
            )
        }
    }

    /**
     * The mock's one-line-per-segment example as Hypixel draws a widget: a bold colored header, body
     * lines indented one space. The first segment is the header line when its label is one of the
     * widget's headers; otherwise the header is added in front.
     */
    private fun example(r: Row): List<String> {
        val segments = r.example.split('|')
        val out = ArrayList<String>()
        fun label(s: String) = LegacyText.strip(s).substringBefore(':', "").trim()
        fun isHeader(s: String) = ':' in LegacyText.strip(s) && r.headers.any { it.equals(label(s), ignoreCase = true) }
        fun header(s: String): String {
            val colon = s.indexOf(':')
            val rest = s.substring(colon + 1)
            return r.headerColor + "§l" + s.substring(0, colon + 1) + (if (rest.isBlank()) "" else "§r$rest")
        }
        if (!isHeader(segments[0])) out += r.headerColor + "§l" + r.headers.first() + ":"
        for (s in segments) out += if (isHeader(s)) header(s) else if (s.startsWith(" ")) s else " $s"
        return out
    }
}
