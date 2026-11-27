package com.soulreturns.features.scoreboard.importer

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.config.ScoreboardSetup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What the player is shown about an import: what came across and what did not. */
data class ImportResult(val imported: List<String>, val skipped: List<String>)

/** A converted setup and look, plus the settings that live outside the scoreboard section. */
class ConvertedSetup(val setup: ScoreboardSetup, val look: ScoreboardLook, val keepVanilla: Boolean, val result: ImportResult)

/**
 * Custom Scoreboard (`config/customscoreboard/config.jsonc`, MIT) to Soul's scoreboard setup. Pure: text in,
 * [ConvertedSetup] out; null when the text is not a Custom Scoreboard config. Element ids map to the
 * catalogue ids of `mocks/scoreboard/mock.js`; an element without a counterpart is skipped and named.
 */
object CustomScoreboardConverter {
    private val json = Json { isLenient = true; allowTrailingComma = true }

    private val ELEMENTS =
        mapOf(
            "TITLE" to "header", "LOBBY" to "server", "DATE" to "date", "TIME" to "time", "IRL_TIME" to "irl", "IRL" to "irl",
            "PLAYER_COUNT" to "players", "AREA" to "area", "PROFILE" to "profile", "VISITING" to "visiting", "SEPARATOR" to "sep",
            "ISLAND" to "island", "PURSE" to "purse", "MOTES" to "motes", "SOWDUST" to "sowdust", "BANK" to "bank", "GEMS" to "gems",
            "HEAT" to "heat", "BITS" to "bits", "COPPER" to "copper", "KERNELS" to "kernels", "NORTH_STARS" to "northStars",
            "SOULFLOW" to "soulflow", "COLD" to "cold", "EVENTS" to "events", "MAXWELL_POWER" to "power", "MAXWELL_TUNINGS" to "tunings",
            "MAXWELL_TUNING" to "tunings", "OBJECTIVE" to "objective", "SLAYER" to "slayer", "SLAYER_STATS" to "slayerStats",
            "POWDER" to "powder", "PARTY" to "party", "FOOTER" to "footer", "EXTRA" to "extra", "SKYBLOCK_LEVEL" to "sblevel",
            "SB_LEVEL" to "sblevel", "QUIVER" to "quiver", "COOKIE_BUFF" to "cookie", "COOKIE" to "cookie", "GOD_POTION" to "godpot",
            "PET" to "pet", "MAYOR" to "mayor", "CHUNKED_STATS" to "chunk",
        )

    private val EVENTS =
        mapOf(
            "VOTING" to "evVoting", "SERVER_RESTART" to "evServer", "DUNGEONS" to "evDungeons", "KUUDRA" to "evKuudra", "DOJO" to "evDojo",
            "DARK_AUCTION" to "evDarkAuction", "JACOBS_CONTEST" to "evJacob", "JACOB_MEDALS" to "evMedals", "FORAGING" to "evGalatea",
            "TRAPPER" to "evTrapper", "GARDEN" to "evGarden", "FLIGHT_DURATION" to "evFlight", "NEW_YEAR" to "evNewYear",
            "WINTER" to "evWinter", "SPOOKY" to "evSpooky", "BROODMOTHER" to "evBrood", "MINING" to "evMining", "DAMAGE" to "evDamage",
            "MAGMA_BOSS" to "evMagma", "CARNIVAL" to "evCarnival", "RIFT" to "evRift", "ESSENCE" to "evEssence",
            "ACTIVE_TABLIST" to "evActiveTab", "STARTING_SOON_TABLIST" to "evSoonTab", "REDSTONE" to "evRedstone",
            "QUEUE" to "evQueue", "ANNIVERSARY" to "evAnniversary",
        )

    private val TABLIST =
        mapOf(
            "AREA" to "tArea", "PROFILE" to "tProfile", "PET" to "tPet", "DAILY_QUESTS" to "tDaily", "SKILLS" to "tSkills",
            "ELECTION" to "tElection", "BESTIARY" to "tBestiary", "COLLECTION" to "tCollection", "STATS" to "tStats", "EVENT" to "tEvent",
            "EVENT_TRACKERS" to "tEventTrackers", "ACTIVE_EFFECTS" to "tEffects", "TIMERS" to "tTimers", "FIRE_SALE" to "tFireSale",
            "MINIONS" to "tMinions", "PITY" to "tPity", "SLAYER" to "tSlayer", "FORGES" to "tForges", "COMMISSIONS" to "tComms",
            "POWDERS" to "tPowders", "CRYSTALS" to "tCrystals", "MINING_EVENT" to "tMiningEvent", "FROZEN_CORPSES" to "tCorpses",
            "PICKAXE_ABILITY" to "tPickaxe", "WORMS" to "tWorms", "AGATHA_CONTEST" to "tAgatha", "STARBORN_TEMPLE" to "tStarborn",
            "FOREST_WHISPERS" to "tForestW", "DESERT_WHISPERS" to "tDesertW", "MOONGLADE_BEACON" to "tMoonglade",
            "TORRHUS_BEACON" to "tTorrhus", "SHARD_TRAPS" to "tShardTraps", "SALTS" to "tSalts", "COMPOSTER" to "tComposter",
            "JACOBS_CONTEST" to "tJacob", "PESTS" to "tPests", "PEST_TRAPS" to "tPestTraps", "VISITORS" to "tVisitors",
            "TRAPPER" to "tTrapper", "CROP_MILESTONES" to "tCrops", "REPUTATION" to "tRep", "TROPHY_FISH" to "tTrophy",
            "FACTION_QUESTS" to "tFaction", "DRAGON" to "tDragon", "TROPHY_FROGS" to "tFrogs", "DOWNED" to "tDowned",
            "TEAM_DEATHS" to "tDeaths", "DISCOVERIES" to "tDiscoveries", "PUZZLES" to "tPuzzles", "RNG_METER" to "tRng",
            "PARTY" to "tParty", "DUNGEONS" to "tDungeons", "ESSENCE" to "tEssence", "GOOD_TO_KNOW" to "tGoodToKnow",
            "SHEN" to "tShen", "ADVERTISEMENT" to "tAd",
        )

    /** Custom prefix keys (`customization.<key>`) to the line that takes them as its label. */
    private val PREFIXES =
        mapOf(
            "pursePrefix" to "purse", "bankPrefix" to "bank", "motesPrefix" to "motes", "bitsPrefix" to "bits", "copperPrefix" to "copper",
            "sowdustPrefix" to "sowdust", "kernelsPrefix" to "kernels", "heatPrefix" to "heat", "coldPrefix" to "cold",
            "northStarsPrefix" to "northStars", "soulflowPrefix" to "soulflow", "gemsPrefix" to "gems", "cookieBuffPrefix" to "cookie",
            "petPrefix" to "pet", "godPotionPrefix" to "godpot", "maxwellPowerPrefix" to "power", "maxwellTuningPrefix" to "tunings",
            "playerCountPrefix" to "players",
        )

    private val MONEY_GAIN = setOf("purse", "bits")

    /** Removes `//` line comments outside strings (the file is JSONC). */
    fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inString -> {
                    out.append(c)
                    if (c == '\\' && i + 1 < text.length) out.append(text[++i]) else if (c == '"') inString = false
                }
                c == '"' -> { inString = true; out.append(c) }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> while (i < text.length && text[i] != '\n') i++
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    fun convert(text: String, currentLook: ScoreboardLook): ConvertedSetup? {
        val root = try { json.parseToJsonElement(stripComments(text)) as? JsonObject } catch (_: Exception) { null } ?: return null
        val custom = root["customization"] as? JsonObject ?: return null
        val appearance = (custom["appearance"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: return null
        val imported = ArrayList<String>()
        val skipped = ArrayList<String>()
        val mod = root["line_modification"] as? JsonObject
        val bg = root["Background"] as? JsonObject

        // Options the elements carry.
        val optionsFor = HashMap<String, MutableMap<String, JsonElement>>()
        fun opt(id: String, key: String, v: JsonElement) { optionsFor.getOrPut(id) { LinkedHashMap() }[key] = v }

        val events = (custom["events"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val eventIds = events.mapNotNull { EVENTS[it] }
        events.filter { it !in EVENTS }.forEach { skipped += "Event ${pretty(it)}" }
        if (custom["events"] != null) opt("events", "events", JsonArray(eventIds.map { JsonPrimitive(it) }))

        val showGain = mod.bool("showCurrencyGain")
        if (showGain != null) for (id in MONEY_GAIN) opt(id, "gain", JsonPrimitive(showGain))
        if (custom.bool("enableCustomPrefixes") == true) {
            for ((key, id) in PREFIXES) {
                val p = (custom[key] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull } ?: continue
                opt(id, "label", JsonPrimitive(MiniMessage.toLegacy(p)))
            }
        }
        if (custom.int("statsPerLine") != null) opt("chunk", "perLine", JsonPrimitive(custom.int("statsPerLine")))
        val chunked = (custom["chunkedStats"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.mapNotNull { ELEMENTS[it] }
        if (chunked != null) opt("chunk", "stats", JsonArray(chunked.map { JsonPrimitive(it) }))

        // Title and footer.
        val align = { key: String -> alignment(custom.str(key)) }
        val header = HashMap<String, JsonElement>()
        header["show"] = JsonPrimitive(if (custom.bool("useHypixelTitle") == true) "hypixel" else if (custom.bool("titleUseCustomText") == false) "hypixel" else "soul")
        (custom["titleText"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { header["text"] = JsonPrimitive(MiniMessage.toLegacy(it)) }
        align("titleAlignment")?.let { header["align"] = JsonPrimitive(it) }
        if (custom.bool("useCustomTitleOutsideSkyBlock") == true) header["outside"] = JsonPrimitive(true)
        val footer = HashMap<String, JsonElement>()
        footer["show"] = JsonPrimitive(if (custom.bool("footerUseCustomText") == true) "custom" else "url")
        (custom["footerText"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.let { footer["text"] = JsonPrimitive(MiniMessage.toLegacy(it)) }
        align("footerAlignment")?.let { footer["align"] = JsonPrimitive(it) }

        // Lines.
        val entries = ArrayList<ScoreboardEntry>()
        var sawHeader = false
        var sawFooter = false
        val seen = HashSet<String>()
        for (raw in appearance) {
            val id = ELEMENTS[raw]
            if (id == null) {
                skipped += "Line ${pretty(raw)}"
                continue
            }
            if (id != "sep" && !seen.add(id)) continue
            val options = when (id) {
                "header" -> { sawHeader = true; header }
                "footer" -> { sawFooter = true; footer }
                else -> optionsFor[id] ?: emptyMap()
            }
            entries += ScoreboardEntry(id, options = JsonObject(options))
        }
        if (!sawHeader) entries.add(0, ScoreboardEntry("header", hidden = true, options = JsonObject(header)))
        if (!sawFooter) entries += ScoreboardEntry("footer", hidden = true, options = JsonObject(footer))
        val tab = (custom["tablistLines"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        if (tab.isNotEmpty()) {
            val at = entries.indexOfFirst { it.type == "extra" }.takeIf { it >= 0 } ?: (entries.size - 1).coerceAtLeast(1)
            val add = tab.mapNotNull { t -> TABLIST[t]?.let { ScoreboardEntry(it) } ?: run { skipped += "Tab list line ${pretty(t)}"; null } }
            entries.addAll(at, add)
        }
        val lineCount = entries.count { it.type != "sep" && it.type != "header" && it.type != "footer" && !it.hidden }
        imported += "$lineCount lines in your order"
        if (eventIds.isNotEmpty()) imported += "${eventIds.size} events, in order"
        imported += "Title and footer"

        // Look.
        var look = currentLook
        root.bool("hideWhenTab")?.let { look = look.copy(hideWithTab = it) }
        root.bool("hideWhenChat")?.let { look = look.copy(hideWithChat = it) }
        root.bool("hideWhenF3")?.let { look = look.copy(hideWithF3 = it) }
        root.bool("text_shadow")?.let { look = look.copy(textShadow = it) }
        root.bool("actions")?.let { look = look.copy(clickActions = it) }
        root.bool("outsideSkyBlock")?.let { look = look.copy(outsideSkyblock = it) }
        custom.int("lineSpacing")?.let { look = look.copy(lineSpacing = it.coerceIn(0, 10)) }
        alignment(custom.str("defaultTextAlignment"))?.let { look = look.copy(align = it) }
        bg.bool("enabled")?.let { look = look.copy(background = it) }
        bg.long("color")?.let { c ->
            val argb = c.toInt()
            look = look.copy(backgroundColor = ChromaColor(argb or (0xFF shl 24)), backgroundOpacity = Math.round((argb ushr 24) * 100f / 255f))
        }
        bg.int("padding")?.let { look = look.copy(padding = it) }
        bg.int("radius")?.let { look = look.copy(cornerRadius = it) }
        bg.bool("blurEnabled")?.let { look = look.copy(blur = it) }
        bg.bool("borderEnabled")?.let { look = look.copy(border = it) }
        // Custom Scoreboard colors each corner: the top-left is color 1, the first other corner that differs (the
        // opposite one first) color 2 of a gradient; all four alike import as one color.
        val borderTl = bg.long("borderColorTopLeft")
        if (borderTl != null) {
            val other = listOf("BottomRight", "BottomLeft", "TopRight").firstNotNullOfOrNull { k -> bg.long("borderColor$k")?.takeIf { it != borderTl } }
            look = look.copy(borderColor = ChromaColor(borderTl.toInt() or (0xFF shl 24)))
            look =
                if (other == null) look.copy(borderStyle = ScoreboardLook.BORDER_SOLID)
                else look.copy(borderStyle = ScoreboardLook.BORDER_GRADIENT, borderColor2 = ChromaColor(other.toInt() or (0xFF shl 24)))
        }
        bg.bool("imageBackground")?.let { look = look.copy(backgroundImage = it) }
        mod.str("number_format")?.let { look = look.copy(numbers = if (it == "SHORT") "short" else "long") }
        mod.str("number_display_format")?.let {
            val style = mapOf("TEXT_COLOR_NUMBER" to "tcn", "COLOR_TEXT_NUMBER" to "ctn", "COLOR_NUMBER_TEXT" to "cnt", "COLOR_NUMBER_RESET_TEXT" to "cnrt")[it]
            if (style != null) look = look.copy(numberStyle = style)
        }
        imported += "Look: background, border (gradient colors too), corners, padding, number format, alignment"

        val keepVanilla = root.bool("hide_hypixel")?.not() ?: false
        imported += "Hypixel's sidebar hidden"

        skipped += "Position and size (use the Move GUI)"
        if (bg.int("minWidth").let { it != null && it > 0 } || bg.int("minHeight").let { it != null && it > 0 }) skipped += "Minimum width and height"
        if (bg.int("margin").let { it != null && it > 0 }) skipped += "Background margin"
        if (bg.str("customImageFile").orEmpty().isNotEmpty()) skipped += "Custom background image file"
        skipped += "Per-line details (date format, 24-hour clock, co-op bank layout, powder display and similar): set them in each line's options"

        return ConvertedSetup(ScoreboardSetup(entries), look, keepVanilla, ImportResult(imported, skipped))
    }

    private fun alignment(v: String?): String? = when (v) { "START" -> "left"; "CENTER" -> "center"; "END" -> "right"; else -> null }

    private fun pretty(id: String): String = id.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun JsonObject?.prim(key: String): JsonPrimitive? = this?.get(key) as? JsonPrimitive
    private fun JsonObject?.bool(key: String): Boolean? = prim(key)?.booleanOrNull
    private fun JsonObject?.int(key: String): Int? = prim(key)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
    private fun JsonObject?.long(key: String): Long? = prim(key)?.longOrNull
    private fun JsonObject?.str(key: String): String? = prim(key)?.takeIf { it.isString }?.content
}
