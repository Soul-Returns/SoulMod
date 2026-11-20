package com.soulreturns.features.profileviewer

import net.minecraft.core.component.DataComponentMap
import net.minecraft.world.item.Items
import com.soulreturns.data.hypixel.HotmTree
import com.soulreturns.data.hypixel.HotmTreeSnapshot
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.MiningSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.SharedConstants
import net.minecraft.server.Bootstrap
import net.minecraft.world.item.ItemStack
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Mining section built from the shared fixtures with the mining fragments merged into the viewed
 * member (`fragments/mining-<fixture>.json`), against the served tree (`fragments/hotm-tree.json`) or none.
 */
class MiningSectionTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @BeforeTest
    fun boot() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        // A real game binds item default components on the data pack load; a bare bootstrap leaves them unbound.
        for (item in listOf(Items.DIAMOND, Items.EMERALD, Items.COAL, Items.EMERALD_BLOCK, Items.COAL_BLOCK, Items.REDSTONE_BLOCK)) {
            val holder = item.builtInRegistryHolder()
            if (!holder.areComponentsBound()) holder.bindComponents(DataComponentMap.EMPTY)
        }
    }

    private class Painted(val text: String, val x: Int, val y: Int)

    /** 5 px per character (6 bold), like SectionBuildTest's recorder, keeping where each text was painted. */
    private class Recorder(override val state: ViewState = ViewState()) : KitCanvas {
        val texts = ArrayList<Painted>()
        val items = ArrayList<ItemStack>()
        override val viewTop = 0
        override val viewBottom = Int.MAX_VALUE
        override val nowMs = NOW
        override fun width(text: String, size: Float, bold: Boolean) = text.length * if (bold) 6 else 5
        override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean) = text.length * 6
        override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {}
        override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
            texts += Painted(text, x, top)
        }
        override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
            texts += Painted(text, cx, cy)
        }
        override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {}
        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {
            items += stack
        }
        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {}
        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int) = false
        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) {}
        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {}
        override fun itemTooltip(stack: ItemStack) {}
        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {}
        override fun openSection(id: String) {}

        fun all(): String = texts.joinToString(" ") { it.text }.replace(Regex("§."), "").replace(Regex("\\s+"), " ")

        fun first(text: String): Painted = texts.first { it.text == text }
    }

    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    private val tree = HotmTree(json.decodeFromString(HotmTreeSnapshot.serializer(), resource("/profileviewer/fragments/hotm-tree.json")))

    /** [name]'s fixture with `fragments/mining-<name>.json` merged into the selected profile's member. */
    private fun fixture(name: String): JsonObject {
        val f = json.parseToJsonElement(resource("/profileviewer/fixtures/$name.json")).jsonObject
        val fragment = json.parseToJsonElement(resource("/profileviewer/fragments/mining-$name.json")).jsonObject
        val uuid = f["uuid"]!!.jsonPrimitive.content
        val wrap = f["profiles"]!!.jsonObject
        val profiles = JsonArray(
            wrap["profiles"]!!.jsonArray.map { p ->
                val profile = p.jsonObject
                if (profile["selected"]?.jsonPrimitive?.booleanOrNull != true) return@map profile
                val members = profile["members"]!!.jsonObject
                JsonObject(profile + ("members" to JsonObject(members + (uuid to JsonObject(members[uuid]!!.jsonObject + fragment)))))
            },
        )
        return SpvFixtures.resolveTimes(JsonObject(f + ("profiles" to JsonObject(wrap + ("profiles" to profiles)))), NOW).jsonObject
    }

    private fun context(name: String): ProfileContext {
        val f = fixture(name)
        val identity = PlayerIdentity(f["uuid"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content)
        val profiles = SkyblockProfiles.from(f["profiles"]!!.jsonObject)
        val profile = profiles.pick(null)!!
        val soul = (f["soulStats"] as? JsonObject)?.let { spec ->
            val status = spec["status"]!!.jsonPrimitive.int
            if (status == 200) {
                SoulStatsAnswer(json.decodeFromJsonElement(SoulStatsEnvelope.serializer(), spec["body"]!!), SoulStatsClient.CODE_OK)
            } else {
                SoulStatsAnswer(null, if (status == 403) SoulStatsClient.CODE_NOT_SHARED else SoulStatsClient.CODE_NOT_A_USER)
            }
        }
        return ProfileContext(
            identity, profiles, profile, profile.member(identity.uuidUndashed)!!, isSelf = false,
            caps = null, capsLoaded = true, soulAnswer = soul, nowMs = NOW, opener = {}, looker = {},
        )
    }

    private fun render(fixture: String, tree: HotmTree = this.tree, w: Int = 969): Pair<Recorder, Int> {
        val c = Recorder()
        val ui = Ui(MiningSection.id, c.state)
        MiningSection.build(context(fixture), ui, tree)
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(c, w)
        root.paint(c, 0, 0, w, h)
        return c to h
    }

    @Test
    fun `an endgame tree with its summary`() {
        val (c, _) = render("sam")
        val text = c.all()
        for (s in listOf(
            "Heart of the Mountain", "26 of 46 nodes", "Tier 10", "max tier", "25 spent · 0 to spend", "Level 10", "1,402,551",
            "Pickobulus", "Selected pickaxe ability", "Mithril", "431,207", "12.3M total · 11.8M spent", "Gemstone", "Glacite",
            "Maxed", "Leveling", "Not unlocked", "Ability",
        )) assertTrue(s in text, "missing '$s' in: $text")
        for (tier in 1..10) assertTrue(c.texts.any { it.text == tier.toString() }, "tier label $tier")
        assertTrue(c.texts.any { it.text == "182" }, "Mole's level count")
        assertTrue(c.texts.any { it.text == "10" && it.x > 30 }, "the Core's level count")
        // 46 nodes + the selected ability + 4 legend icons
        assertEquals(51, c.items.size)
    }

    @Test
    fun `glacite, forge, nucleus and mineshaft cards`() {
        val text = render("sam").first.all()
        for (s in listOf(
            "Glacite", "Mineshafts entered", "412", "Lapis corpses looted", "380", "Vanguard corpses looted", "Fossils donated", "3", "Fossil dust", "23,411",
            "Forge", "times from the start time Hypixel reports", "Refined Mithril", "Mithril Plate", "Empty",
            "Crystal Nucleus", "203 crystals placed", "Jade", "placed", "Amber", "found", "Sapphire", "missing", "Topaz",
            "Mineshaft odds",
        )) assertTrue(s in text, "missing '$s' in: $text")
        assertTrue("Jasper" !in text, "only the five Nucleus crystals")
        assertTrue(text.indexOf("Lapis corpses") < text.indexOf("Umber corpses") && text.indexOf("Umber corpses") < text.indexOf("Tungsten corpses"))
        assertEquals(4, Regex("Empty").findAll(text).count(), "7 slots at tier 10, three busy")
    }

    @Test
    fun `mid tier with tokens to spend and a disabled perk`() {
        val text = render("joe").first.all()
        for (s in listOf("Tier 6", "53,000 / 150,000 XP to Tier 7", "11 spent · 1 to spend", "Level 3", "12 of 46 nodes", "Fisher_Joe doesn't use Soul")) {
            assertTrue(s in text, "missing '$s' in: $text")
        }
        assertEquals(6, Regex("Empty").findAll(text).count(), "6 Forge slots at tier 6, none busy")
    }

    @Test
    fun `no skill_tree in the response`() {
        val text = render("builder").first.all()
        for (s in listOf(
            "No Heart of the Mountain in the response", "Hypixel sent no skill_tree for Builder42 on this profile.",
            "Nothing from the Glacite Tunnels", "Forge not unlocked", "No Crystal Hollows data", "Builder42 keeps their Soul stats private",
        )) assertTrue(s in text, "missing '$s' in: $text")
    }

    @Test
    fun `without the tree table the levels are a plain list`() {
        val (c, _) = render("sam", tree = HotmTree(HotmTreeSnapshot()))
        val text = c.all()
        for (s in listOf(
            "Tree layout unavailable", "HOTM XP", "1,402,551", "Tokens spent", "25", "Core of the Mountain", "Level 10", "Selected ability", "Pickobulus",
            "Mole", "182", "Warm Heart", "50 off", "Mithril", "431,207", "Glacite",
        )) assertTrue(s in text, "missing '$s' in: $text")
        assertTrue(c.items.isEmpty(), "no tree, no node items")
        assertEquals(0, Regex("Empty").findAll(text).count(), "without the tree the Forge lists up to the last busy slot (1 to 3, all busy)")
    }

    @Test
    fun `summary beside the tree when wide, below it when narrow`() {
        val (wide, wideH) = render("sam", w = 969)
        val (narrow, narrowH) = render("sam", w = 476)
        assertTrue(wide.first("Tier 10").x > HOTM_TREE_W, "beside the tree at 969")
        assertTrue(narrow.first("Tier 10").y > narrow.first("1").y, "below tier 1 at 476")
        assertTrue(narrowH > wideH)
        assertEquals(wideH, render("sam", w = 969).second, "deterministic")
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        /** Tree label column + 7 columns on a 28 px pitch, as the mock's `.htree`. */
        const val HOTM_TREE_W = 30 + 7 * 28 - 8
    }
}
