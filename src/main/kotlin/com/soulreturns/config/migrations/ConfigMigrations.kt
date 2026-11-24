package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/**
 * `config.json` schema migrations. Players keep their settings across updates, so any change that
 * would lose a stored value (rename, move between sections, type change, split, removal) bumps
 * [CURRENT] and adds the step that rewrites old files. Adding a new field with a default needs no
 * migration.
 *
 * A step receives the whole root object of the previous version and returns the next version's.
 * Steps only move, convert or drop values; they must tolerate absent keys (a player may never have
 * changed that setting) and never throw on odd input: leave what they don't understand alone.
 * `ConfigCodecTest` fails when a fixture value disappears without a step accounting for it; add a
 * real config of the new version to `src/test/resources/config/` with each bump.
 *
 * Version history:
 * - 0: files written before versioning (up to 2026-11-07).
 * - 1: introduces `configVersion`; drops `dev.enabled` (retired by the developer-access unlock,
 *   which is not a config setting).
 * - 2: custom command keybinds whose command is a preset's move into `commandKeybinds.presets`
 *   ([commandsToPresets]).
 * - 3: the `soul` preset (it ran `/soul`) is gone; a stored `presets.soul` becomes a custom `/soul`
 *   binding again ([soulPresetToCommand]). Its successor `soul_gui` opens the Move GUI instead.
 * - 4: the Metal Detector tools warning and the Bal warning lose their own HUDs to the shared alert;
 *   a moved `mining.metalDetector.toolsWarningTransform` (else a moved `mining.nucleus.balWarningTransform`)
 *   becomes `general.alertTransform` ([warningsToAlert]).
 * - 5: inventory button profiles stop carrying `areas` / `dungeonRun`; where a profile is used is the
 *   separate mapping `inventoryButtons.activeByArea` ([profilesToActive]).
 * - 6: inventory buttons store a grid spot id (`spot`) instead of free `x`/`y`; `snapToGrid` is gone
 *   ([ButtonGridMigration]).
 * - 7: HUD transforms move from the fractional pin to edge anchors with optional links
 *   ([HudPlacementMigration]).
 * - 8: the Soul action bar's default is vanilla's own spot as a bottom-centered screen anchor instead of a
 *   link to the vanilla action bar; any other stored position sets `general.actionBarCustomPosition`
 *   ([ActionBarPlacementMigration]).
 * - 9: the Metal Detector loot panel's default is the top-right (-5 / 5) instead of the top-left; an
 *   unmoved stored transform follows ([MetalDetectorPlacementMigration]).
 * - 10: the Move GUI's edge snap default is 5 px instead of 1; a stored 1 becomes 5, any other
 *   distance stays ([EdgeSnapDefaultMigration]).
 * - 11: the config menu's merged cards get master switches (`general.cleanUpInventory`,
 *   `general.hideEffects`, `general.quietImplosion`, `mining.nucleus.compactChat`): on when any part
 *   was on, else off with the parts at their new defaults; `menu.onboardingDone` set for existing
 *   files ([MergedCardsMigration]).
 * - 12: the Hyperion highlight is gone; `general.highlightHyperion` and `general.highlightHyperionColor`
 *   are dropped.
 * - 13: the Scavenged Tools counter's default is the top-right corner, linked to the left of the Metal
 *   Detector loot panel while it shows, instead of under the vanilla action bar; a stored transform that
 *   is still the old default follows ([ToolsCounterPlacementMigration]).
 * - 14: the Storm lure guide became the bait hint: `dungeons.boss.lure` becomes `baitHint`, its
 *   `lureTarget`, `lureTravelTicks` and `lureGoSound` are dropped ([lureToBaitHint]).
 * - 15: the boss helpers' crush readout and timer table became the crosshair timers
 *   (mocks/boss-timers/): one master, `units`, per-timer `show*` switches, `markPillar`; the two HUD
 *   transforms, backgrounds, outline colors and `edgeMargin` are dropped ([bossCrosshairTimers]).
 * - 16: the crosshair timers became fixed places (mocks/boss-timers/): `showPadRing` becomes `showPad`,
 *   `padRingWhen` becomes `padWhen`, `baitHint` is dropped for `showArm` (default on), and `goldorRing`
 *   starts off, its new default ([bossFixedPlaces]).
 * - 17: "Override blocking while" became per device: a stored `dungeons.devices.overrideBlockingWhile`
 *   is copied into `simonSaysOverride`, `lightsOverride` and `arrowAlignOverride` and dropped ([deviceOverridePerDevice]).
 * - 18: dungeon map class defaults follow the leap palette; custom colors stay ([mapClassColorsFromLeap]).
 * - 19: the chest search binding opens and reopens search; `islandChests.clearKey` is retired.
 *   Every stored `searchKey` stays unchanged, including unbound and the former default I.
 * - 20: SkyBlock 0.27.3 replaced the combo's Magic Find with Combat Wisdom: `combo.showMagicFind` becomes
 *   `combo.showCombatWisdom` (same on/off), `combo.showCombatExp` is dropped with the Combat Exp row
 *   ([comboMagicFindToWisdom]).
 * - 21: the terminal solver's "Show left / right labels" is off by default; a stored `dungeons.terminals.rubixLabels`
 *   becomes false for everyone (one-time owner-decided flip, 2026-11-24; [RubixLabelsOffMigration]).
 */
object ConfigMigrations {
    const val CURRENT = 21

    /** `steps[n]` turns a version-n root into version n+1. */
    private val steps: List<(Map<String, JsonElement>) -> Map<String, JsonElement>> =
        listOf(
            { root -> root.withObject("dev") { it - "enabled" } }, // 0 → 1
            ::commandsToPresets, // 1 → 2
            ::soulPresetToCommand, // 2 → 3
            ::warningsToAlert, // 3 → 4
            ::profilesToActive, // 4 → 5
            ButtonGridMigration::migrate, // 5 → 6
            HudPlacementMigration::migrate, // 6 → 7
            ActionBarPlacementMigration::migrate, // 7 → 8
            MetalDetectorPlacementMigration::migrate, // 8 → 9
            EdgeSnapDefaultMigration::migrate, // 9 → 10
            MergedCardsMigration::migrate, // 10 → 11
            { root -> root.withObject("general") { it - "highlightHyperion" - "highlightHyperionColor" } }, // 11 → 12
            ToolsCounterPlacementMigration::migrate, // 12 → 13
            ::lureToBaitHint, // 13 → 14
            ::bossCrosshairTimers, // 14 → 15
            ::bossFixedPlaces, // 15 → 16
            ::deviceOverridePerDevice, // 16 → 17
            ::mapClassColorsFromLeap, // 17 → 18
            { root -> root.withObject("islandChests") { it - "clearKey" } }, // 18 → 19
            ::comboMagicFindToWisdom, // 19 → 20
            RubixLabelsOffMigration::migrate, // 20 → 21
        )

    init {
        check(steps.size == CURRENT) { "one migration step per version: ${steps.size} steps for version $CURRENT" }
    }

    /** Runs the steps from [fromVersion] up to [CURRENT]. A newer file passes through unchanged. */
    fun migrate(root: JsonObject, fromVersion: Int): Map<String, JsonElement> {
        var tree: Map<String, JsonElement> = root
        for (version in fromVersion.coerceAtLeast(0) until CURRENT) tree = steps[version](tree)
        return tree
    }

    /**
     * Preset id by command for version 2. A snapshot on purpose: later preset changes must not
     * change what this step does to a version-1 file. `/soul` was in it until version 3 retired the
     * `soul` preset; it stays out so a `/soul` binding is no longer turned into a preset.
     */
    private val presetByCommand: Map<String, String> =
        mapOf(
            "storage" to "storage",
            "loadouts" to "loadouts",
            "stats" to "stats",
            "wardrobe" to "wardrobe",
            "trades" to "trades",
            "pets" to "pets",
            "sacks" to "sacks",
            "ah" to "auction_house",
            "bz" to "bazaar",
            "craft" to "craft",
        )

    private val whitespace = Regex("\\s+")

    /**
     * A custom binding whose command is a preset's (leading `/`, case and extra blanks ignored) becomes
     * that preset's binding (key + on/off) and leaves the custom list. Never loses a key: when the preset
     * is already bound (stored, or by an earlier entry), the custom entry is dropped only if its key is
     * identical. Unbound matches are dropped; the preset tile is always there.
     */
    private fun commandsToPresets(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val bindings = root["commandBindings"] as? JsonArray ?: return root
        val settings = (root["commandKeybinds"] ?: JsonObject(emptyMap())) as? JsonObject ?: return root
        val presets = LinkedHashMap((settings["presets"] ?: JsonObject(emptyMap())) as? JsonObject ?: return root)
        val kept = ArrayList<JsonElement>()
        for (entry in bindings) {
            val obj = entry as? JsonObject
            val id = obj?.text("command")?.let { presetByCommand[normalizeCommand(it)] }
            val key = obj?.text("key")
            if (obj == null || id == null || key == null) {
                kept += entry // not a preset's command, or a key this step does not understand
                continue
            }
            if (key.isBlank()) continue // unbound: the preset tile is always there
            val current = presets[id]
            val currentKey = if (current == null) "" else (current as? JsonObject)?.text("key")
            when {
                currentKey == null -> kept += entry
                currentKey.isBlank() -> presets[id] = JsonObject(obj.filterKeys { it == "key" || it == "enabled" })
                currentKey != key -> kept += entry
            }
        }
        if (kept.size == bindings.size) return root
        return root + mapOf(
            "commandBindings" to JsonArray(kept),
            "commandKeybinds" to JsonObject(settings + ("presets" to JsonObject(presets))),
        )
    }

    /**
     * The `soul` preset only ever ran `/soul` (the config menu), and version 2 also produced it from a
     * custom `/soul` binding. Its key + on/off go back to the end of the custom list as `soul`; an
     * unbound entry is dropped. A value this step does not understand stays where it is.
     */
    private fun soulPresetToCommand(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val settings = root["commandKeybinds"] as? JsonObject ?: return root
        val presets = settings["presets"] as? JsonObject ?: return root
        val soul = presets["soul"] as? JsonObject ?: return root
        val key = soul.text("key") ?: return root
        val bindings = (root["commandBindings"] ?: JsonArray(emptyList())) as? JsonArray ?: return root
        val entry = JsonObject(mapOf("command" to JsonPrimitive("soul")) + soul.filterKeys { it == "key" || it == "enabled" })
        val moved = if (key.isBlank()) bindings else JsonArray(bindings + entry)
        return root + mapOf(
            "commandBindings" to moved,
            "commandKeybinds" to JsonObject(settings + ("presets" to JsonObject(presets - "soul"))),
        )
    }

    /**
     * Version 3 defaults of the two retired warning transforms (both centered; anchorY differs), a
     * snapshot like [presetByCommand].
     */
    private val oldWarningDefaults: Map<String, Double> =
        mapOf(
            "anchorX" to 0.5, "offsetX" to 0.0, "offsetY" to 0.0, "scale" to 1.0, "hAnchor" to 1.0, "vAnchor" to 1.0,
            "fontMode" to 0.0, "fontChoice" to 0.0, "textShadowMode" to 0.0,
        )

    /**
     * Both warnings now draw through the one shared alert. Its transform takes the tools warning's
     * when the player moved it, else the Bal warning's when that was moved; unmoved ones leave the
     * new default (the tools warning's old spot). The two old keys are dropped either way.
     */
    private fun warningsToAlert(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val mining = root["mining"] as? JsonObject ?: return root
        val detector = mining["metalDetector"] as? JsonObject
        val nucleus = mining["nucleus"] as? JsonObject
        val moved = listOf(detector?.get("toolsWarningTransform") to 0.38, nucleus?.get("balWarningTransform") to 0.30)
            .firstOrNull { (t, anchorY) -> t is JsonObject && !isOldDefault(t, anchorY) }?.first
        var out = root
        val general = (root["general"] ?: JsonObject(emptyMap())) as? JsonObject
        if (moved != null && general != null) out = out + ("general" to JsonObject(general + ("alertTransform" to moved)))
        var m: Map<String, JsonElement> = mining
        if (detector != null) m = m + ("metalDetector" to JsonObject(detector - "toolsWarningTransform"))
        if (nucleus != null) m = m + ("nucleus" to JsonObject(nucleus - "balWarningTransform"))
        return out + ("mining" to JsonObject(m))
    }

    /**
     * Version 4 profiles replaced Default in their `areas` and, with `dungeonRun`, in a dungeon run;
     * a place two profiles claimed showed Default. Version 5 maps each place to exactly one profile
     * id, so a place claimed by one profile becomes `activeByArea[place] = id` (the run under
     * `@dungeon-run`) and a contested place gets **no entry**: it keeps falling back to Everywhere,
     * which the missing `activeEverywhere` leaves at Default, so the player sees exactly what they saw
     * before. The claim itself is dropped (one slot cannot hold two profiles); the editor lets the
     * player assign the place to either profile again. `areas` and `dungeonRun` are removed.
     */
    private fun profilesToActive(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val buttons = root["inventoryButtons"] as? JsonObject ?: return root
        val profiles = buttons["profiles"] as? JsonArray ?: return root
        val claims = LinkedHashMap<String, MutableSet<String>>()
        val cleaned = profiles.map { entry ->
            val profile = entry as? JsonObject ?: return@map entry
            val id = (profile["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id != null) {
                for (area in profile["areas"] as? JsonArray ?: JsonArray(emptyList())) {
                    val name = (area as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                    claims.getOrPut(name) { LinkedHashSet() }.add(id)
                }
                if ((profile["dungeonRun"] as? JsonPrimitive)?.booleanOrNull == true) {
                    claims.getOrPut("@dungeon-run") { LinkedHashSet() }.add(id)
                }
            }
            JsonObject(profile - "areas" - "dungeonRun")
        }
        val active = LinkedHashMap((buttons["activeByArea"] as? JsonObject) ?: JsonObject(emptyMap()))
        for ((slot, ids) in claims) if (ids.size == 1) active.putIfAbsent(slot, JsonPrimitive(ids.first()))
        return root + ("inventoryButtons" to JsonObject(buttons + ("profiles" to JsonArray(cleaned)) + ("activeByArea" to JsonObject(active))))
    }

    /** Absent keys count as their default (a field added later, or a hand-edited file). */
    private fun isOldDefault(t: JsonObject, anchorY: Double): Boolean =
        (oldWarningDefaults + ("anchorY" to anchorY)).all { (key, default) ->
            val value = t[key] ?: return@all true
            (value as? JsonPrimitive)?.doubleOrNull == default
        }

    private fun normalizeCommand(command: String): String =
        command.trim().removePrefix("/").replace(whitespace, " ").trim().lowercase(Locale.ROOT)

    /** The string at [key]; "" when absent (the stored default), null when it is not a string. */
    private fun JsonObject.text(key: String): String? {
        val value = this[key] ?: return ""
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** [key]'s object rewritten by [change]; absent or non-object values are left alone. */
    /** The Storm lure guide (spot + GO cue) became the bait hint: its switch carries over, its settings go. */
    private fun lureToBaitHint(root: Map<String, JsonElement>): Map<String, JsonElement> =
        root.withObject("dungeons") { dungeons ->
            dungeons.withObject("boss") { boss ->
                val lure = boss["lure"]
                val rest = boss - "lure" - "lureTarget" - "lureTravelTicks" - "lureGoSound"
                if (lure == null) rest else rest + ("baitHint" to lure)
            }
        }

    /**
     * The boss helpers' two HUDs (crush readout, timer table) became the crosshair timers: one
     * master (on if either was), its unit without "both", the per-timer switches of the table. A group
     * whose old master was off while the other was on starts off too; with both off the defaults stay.
     */
    private fun bossCrosshairTimers(root: Map<String, JsonElement>): Map<String, JsonElement> =
        root.withObject("dungeons") { dungeons ->
            dungeons.withObject("boss") { boss ->
                fun flag(key: String, default: Boolean) = (boss[key] as? JsonPrimitive)?.booleanOrNull ?: default
                val crush = flag("crushWindow", false)
                val timers = flag("timers", false)
                val out = LinkedHashMap(
                    boss - "timers" - "timerUnit" - "timersBackground" - "timersTransform" - "crushHud" - "crushResult" -
                        "crushBackground" - "crushTransform" - "crushOutline" - "edgeMargin" - "outlineIdle" - "outlineArmed" -
                        "outlineInside" - "timerPad" - "timerPurple" - "timerLightning" - "timerStormState" - "timerGoldor",
                )
                out["crushWindow"] = JsonPrimitive(crush || timers)
                if ((boss["timerUnit"] as? JsonPrimitive)?.intOrNull == 1) out["units"] = JsonPrimitive(1)
                boss["crushOutline"]?.let { out["markPillar"] = it }
                if (crush || timers) {
                    out["showWindow"] = JsonPrimitive(crush && (flag("crushHud", true) || flag("crushResult", true)))
                    out["showPadRing"] = JsonPrimitive(timers && flag("timerPad", true))
                    out["showPurple"] = JsonPrimitive(timers && flag("timerPurple", true))
                    out["showGiga"] = JsonPrimitive(timers && flag("timerLightning", true))
                    out["showHigh"] = JsonPrimitive(timers && flag("timerStormState", true))
                    out["showGoldor"] = JsonPrimitive(timers && flag("timerGoldor", true))
                }
                out
            }
        }

    /** The single-slot timers became fixed places: renamed pad switches, the arm cue on by default, the Goldor ring off. */
    private fun bossFixedPlaces(root: Map<String, JsonElement>): Map<String, JsonElement> =
        root.withObject("dungeons") { dungeons ->
            dungeons.withObject("boss") { boss ->
                val out = LinkedHashMap(boss - "showPadRing" - "padRingWhen" - "baitHint" - "goldorRing")
                boss["showPadRing"]?.let { out["showPad"] = it }
                boss["padRingWhen"]?.let { out["padWhen"] = it }
                out
            }
        }

    /** The one "Override blocking while" of the device card became one per blocking device; its stored value goes to all three. */
    private fun deviceOverridePerDevice(root: Map<String, JsonElement>): Map<String, JsonElement> =
        root.withObject("dungeons") { dungeons ->
            dungeons.withObject("devices") { devices ->
                val old = devices["overrideBlockingWhile"] ?: return@withObject devices
                devices - "overrideBlockingWhile" + mapOf("simonSaysOverride" to old, "lightsOverride" to old, "arrowAlignOverride" to old)
            }
        }

    /** Old map defaults by key (snapshot) to the leap palette that replaced them. */
    private val oldMapClassColors: Map<String, Pair<Int, Int>> =
        mapOf(
            "classArcher" to (0xFFFF5555.toInt() to 0xFFFFAA00.toInt()),
            "classBerserk" to (0xFFFFAA00.toInt() to 0xFFFF5555.toInt()),
            "classMage" to (0xFF00AAAA.toInt() to 0xFF55FFFF.toInt()),
            "classHealer" to (0xFFAA00AA.toInt() to 0xFFFF55FF.toInt()),
            "classTank" to (0xFF55FF55.toInt() to 0xFF55C855.toInt()),
        )

    private fun mapClassColorsFromLeap(root: Map<String, JsonElement>): Map<String, JsonElement> =
        root.withObject("dungeons") { dungeons ->
            dungeons.withObject("colors") { colors ->
                var out = colors
                for ((key, change) in oldMapClassColors) {
                    val color = colors[key] as? JsonObject ?: continue
                    val argb = (color["argb"] as? JsonPrimitive)?.intOrNull ?: continue
                    val chroma = (color["chroma"] as? JsonPrimitive)?.booleanOrNull ?: false
                    if (argb == change.first && !chroma) out = out + (key to JsonObject(color + ("argb" to JsonPrimitive(change.second))))
                }
                out
            }
        }

    /** The player's Magic Find switch carries over as the Combat Wisdom switch; an existing new key wins. */
    private fun comboMagicFindToWisdom(root: Map<String, JsonElement>): Map<String, JsonElement> =
        root.withObject("combo") { combo ->
            val shown = combo["showMagicFind"]
            val rest = combo - "showMagicFind" - "showCombatExp"
            if (shown != null && "showCombatWisdom" !in rest) rest + ("showCombatWisdom" to shown) else rest
        }

    private fun Map<String, JsonElement>.withObject(
        key: String,
        change: (Map<String, JsonElement>) -> Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val obj = this[key] as? JsonObject ?: return this
        return this + (key to JsonObject(change(obj)))
    }
}
