package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.HotmEffectValue
import com.soulreturns.data.hypixel.HotmProgress
import com.soulreturns.data.hypixel.HotmTree
import com.soulreturns.data.hypixel.HotmTreeSnapshot
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.hotm
import com.soulreturns.features.profileviewer.gui.sections.mining.HotmLore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Heart of the Mountain math over the served tree (`fragments/hotm-tree.json`, a copy of SkyBackend's
 * `data/hotm/tree.json`). Expected numbers are what the mock's `hotm.js` computes (`costOf`, `tierFromXp`,
 * `tokensTotal`, each perk's `effect`); Mole 153 → 154 is also the captured in-game cost.
 */
class HotmTreeTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun resource(path: String) = javaClass.getResource(path)!!.readText()

    private val tree = HotmTree(json.decodeFromString(HotmTreeSnapshot.serializer(), resource("/profileviewer/fragments/hotm-tree.json")))

    private fun node(id: String) = tree.node(id)!!

    private fun cost(id: String, level: Int) = tree.costToReach(node(id), level)

    private val joe: JsonObject = json.parseToJsonElement(resource("/profileviewer/fragments/mining-joe.json")).jsonObject

    private fun joeProgress(): HotmProgress {
        val h = SkyblockProfileMember(joe).hotm!!
        return tree.resolve(h.levels, h.disabled, h.ability, h.xp, h.tokensSpent)
    }

    @Test
    fun `the served tree decodes with every node placed`() {
        assertTrue(tree.available)
        assertEquals(46, tree.nodes.size)
        assertEquals(10, tree.maxTier)
        assertEquals(46, tree.nodes.map { it.tier to it.col }.toSet().size)
        assertEquals(listOf("mithril", "gemstone", "glacite"), tree.powders.map { it.id })
        assertEquals("MIT", tree.snapshot.source?.license)
    }

    @Test
    fun `perk costs follow the formula, captured exponents included`() {
        assertEquals(33L, cost("quick_forge", 2))
        assertEquals(17025L, cost("quick_forge", 20)) // exponent 3.2 from the captures (NEU: 4)
        assertEquals(10L, cost("mole", 2))
        assertEquals(59205L, cost("mole", 154)) // 2.17883; the menu showed 59,205 Gemstone Powder for 153 → 154
        assertEquals(29L, cost("subterranean_fisher", 2))
        assertEquals(89381L, cost("subterranean_fisher", 40)) // 3.07 (NEU: 3.7)
        assertEquals(59049L, cost("mining_master", 2)) // (L + 7) ^ 5
        assertEquals(1419857L, cost("mining_master", 10))
        assertEquals(27L, cost("mining_speed", 2))
        assertEquals(132651L, cost("mining_speed", 50))
        assertEquals(28L, cost("mining_fortune", 2))
        assertEquals(162643L, cost("efficient_miner", 100))
        assertEquals(194481L, cost("gem_lover", 20))
        assertEquals(431500L, cost("crystalline", 50))
        assertEquals(81393L, cost("gifts_from_the_departed", 100))
        assertNull(cost("mining_speed", 1), "level 1 costs a token")
        assertNull(cost("mining_speed", 51), "above max")
        assertNull(cost("pickobulus", 2), "abilities have one level")
    }

    @Test
    fun `core of the mountain costs and powders, 7 to 8 in Glacite`() {
        val core = node("core_of_the_mountain")
        assertNull(tree.costToReach(core, 1))
        assertEquals(50000L, tree.costToReach(core, 2))
        assertEquals("mithril", tree.powderToReach(core, 4)?.id)
        assertEquals("gemstone", tree.powderToReach(core, 5)?.id)
        assertEquals(750000L, tree.costToReach(core, 8))
        assertEquals("glacite", tree.powderToReach(core, 8)?.id)
        assertEquals(1250000L, tree.costToReach(core, 10))
    }

    @Test
    fun `tier from HOTM XP`() {
        val xp = listOf(0.0, 2999.0, 3000.0, 12000.0, 846999.0, 847000.0, 1246999.0, 1247000.0, 5e6)
        assertEquals(listOf(1, 1, 2, 3, 8, 9, 9, 10, 10), xp.map(tree::tierOf))
        assertEquals(1247000L, tree.xpForTier(10))
        assertEquals(150000L, tree.xpToNext(6))
        assertNull(tree.xpToNext(10))
    }

    @Test
    fun `token accounting per tier and core level`() {
        val cases = listOf(Triple(10, 10, 25), Triple(9, 7, 21), Triple(6, 3, 12), Triple(1, 0, 1), Triple(5, 1, 10), Triple(7, 0, 14))
        for ((tier, core, tokens) in cases) assertEquals(tokens, tree.tokensAt(tier, core), "tier $tier core $core")
        assertEquals(listOf(0, 0, 2, 3, 4, 5, 6, 7, 7, 7, 7), (0..10).map(tree::forgeSlots))
    }

    @Test
    fun `effects read like the menu`() {
        fun effect(id: String, level: Int, tier: Int = 10) = tree.effect(node(id), level, tier)
        assertEquals("Grants §a+317.34 §6Mining Spread §7when mining Hard Stone.", effect("mole", 153))
        assertEquals("Grants §a+50.00 §6Mining Spread §7when mining Hard Stone.", effect("mole", 1))
        assertEquals("Decreases the time to forge items by §a30%§7.", effect("quick_forge", 20))
        assertEquals("Decreases the time to forge items by §a19.5%§7.", effect("quick_forge", 19))
        assertEquals("When mining Mithril Ore, you have a §a7% §7chance to turn it into Titanium Ore.", effect("titanium_insanium", 50))
        assertEquals("When mining Mithril Ore, you have a §a2.3% §7chance to turn it into Titanium Ore.", effect("titanium_insanium", 3))
        assertEquals("Increases §cStrength§7, §9Crit Chance§7, §9Crit Damage§7, §aDefense §7and §cHealth §7by §a5% §7on Mining Islands.", effect("lonesome_miner", 1))
        assertEquals("Increases your chance to find a Glacite Mineshaft in the Glacite Tunnels by §a1.50%§7.", effect("surveyor", 2))
        assertEquals("Your first daily commission on each Mining Island grants §a+3,500 §7of that island's Powder.", effect("daily_grind", 1, tier = 7))
        assertEquals("Grants §a+1,000 §6Mining Speed§7.", effect("mining_speed", 50))
        assertEquals("Grants §a+6.5 §bFishing Speed §7and §a+1.3 §3Sea Creature Chance §7on Mining Islands.", effect("subterranean_fisher", 3))
        assertEquals("Grants §a+36% §7chance to find treasure chests, and chests need §a1 §7lock fewer.", effect("great_explorer", 5))
        assertEquals("Grants §a+0.3 §5Pristine§7.", effect("mining_master", 3))
        assertEquals("Grants §a+5.7 §3Mining Wisdom§7.", effect("seasoned_mineman", 7))
        assertEquals("", effect("core_of_the_mountain", 3))
    }

    @Test
    fun `effect value formatting`() {
        assertEquals("1,500", HotmTree.format(HotmEffectValue(per = 500.0, of = "tier", group = true), 1, 3, 1))
        assertEquals("2", HotmTree.format(HotmEffectValue(base = 1.0, per = 0.5, decimals = 1, trim = true), 2, 1, 50))
        assertEquals("2.0", HotmTree.format(HotmEffectValue(base = 1.0, per = 0.5, decimals = 1), 2, 1, 50))
        assertEquals("1.50", HotmTree.format(HotmEffectValue(per = 0.75, decimals = 2, trim = true), 2, 1, 20))
        assertEquals("30", HotmTree.format(HotmEffectValue(base = 10.0, per = 0.5, decimals = 1, trim = true, atMax = 30.0), 20, 1, 20))
    }

    @Test
    fun `the member's tree resolves from skill_tree`() {
        val h = SkyblockProfileMember(joe).hotm!!
        assertEquals(12, h.levels.size, "toggle_ keys are not levels")
        assertEquals(setOf("professional"), h.disabled)
        assertEquals("pickobulus", h.ability)
        assertEquals(11, h.tokensSpent)

        val p = joeProgress()
        assertEquals(6, p.tier)
        assertEquals(3, p.coreLevel)
        assertEquals(12, p.tokensEarned)
        assertEquals(11, p.tokensSpent)
        assertEquals(1, p.tokensLeft)
        assertEquals(53000.0, p.xpIntoTier)
        assertEquals(150000L, p.xpToNext)
        assertEquals(12, p.bought)

        val speed = p.node(node("mining_speed"))
        assertTrue(speed.maxed && speed.open && !speed.disabled)
        val mole = p.node(node("mole"))
        assertEquals(40, mole.level)
        assertFalse(mole.maxed)
        assertTrue(p.node(node("professional")).disabled)
        assertTrue(p.node(node("pickobulus")).selected)
        assertFalse(p.node(node("mining_speed_boost")).selected)
        assertEquals("precision_mining", p.node(node("mining_speed_boost")).missing?.id)
        assertNull(p.node(node("precision_mining")).missing, "its prerequisite Mining Fortune is bought")
        assertEquals("mining_speed_boost", p.node(node("luck_of_the_cave")).missing?.id)
        assertFalse(p.node(node("speedy_mineman")).open, "tier 7 above a tier 6 player")
        assertTrue(p.edgeOn(node("mining_fortune")))
        assertFalse(p.edgeOn(node("precision_mining")))
    }

    @Test
    fun `tokens spent falls back to the bought nodes`() {
        val h = SkyblockProfileMember(joe).hotm!!
        assertEquals(11, tree.resolve(h.levels, h.disabled, h.ability, h.xp, null).tokensSpent)
    }

    @Test
    fun `no skill_tree means no tree, levels written as decimals still count`() {
        assertNull(SkyblockProfileMember(JsonObject(emptyMap())).hotm)
        val raw = json.parseToJsonElement("""{"skill_tree":{"nodes":{"mining":{"mole":40.0,"toggle_mole":true}}}}""").jsonObject
        val h = SkyblockProfileMember(raw).hotm!!
        assertEquals(mapOf("mole" to 40), h.levels)
        assertEquals(0.0, h.xp)
        assertNull(h.tokensSpent)
        assertNull(h.ability)
    }

    @Test
    fun `node items carry the menu's icon, name color, lore and count`() {
        val p = joeProgress()
        fun item(id: String) = HotmLore.item(tree, p, p.node(node(id)))

        val mole = item("mole")
        assertEquals("§eMole", mole.name)
        assertEquals(HotmLore.EMERALD, mole.icon)
        assertEquals(40, mole.count)
        assertEquals("§7Level 40§8/200", mole.lore[0])
        assertTrue("§a=====[ §a§lUPGRADE §a] =====" in mole.lore)
        assertTrue("§7Level 41§8/200" in mole.lore)
        assertEquals("§d${String.format(java.util.Locale.ROOT, "%,d", cost("mole", 41))} Gemstone Powder", mole.lore.last())

        val speed = item("mining_speed")
        assertEquals("§aMining Speed", speed.name)
        assertEquals(HotmLore.DIAMOND, speed.icon)
        assertEquals("§7Level 50", speed.lore[0])
        assertEquals(1, speed.count)
        assertFalse("§7Cost" in speed.lore)

        val pick = item("pickobulus")
        assertEquals(HotmLore.EMERALD_BLOCK, pick.icon)
        assertTrue(pick.glint)
        assertEquals("§6Pickaxe Ability", pick.lore[0])
        assertTrue("§7§8Cooldown: §a50s" in pick.lore, pick.lore.toString())
        assertEquals("§a§lSELECTED", pick.lore.last())

        assertEquals("§c§lDISABLED", item("professional").lore.last())

        val boost = item("mining_speed_boost")
        assertEquals("§cMining Speed Boost", boost.name)
        assertEquals(HotmLore.COAL_BLOCK, boost.icon)
        assertEquals(listOf("§7Cost", "§51 Token of the Mountain", "", "§cRequires Precision Mining"), boost.lore.takeLast(4))

        assertEquals(HotmLore.COAL, item("precision_mining").icon)
        assertEquals("§8Not unlocked", item("precision_mining").lore.last())
        assertEquals("§cRequires Tier 7", item("speedy_mineman").lore.last())

        val core = item("core_of_the_mountain")
        assertEquals(HotmLore.CORE, core.icon)
        assertEquals(3, core.count)
        assertEquals("§7Level 3§8/10", core.lore[0])
        assertTrue("  §8+§a1 Commission Slot" in core.lore)
        assertEquals(listOf("§7Level 4§8/10", "  §8+§21 Base Mithril Powder §8when mining Mithril", "", "§7Cost", "§2200,000 Mithril Powder"), core.lore.takeLast(5))
    }

    @Test
    fun `the upgrade cost line matches the captured menu`() {
        val p = tree.resolve(mapOf("mole" to 153), emptySet(), null, 1.0e6, null)
        val lore = HotmLore.item(tree, p, p.node(node("mole"))).lore
        assertEquals("§7Level 154§8/200", lore[lore.indexOf("§a=====[ §a§lUPGRADE §a] =====") + 1])
        assertEquals("§d59,205 Gemstone Powder", lore.last())
    }

    @Test
    fun `lore wraps at 38 visible characters and carries the color`() {
        assertEquals(
            listOf("§7Grants §a+317.34 §6Mining Spread §7when", "§7mining Hard Stone."),
            HotmLore.wrap("Grants §a+317.34 §6Mining Spread §7when mining Hard Stone."),
        )
        assertEquals(listOf("§7Throw your pickaxe.", "§7§8Cooldown: §a50s"), HotmLore.wrap("Throw your pickaxe.\n§8Cooldown: §a50s"))
        assertEquals(listOf("§7aaaa §ebbbb", "§ecccc"), HotmLore.wrap("aaaa §ebbbb cccc", 10))
    }

    @Test
    fun `tier label tooltips`() {
        val p = joeProgress()
        assertEquals(listOf("Tier 7", "+3 Tokens of the Mountain", "+1 Forge Slot", "+Golden Mining Helix Emblem", "+130 SkyBlock XP", "Progress: 53,000 / 150,000 XP"), HotmLore.tierTip(tree, p, 7))
        assertEquals("Unlocked", HotmLore.tierTip(tree, p, 6).last())
        assertEquals("Locked", HotmLore.tierTip(tree, p, 8).last())
    }
}
