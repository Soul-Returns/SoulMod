package com.soulreturns.config

import com.soulreturns.config.migrations.ConfigMigrations
import com.soulreturns.features.inventorybuttons.InventoryButtonProfiles
import com.soulreturns.features.inventorybuttons.InventoryButtonSpots
import com.soulreturns.ui.hud.movable.HudAnchor
import com.soulreturns.ui.hud.movable.HudLink
import com.soulreturns.ui.hud.movable.HudLinkHidden
import com.soulreturns.ui.hud.movable.HudPlace
import com.soulreturns.ui.hud.movable.HudTransform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Players' settings must survive updates. Every file in `src/test/resources/config/` is a real
 * config.json from some version; when a change renames, moves or retypes a setting without a
 * migration, [everyStoredValueSurvives] names the value that would have been lost.
 */
class ConfigCodecTest {
    private val fixtures: List<File> =
        File(javaClass.getResource("/config")!!.toURI()).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }

    @Test
    fun everyStoredValueSurvives() {
        assertTrue(fixtures.isNotEmpty())
        for (fixture in fixtures) {
            val original = ConfigCodec.json.parseToJsonElement(fixture.readText()).jsonObject
            val version = (original[ConfigCodec.VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: 0
            val expected = JsonObject(ConfigMigrations.migrate(original, version) - ConfigCodec.VERSION_KEY)
            val decoded = ConfigCodec.decode(fixture.readText())
            assertEquals(emptyList(), decoded.notes, "${fixture.name} decoded with problems")
            val written = ConfigCodec.json.parseToJsonElement(ConfigCodec.encode(decoded.config)).jsonObject
            val lost = leaves(expected).filter { (path, value) -> at(written, path) != value }.map { it.first.joinToString(".") }
            assertEquals(emptyList(), lost, "${fixture.name}: values lost without a migration")
        }
    }

    @Test
    fun brokenValueResetsOnlyThatValue() {
        val tree = JsonObject(ConfigMigrations.migrate(ConfigCodec.json.parseToJsonElement(fixtures.first().readText()).jsonObject, 0))
        val general = tree.getValue("general").jsonObject
        val broken =
            JsonObject(
                tree + mapOf(
                    "general" to JsonObject(general + ("darkUi" to JsonPrimitive("banana"))),
                    "commandBindings" to JsonArray(listOf(JsonPrimitive(42)) + (tree["commandBindings"] as? JsonArray).orEmpty()),
                ),
            )
        val decoded = ConfigCodec.decode(broken.toString())
        assertTrue(decoded.notes.any { "general.darkUi" in it }, decoded.notes.toString())
        assertTrue(decoded.notes.any { "commandBindings[]" in it }, decoded.notes.toString())
        assertEquals(SoulConfig().general.darkUi, decoded.config.general.darkUi)
        val written = ConfigCodec.json.parseToJsonElement(ConfigCodec.encode(decoded.config)).jsonObject
        for ((path, value) in leaves(JsonObject(tree - "commandBindings"))) {
            if (path == listOf("general", "darkUi")) continue
            assertEquals(value, at(written, path), "${path.joinToString(".")} was lost next to a broken value")
        }
        for ((path, value) in leaves(JsonObject(mapOf("commandBindings" to tree.getValue("commandBindings"))))) {
            assertEquals(value, at(written, path), "${path.joinToString(".")} was lost next to a broken entry")
        }
    }

    @Test
    fun plainCommandBindingsLoad() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v0-") }.readText()).config
        assertEquals(
            listOf(
                CommandBinding("soul keybinds", "key.keyboard.backslash"),
                CommandBinding("sax", "key.keyboard.i"),
                CommandBinding("skyblocker crystalWaypoints add ~29 ~-32 ~48 Unknown", "key.keyboard.right.alt"),
                CommandBinding("od ep", "key.keyboard.j"),
            ),
            decoded.commandBindings,
        )
        assertEquals(
            CommandKeybindsConfig(
                presets = mapOf(
                    "pets" to PresetBinding("key.keyboard.x"),
                    "trades" to PresetBinding("key.keyboard.y"),
                    "bazaar" to PresetBinding("key.keyboard.u"),
                    "stats" to PresetBinding("key.keyboard.q"),
                    "wardrobe" to PresetBinding("key.keyboard.grave.accent"),
                    "auction_house" to PresetBinding("key.keyboard.o"),
                    "loadouts" to PresetBinding("key.keyboard.left.alt"),
                    "storage" to PresetBinding("key.keyboard.b"),
                ),
            ),
            decoded.commandKeybinds,
        )
    }

    // v1 -> v2: a custom entry with a preset's command becomes the preset's binding unless that would lose a key.
    @Test
    fun customBindingsWithPresetCommandsBecomePresets() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v1-preset-commands") }.readText())
        assertEquals(1, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        assertEquals(
            listOf(
                CommandBinding("soul keybinds", "key.keyboard.backslash"),
                CommandBinding("/pets", "key.keyboard.k"), // pets already taken by the first /pets entry
                CommandBinding("/warp dh", "key.keyboard.f6", enabled = false),
                CommandBinding("craft", "key.keyboard.c"), // preset bound to another key
                CommandBinding("  /Soul  ", "key.keyboard.right.shift"), // /soul is no preset's command
                CommandBinding("od ep", "key.keyboard.j"),
            ),
            decoded.config.commandBindings,
        )
        assertEquals(
            CommandKeybindsConfig(
                presetsOpen = false,
                presets = mapOf(
                    "wardrobe" to PresetBinding("key.keyboard.grave.accent"),
                    "craft" to PresetBinding("key.keyboard.right.control+key.keyboard.c"),
                    "storage" to PresetBinding("key.keyboard.b"),
                    "pets" to PresetBinding("key.keyboard.x", enabled = false),
                    "auction_house" to PresetBinding("key.keyboard.left.control+key.keyboard.o"),
                ),
            ),
            decoded.config.commandKeybinds,
        )
    }

    // v2 -> v3: the retired `soul` preset (it ran /soul) goes back to the custom list.
    @Test
    fun soulPresetBecomesCustomCommand() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v2-soul-preset") }.readText())
        assertEquals(2, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        assertEquals(
            listOf(
                CommandBinding("warp cn", "key.keyboard.semicolon"),
                CommandBinding("soul", "key.keyboard.backslash"),
            ),
            decoded.config.commandBindings,
        )
        assertEquals(null, decoded.config.commandKeybinds.presets["soul"])
        assertEquals(null, decoded.config.commandKeybinds.presets["soul_gui"])
        assertEquals(PresetBinding("key.keyboard.b"), decoded.config.commandKeybinds.presets["storage"])
        assertEquals(8, decoded.config.commandKeybinds.presets.size)

        val disabled = JsonObject(
            mapOf(
                "configVersion" to JsonPrimitive(2),
                "commandKeybinds" to JsonObject(
                    mapOf(
                        "presets" to JsonObject(
                            mapOf("soul" to JsonObject(mapOf("key" to JsonPrimitive("key.keyboard.k"), "enabled" to JsonPrimitive(false)))),
                        ),
                    ),
                ),
            ),
        )
        val noCustomList = ConfigCodec.decode(disabled.toString()).config
        assertEquals(listOf(CommandBinding("soul", "key.keyboard.k", enabled = false)), noCustomList.commandBindings)
        assertEquals(emptyMap(), noCustomList.commandKeybinds.presets)
    }

    // v3 -> v4: the moved tools warning (else the moved Bal warning) becomes the shared alert's spot
    // (then v7 re-anchors it: centered, 107.5 px above the middle at the reference window).
    @Test
    fun warningTransformsMoveToSharedAlert() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v3-alert-transform") }.readText())
        assertEquals(3, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        assertEquals(
            HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.CENTER, offsetY = -107.5f, scale = 1.25f),
            decoded.config.general.alertTransform,
        )

        fun v3(tools: String, bal: String): String =
            """{"configVersion":3,"mining":{"metalDetector":{"toolsWarningTransform":$tools},"nucleus":{"balWarningTransform":$bal}}}"""
        val toolsDefault = """{"anchorX":0.5,"anchorY":0.38,"hAnchor":1,"vAnchor":1}"""
        val balDefault = """{"anchorX":0.5,"anchorY":0.30,"hAnchor":1,"vAnchor":1}"""
        val balMoved = """{"anchorX":0.5,"anchorY":0.30,"offsetX":12.0,"hAnchor":1,"vAnchor":1,"fontChoice":2}"""
        assertEquals(
            HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.CENTER, offsetX = 12f, offsetY = -108f, fontChoice = 2),
            ConfigCodec.decode(v3(toolsDefault, balMoved)).config.general.alertTransform,
        )
        assertEquals(SoulConfig().general.alertTransform, ConfigCodec.decode(v3(toolsDefault, balDefault)).config.general.alertTransform)
    }

    // v4 -> v5: a place one profile claimed becomes that profile's slot; a place two claimed keeps Default.
    @Test
    fun profileAreasBecomeActiveSlots() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v4-button-profiles") }.readText())
        assertEquals(4, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        val buttons = decoded.config.inventoryButtons
        assertEquals(listOf("Mining", "Hollows only", "Dungeons"), buttons.profiles.map { it.name })
        assertEquals(listOf(2, 1, 1), buttons.profiles.map { it.buttons.size })
        assertEquals(InventoryButtonsConfig.DEFAULT_ID, buttons.activeEverywhere)
        assertEquals(
            mapOf(
                "Dwarven Mines" to "3f9a1c2e",
                "Mineshaft" to "3f9a1c2e",
                "Hub" to "b7d04e55",
                InventoryButtonsConfig.RUN_KEY to "a21c9d80",
            ),
            buttons.activeByArea, // Crystal Hollows was claimed twice: no slot, Default shows there as before
        )
        assertEquals(listOf("Hub", "Dwarven Mines", "Crystal Hollows", "Private Island"), buttons.seenAreas)

        fun profile(area: String?, inDungeon: Boolean) = InventoryButtonProfiles.resolve(buttons, area, inDungeon)
        assertEquals("3f9a1c2e", profile("Mineshaft", false))
        assertEquals(InventoryButtonsConfig.DEFAULT_ID, profile("Crystal Hollows", false))
        assertEquals("a21c9d80", profile("Dungeon", true))

        val twoRuns = """{"configVersion":4,"inventoryButtons":{"profiles":[{"id":"a","name":"A","dungeonRun":true},{"id":"b","name":"B","dungeonRun":true},{"id":"c","name":"C","areas":["Hub","Hub"]}]}}"""
        assertEquals(mapOf("Hub" to "c"), ConfigCodec.decode(twoRuns).config.inventoryButtons.activeByArea)
    }

    // v5 -> v6: free x/y become the nearest grid spot; rings first, screen edges for buttons beyond them.
    @Test
    fun buttonPositionsBecomeSpots() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v5-button-grid") }.readText())
        assertEquals(5, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        val config = decoded.config.inventoryButtons
        val spots = config.buttons.map { it.spot }
        assertEquals(
            listOf(
                "G:10:7", // right of the frame, old row 7
                "G:10:4",
                "G:10:5",
                "G:3:0", // above
                "G:4:10", // below
                "G:0:3", // left
                "G:3:-1", // overlapped the button above: next nearest free spot, straight above it in ring 2
                "T:1:4", // beyond ring 2 above: top screen row
                "L:1:3", // beyond ring 2 on the left: left screen column
                "R:0:14", // off screen bottom-right (it was clamped into the corner): right screen column
                "G:1:0", // never moved, no x/y stored: the old default spot above the frame
            ),
            spots,
        )
        assertEquals(listOf("warp cn", "od ep", "sax", "storage", "pets", "wardrobe", "bz", "warp hub", "ah", "craft", "trades"), config.buttons.map { it.command })
        assertEquals(false, config.buttons[5].inventoryOnly)
        assertEquals(listOf(listOf("G:10:7", "G:10:8"), listOf("G:10:7"), listOf("G:10:7")), config.profiles.map { p -> p.buttons.map { it.spot } })
        // Every migrated spot exists on a 1920×1080 screen at GUI scale 3.
        val layout = InventoryButtonSpots.layout(640, 360)
        assertTrue((spots + config.profiles.flatMap { p -> p.buttons.map { it.spot } }).all { layout.at(it) != null })

        // Collisions never drop a button: a full list still keeps every entry, overflow ids are hidden.
        val crowd = (0 until 300).joinToString(",") { """{"command":"c$it","x":0,"y":-26}""" }
        val crowded = ConfigCodec.decode("""{"configVersion":5,"inventoryButtons":{"snapToGrid":false,"buttons":[$crowd]}}""").config.inventoryButtons.buttons
        assertEquals(300, crowded.size)
        assertEquals(300, crowded.map { it.spot }.toSet().size)
        assertEquals("G:1:0", crowded.first().spot)
        assertEquals("G:-2:27", crowded.last().spot) // 272 grid spots taken: the rest wait in the tray
        assertEquals(null, InventoryButtonSpots.layout(640, 360).at(crowded.last().spot))
    }

    // v6 -> v7: each transform is resolved with the old pin math at 960×540 Soul px (1920×1080, GUI 2,
    // Soul 2), kept on that screen and anchored to its nearest region; unmoved HUDs take the new
    // default (links for Action Bar, Scavenged Tools and Sprint Toggle); style values stay.
    @Test
    fun hudTransformsBecomeEdgeAnchors() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v6-hud-placement") }.readText())
        assertEquals(6, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        val c = decoded.config
        val defaults = SoulConfig()
        // The user's Sack Tracker (pin 99 % / 35 %, 234 px up): its top was 45 px above the reference
        // screen, so it lands on the top edge, 29.5 px in from the right.
        assertEquals(HudTransform(hAnchor = HudAnchor.END, vAnchor = HudAnchor.START, offsetX = -29.5f, offsetY = 0f), c.sacks.hudTransform)
        // Moved right of the hotbar's default spot: no link, nearest regions (center, bottom).
        assertEquals(HudTransform(hAnchor = HudAnchor.CENTER, vAnchor = HudAnchor.END, offsetX = 180f, offsetY = -4f), c.general.toggleSprintHudTransform)
        // Never moved: the vanilla links, keeping scale and font.
        assertEquals(defaults.general.actionBarTransform.copy(scale = 1.9f), c.general.actionBarTransform)
        assertEquals(defaults.mining.metalDetector.toolsCounterTransform.copy(fontChoice = 2), c.mining.metalDetector.toolsCounterTransform)
        assertEquals(defaults.notifications.hud, c.notifications.hud)
        assertEquals(defaults.mining.nucleus.balTransform, c.mining.nucleus.balTransform)
        // A 2.8× map dragged past the top-left corner is kept on screen there.
        assertEquals(HudTransform(scale = 2.8f), c.dungeons.mapHud)
        // Pushed past the right edge: flush right, 36.5 px down.
        assertEquals(HudTransform(hAnchor = HudAnchor.END, offsetX = 0f, offsetY = 36.5f), c.mining.metalDetector.hudTransform)
        assertEquals(1.3f, c.fishing.trackerHudTransform.scale)
        assertTrue(listOf(c.sacks.hudTransform, c.general.toggleSprintHudTransform, c.dungeons.mapHud).all { it.link == null && it.autoAnchor })
    }

    // v7 -> v8: the action bar's default link to the vanilla action bar becomes the plain bottom-centered
    // default (vanilla's spot is resolved live); any other stored transform keeps its position.
    @Test
    fun actionBarLeavesVanillaLink() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v7-action-bar-link") }.readText())
        assertEquals(7, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        val g = decoded.config.general
        assertEquals(SoulConfig().general.actionBarTransform.copy(scale = 1.4f, fontMode = 2), g.actionBarTransform)
        assertEquals(false, g.actionBarCustomPosition)
        assertEquals("vanilla:hotbar", g.toggleSprintHudTransform.link?.target) // other HUDs keep their links

        fun v7(transform: String) = """{"configVersion":7,"general":{"actionBarTransform":$transform}}"""
        val detached = """{"hAnchor":1,"vAnchor":2,"offsetX":30.0,"offsetY":-80.0,"autoAnchor":true,"link":null}"""
        val movedAlong = """{"hAnchor":1,"vAnchor":2,"offsetY":-62.0,"link":{"target":"vanilla:actionbar","h":1,"v":1,"offsetX":14.0,"follow":true,"auto":false}}"""
        val elsewhere = """{"hAnchor":1,"vAnchor":2,"offsetY":-30.0,"link":{"target":"hud:alert","h":1,"v":3,"follow":true}}"""
        for (stored in listOf(detached, movedAlong, elsewhere)) {
            val c = ConfigCodec.decode(v7(stored)).config.general
            assertEquals(true, c.actionBarCustomPosition, stored)
            assertEquals(ConfigCodec.json.decodeFromString<HudTransform>(stored), c.actionBarTransform, stored)
        }
    }

    // v8 -> v9: an unmoved Metal Detector panel follows the new top-right default; a moved one stays.
    @Test
    fun metalDetectorPanelFollowsNewDefault() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v8-metal-detector-placement") }.readText())
        assertEquals(8, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        assertEquals(SoulConfig().mining.metalDetector.hudTransform.copy(scale = 1.2f), decoded.config.mining.metalDetector.hudTransform)
        val d = SoulConfig().mining.metalDetector.hudTransform
        assertEquals(HudAnchor.END, d.hAnchor)
        assertEquals(HudAnchor.START, d.vAnchor)
        assertEquals(-5f, d.offsetX)
        assertEquals(5f, d.offsetY)
        assertEquals(null, d.link)

        val moved = """{"hAnchor":0,"vAnchor":0,"offsetX":30.0,"offsetY":11.0,"autoAnchor":true}"""
        val c = ConfigCodec.decode("""{"configVersion":8,"mining":{"metalDetector":{"hudTransform":$moved}}}""").config
        assertEquals(ConfigCodec.json.decodeFromString<HudTransform>(moved), c.mining.metalDetector.hudTransform)
    }

    // v12 -> v13: an unmoved Scavenged Tools counter follows the new default (top right, left of the loot panel); a moved or re-linked one stays.
    @Test
    fun toolsCounterFollowsNewDefault() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v12-tools-counter-placement") }.readText())
        assertEquals(12, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        assertEquals(SoulConfig().mining.metalDetector.toolsCounterTransform.copy(scale = 1.3f, fontMode = 2), decoded.config.mining.metalDetector.toolsCounterTransform)
        val d = SoulConfig().mining.metalDetector.toolsCounterTransform
        assertEquals(HudAnchor.END, d.hAnchor)
        assertEquals(HudAnchor.START, d.vAnchor)
        assertEquals(-5f, d.offsetX)
        assertEquals(5f, d.offsetY)
        assertEquals(HudLink("hud:metal_detector_tracker", h = HudPlace.OUT_START, v = HudPlace.START, offsetX = -5f, follow = true, auto = false), d.link)
        assertEquals(HudLinkHidden.SCREEN, d.link!!.whenHidden)

        fun v12(transform: String) = """{"configVersion":12,"mining":{"metalDetector":{"toolsCounterTransform":$transform}}}"""
        val moved = """{"hAnchor":1,"vAnchor":2,"offsetX":30.0,"offsetY":-80.0,"autoAnchor":true,"link":null}"""
        val shifted = """{"hAnchor":1,"vAnchor":2,"offsetY":-32.0,"link":{"target":"vanilla:actionbar","h":1,"v":3,"offsetX":14.0,"offsetY":3.0,"follow":true,"auto":false}}"""
        val hotbar = """{"hAnchor":1,"vAnchor":2,"offsetY":-32.0,"link":{"target":"vanilla:hotbar","h":1,"v":3,"offsetY":3.0,"follow":true,"auto":false}}"""
        val topLeft = """{"hAnchor":0,"vAnchor":0,"offsetX":9.5,"offsetY":11.0,"link":null}"""
        for (stored in listOf(moved, shifted, hotbar, topLeft)) {
            val c = ConfigCodec.decode(v12(stored)).config.mining.metalDetector
            assertEquals(ConfigCodec.json.decodeFromString<HudTransform>(stored), c.toolsCounterTransform, stored)
        }
        // Absent transform = never written: decodes to the new default without a step.
        assertEquals(d, ConfigCodec.decode("""{"configVersion":12}""").config.mining.metalDetector.toolsCounterTransform)
    }

    @Test
    fun presetsAndDisabledBindingsRoundTrip() {
        val config = SoulConfig(
            commandBindings = listOf(CommandBinding("warp dh", "key.keyboard.f6", enabled = false), CommandBinding("storage", "")),
            commandKeybinds = CommandKeybindsConfig(
                enabled = false,
                presetsOpen = false,
                presets = mapOf("craft" to PresetBinding("key.keyboard.right.control+key.keyboard.c"), "pets" to PresetBinding("key.keyboard.p", enabled = false)),
            ),
        )
        val decoded = ConfigCodec.decode(ConfigCodec.encode(config))
        assertEquals(emptyList(), decoded.notes)
        assertEquals(config, decoded.config)
    }

    // v9 -> v10: the untouched edge snap (1 px, always written to the file) becomes the new 5 px default;
    // a chosen distance stays, including one set back to 1 on a v10 file.
    @Test
    fun edgeSnapOldDefaultBecomesFive() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v9-edge-snap") }.readText())
        assertEquals(9, decoded.fileVersion)
        assertEquals(emptyList(), decoded.notes)
        val m = decoded.config.general.moveGui
        assertEquals(5f, m.edgeSnap)
        assertEquals(7.5f, m.linkGap)
        assertEquals(false, m.confirmDetach)
        assertEquals(5f, SoulConfig().general.moveGui.edgeSnap)
        fun snap(version: Int, value: String) =
            ConfigCodec.decode("""{"configVersion":$version,"general":{"moveGui":{"edgeSnap":$value}}}""").config.general.moveGui.edgeSnap
        assertEquals(8f, snap(9, "8.0"))
        assertEquals(1.5f, snap(9, "1.5"))
        assertEquals(1f, snap(10, "1.0"))
    }

    // v10 -> v11: a merged card's master is on when any part was on (parts unchanged); with no part
    // on, the master is off and the parts take their new defaults. Existing files skip onboarding.
    @Test
    fun mergedCardsGetMasters() {
        fun decode(general: String, nucleus: String = "{}") =
            ConfigCodec.decode("""{"configVersion":10,"general":$general,"mining":{"nucleus":$nucleus}}""").config
        val anyOn = decode(
            """{"hideRecipeBook":false,"hideOffhandSlot":true,"hideCraftingGrid":false,"hideEffectsHud":false,"hideEffectsInventory":false,""" +
                """"hideImplosionParticles":false,"hideImplosionExplosionSound":true,"hideImplosionTeleportSound":false}""",
            """{"compactFound":false,"compactLoot":false,"hideAlreadyObtained":false,"hideBlocksInWay":true}""",
        )
        assertEquals(true, anyOn.general.cleanUpInventory)
        assertEquals(listOf(false, true, false), listOf(anyOn.general.hideRecipeBook, anyOn.general.hideOffhandSlot, anyOn.general.hideCraftingGrid))
        assertEquals(false, anyOn.general.hideEffects)
        assertEquals(listOf(true, true), listOf(anyOn.general.hideEffectsHud, anyOn.general.hideEffectsInventory))
        assertEquals(true, anyOn.general.quietImplosion)
        assertEquals(false, anyOn.general.hideImplosionParticles)
        assertEquals(true, anyOn.mining.nucleus.compactChat)
        assertEquals(listOf(false, false, false, true), anyOn.mining.nucleus.let { listOf(it.compactFound, it.compactLoot, it.hideAlreadyObtained, it.hideBlocksInWay) })
        assertEquals(true, anyOn.menu.onboardingDone)

        val noneOn = decode("""{"hideRecipeBook":false}""", """{"compactFound":false}""")
        assertEquals(false, noneOn.general.cleanUpInventory)
        assertEquals(true, noneOn.general.hideRecipeBook)
        assertEquals(false, noneOn.mining.nucleus.compactChat)
        assertEquals(listOf(true, true, false, false), noneOn.mining.nucleus.let { listOf(it.compactFound, it.compactLoot, it.hideAlreadyObtained, it.hideBlocksInWay) })

        // A v11 file keeps whatever it stores, and a fresh config asks the first-launch question.
        val v11 = ConfigCodec.decode("""{"configVersion":11,"general":{"cleanUpInventory":false,"hideRecipeBook":false}}""").config
        assertEquals(false, v11.general.hideRecipeBook)
        assertEquals(false, v11.menu.onboardingDone)
        assertEquals(false, SoulConfig().menu.onboardingDone)

        val real = ConfigCodec.decode(fixtures.first { it.name.startsWith("v2-soul-preset") }.readText()).config
        assertEquals(true, real.general.cleanUpInventory)
        assertEquals(true, real.general.quietImplosion)
        assertEquals(true, real.mining.nucleus.compactChat)
    }

    // v11 -> v12: the retired Hyperion highlight leaves the file; neighbours stay.
    @Test
    fun hyperionHighlightIsDropped() {
        val old = """{"configVersion":11,"general":{"highlightHyperion":true,"highlightHyperionColor":{"argb":-1},"hideStuckDianaMobs":true}}"""
        val migrated = ConfigMigrations.migrate(ConfigCodec.json.parseToJsonElement(old).jsonObject, 11)
        assertEquals(setOf("hideStuckDianaMobs"), migrated.getValue("general").jsonObject.keys)
        assertEquals(true, ConfigCodec.decode(old).config.general.hideStuckDianaMobs)
    }

    // v16 -> v17: the card-wide override became per device; a stored value is copied to all three.
    @Test
    fun deviceOverrideBecomesPerDevice() {
        val decoded = ConfigCodec.decode(fixtures.first { it.name.startsWith("v16-device-override") }.readText())
        val d = decoded.config.dungeons.devices
        assertEquals("sneaking", d.simonSaysOverride)
        assertEquals("sneaking", d.lightsOverride)
        assertEquals("sneaking", d.arrowAlignOverride)
        assertEquals(true, d.enabled)
        val unset = ConfigCodec.decode("""{"configVersion":16,"dungeons":{"devices":{"enabled":true}}}""").config.dungeons.devices
        assertEquals("never", unset.lightsOverride)
    }

    @Test
    fun chestSearchMigrationRetiresClearBindingWithoutChangingAnyStoredSearchKey() {
        for (key in listOf("key.keyboard.i", "key.keyboard.k", "key.keyboard.f8", "key.keyboard.right.control+key.mouse.4", "")) {
            val old = """{"configVersion":18,"islandChests":{"enabled":true,"shareWithCoop":true,"searchKey":"$key","clearKey":"key.keyboard.left.alt+key.keyboard.backspace"},"commandBindings":[{"command":"soul chests","key":"key.keyboard.j"}]}"""
            val decoded = ConfigCodec.decode(old)
            assertEquals(18, decoded.fileVersion)
            assertTrue(decoded.migrated) // ConfigManager backs the original file up before rewriting.
            assertEquals(emptyList(), decoded.notes)
            assertEquals(IslandChestsConfig(enabled = true, shareWithCoop = true, searchKey = key), decoded.config.islandChests)
            assertEquals(listOf(CommandBinding("soul chests", "key.keyboard.j")), decoded.config.commandBindings)
            val migrated = ConfigMigrations.migrate(ConfigCodec.json.parseToJsonElement(old).jsonObject, 18)
            assertTrue("clearKey" !in migrated.getValue("islandChests").jsonObject)
            val written = ConfigCodec.json.parseToJsonElement(ConfigCodec.encode(decoded.config)).jsonObject
            assertTrue("clearKey" !in written.getValue("islandChests").jsonObject)
            assertEquals(key, ConfigCodec.decode(written.toString()).config.islandChests.searchKey)
        }
    }

    @Test
    fun storedChestSharingOptOutSurvivesWithoutMigration() {
        val old = """{"configVersion":21,"islandChests":{"enabled":true,"shareWithCoop":false,"searchKey":"key.keyboard.f8"}}"""
        val decoded = ConfigCodec.decode(old)
        assertEquals(false, decoded.migrated)
        assertEquals(IslandChestsConfig(enabled = true, shareWithCoop = false, searchKey = "key.keyboard.f8"), decoded.config.islandChests)
        assertEquals(decoded.config.islandChests, ConfigCodec.decode(ConfigCodec.encode(decoded.config)).config.islandChests)
    }

    @Test
    fun chestBindingRemovalToleratesAbsentAndMalformedSections() {
        for (section in listOf(null, "null", "42", """{"clearKey":null}""", """{"searchKey":null,"clearKey":{"unexpected":true}}""")) {
            val old = ConfigCodec.json.parseToJsonElement("""{"configVersion":18${section?.let { ",\"islandChests\":$it" }.orEmpty()}}""").jsonObject
            val migrated = ConfigMigrations.migrate(old, 18)
            val originalSection = old["islandChests"]
            if (originalSection is JsonObject) {
                assertEquals(originalSection - "clearKey", migrated.getValue("islandChests").jsonObject)
            } else {
                assertEquals(originalSection, migrated["islandChests"])
            }
        }
    }

    // v19 -> v20: Magic Find's on/off becomes Combat Wisdom's; the Combat Exp row is gone.
    @Test
    fun comboMagicFindSwitchBecomesCombatWisdom() {
        for (shown in listOf(true, false)) {
            val old = """{"configVersion":19,"combo":{"enabled":true,"showMagicFind":$shown,"showCombatExp":false,"showCoins":false}}"""
            val decoded = ConfigCodec.decode(old)
            assertEquals(emptyList(), decoded.notes)
            assertEquals(shown, decoded.config.combo.showCombatWisdom)
            assertEquals(false, decoded.config.combo.showCoins)
            assertTrue(decoded.config.combo.enabled)
            val written = ConfigCodec.json.parseToJsonElement(ConfigCodec.encode(decoded.config)).jsonObject.getValue("combo").jsonObject
            assertEquals(null, written["showMagicFind"])
            assertEquals(null, written["showCombatExp"])
        }
        // An absent switch keeps the default; an already-new key is not overwritten; odd shapes pass through.
        assertEquals(SoulConfig().combo.showCombatWisdom, ConfigCodec.decode("""{"configVersion":19,"combo":{"enabled":true}}""").config.combo.showCombatWisdom)
        val both = ConfigMigrations.migrate(ConfigCodec.json.parseToJsonElement("""{"combo":{"showMagicFind":true,"showCombatWisdom":false}}""").jsonObject, 19)
        assertEquals(JsonPrimitive(false), both.getValue("combo").jsonObject["showCombatWisdom"])
        for (odd in listOf("null", "42", "[]")) {
            val root = ConfigCodec.json.parseToJsonElement("""{"combo":$odd}""").jsonObject
            assertEquals(root.getValue("combo"), ConfigMigrations.migrate(root, 19).getValue("combo"))
        }
        val fixture = ConfigCodec.decode(fixtures.first { it.name.startsWith("v20-combo-wisdom") }.readText())
        assertEquals(false, fixture.config.combo.showCombatWisdom)
        val v19 = ConfigCodec.decode(fixtures.first { it.name.startsWith("v19-") }.readText())
        assertEquals(true, v19.config.combo.showCombatWisdom)
    }

    @Test
    fun rubixLabelsFlipOffOnce() {
        val stored = ConfigCodec.json.parseToJsonElement("""{"dungeons":{"terminals":{"enabled":true,"rubixLabels":true,"order":false}}}""").jsonObject
        val terminals = ConfigMigrations.migrate(stored, 20).getValue("dungeons").jsonObject.getValue("terminals").jsonObject
        assertEquals(JsonPrimitive(false), terminals.getValue("rubixLabels"))
        assertEquals(JsonPrimitive(true), terminals.getValue("enabled"))
        assertEquals(JsonPrimitive(false), terminals.getValue("order"))
        val noSection = ConfigCodec.json.parseToJsonElement("""{"dungeons":{"terminals":{"enabled":true}}}""").jsonObject
        assertEquals(noSection, ConfigMigrations.migrate(noSection, 20).filterKeys { it != ConfigCodec.VERSION_KEY }.let { JsonObject(it) })
        // A player who turns the labels on again at the current version keeps them.
        val on = ConfigCodec.json.parseToJsonElement("""{"configVersion":21,"dungeons":{"terminals":{"rubixLabels":true}}}""").toString()
        assertEquals(true, ConfigCodec.decode(on).config.dungeons.terminals.rubixLabels)
        val fixture = ConfigCodec.decode(fixtures.first { it.name.startsWith("v21-rubix-labels-off") }.readText())
        assertEquals(false, fixture.config.dungeons.terminals.rubixLabels)
    }

    @Test
    fun unreadableFileResetsWithNote() {
        val decoded = ConfigCodec.decode("{ not json")
        assertEquals(SoulConfig(), decoded.config)
        assertTrue(decoded.notes.isNotEmpty())
    }

    @Test
    fun savedFileCarriesCurrentVersion() {
        val written = ConfigCodec.json.parseToJsonElement(ConfigCodec.encode(SoulConfig())).jsonObject
        assertEquals(JsonPrimitive(ConfigMigrations.CURRENT), written[ConfigCodec.VERSION_KEY])
        assertEquals(ConfigMigrations.CURRENT, ConfigCodec.decode(written.toString()).fileVersion)
    }

    // Array elements count by index, so an entry growing a defaulted field still keeps its values.
    private fun leaves(e: JsonElement, path: List<String> = emptyList()): List<Pair<List<String>, JsonElement>> =
        if (e is JsonObject && e.isNotEmpty()) {
            e.flatMap { (k, v) -> leaves(v, path + k) }
        } else if (e is JsonArray && e.isNotEmpty()) {
            e.withIndex().flatMap { (i, v) -> leaves(v, path + i.toString()) }
        } else {
            listOf(path to e)
        }

    private fun at(e: JsonElement, path: List<String>): JsonElement? =
        path.fold(e as JsonElement?) { cur, k ->
            if (cur is JsonArray) k.toIntOrNull()?.let { cur.getOrNull(it) } else (cur as? JsonObject)?.get(k)
        }

    // v17 -> v18: map class colors that still equal the old defaults take the leap palette; custom ones stay.
    @Test
    fun mapClassColorsTakeLeapPaletteOnlyFromOldDefaults() {
        val file = fixtures.first { it.name.startsWith("v17-map-class-colors") }
        val c = ConfigCodec.decode(file.readText()).config.dungeons.colors
        assertEquals(0xFF00FF00.toInt(), c.classArcher.argb) // custom
        assertEquals(com.soulreturns.features.dungeons.DungeonClass.BERSERK.color, c.classBerserk.argb)
        assertEquals(com.soulreturns.features.dungeons.DungeonClass.MAGE.color, c.classMage.argb)
        assertEquals(com.soulreturns.features.dungeons.DungeonClass.HEALER.color, c.classHealer.argb)
        assertEquals(com.soulreturns.features.dungeons.DungeonClass.TANK.color, c.classTank.argb)
        val old = ConfigCodec.decode(fixtures.first { it.name.startsWith("v16-") }.readText()).config.dungeons.colors
        assertEquals(com.soulreturns.features.dungeons.DungeonClass.ARCHER.color, old.classArcher.argb)
    }
}
