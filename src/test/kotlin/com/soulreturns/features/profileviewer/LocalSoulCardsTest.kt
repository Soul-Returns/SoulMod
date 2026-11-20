package com.soulreturns.features.profileviewer

import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.features.experiments.RngMeterStore
import com.soulreturns.features.fishing.FishingCounts
import com.soulreturns.features.mining.metaldetector.DetectorLootCounts
import com.soulreturns.features.mining.nucleus.NucleusCounts
import com.soulreturns.features.profileviewer.gui.LocalSoulCards
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.Ui
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.profileviewer.gui.sections.FishingSection
import com.soulreturns.features.profileviewer.gui.sections.SoulSection
import com.soulreturns.features.profit.dragon.DragonPartition
import com.soulreturns.features.profit.dragon.DragonProfitStats
import com.soulreturns.features.profit.dragon.DragonType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.world.item.ItemStack
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The ONLY YOU cards from a stubbed stats source: data, empty buckets, another profile's bucket, other players. */
class LocalSoulCardsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun restore() {
        LocalSoulCards.source = LocalSoulCards.Stored
    }

    private class Recorder(override val state: ViewState = ViewState()) : KitCanvas {
        val texts = ArrayList<String>()
        override val viewTop = 0
        override val viewBottom = Int.MAX_VALUE
        override val nowMs = NOW
        override fun width(text: String, size: Float, bold: Boolean) = text.length * if (bold) 6 else 5
        override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean) = text.length * 6
        override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {}
        override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
            texts += text
        }
        override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
            texts += text
        }
        override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {}
        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {}
        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {}
        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int) = false
        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) {}
        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {}
        override fun itemTooltip(stack: ItemStack) {}
        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {}
        override fun openSection(id: String) {}

        fun all(): String = texts.joinToString(" ").replace(Regex("§."), "").replace(Regex("\\s+"), " ")
    }

    /** A stubbed `stats.json`: buckets per profile name, empty everywhere else. */
    private class Fake(val profile: String, val rngTableRead: Boolean = true, val rng: RngMeterStore.SelectedDrop? = null) : LocalSoulCards.Source {
        var nucleus = NucleusCounts()
        var fishing = FishingCounts()
        var dragons = DragonProfitStats()
        var detector = DetectorLootCounts()

        override fun nucleus(profile: String) = if (profile == this.profile) nucleus else NucleusCounts()
        override fun fishing(profile: String) = if (profile == this.profile) fishing else FishingCounts()
        override fun dragons(profile: String) = if (profile == this.profile) dragons else DragonProfitStats()
        override fun detector(profile: String) = if (profile == this.profile) detector else DetectorLootCounts()
        override fun rngRead() = rngTableRead
        override fun rngSelected(profileId: String, profileName: String) = if (profileName == profile) rng else null
        override fun nucleusValue(name: String) = if (name == "Jaderald") 1_000_000L else 0L
        override fun dragonDropName(sourceId: String, itemId: String) = itemId.lowercase().replace('_', ' ')
        override fun dragonDropValue(itemId: String) = 0L
    }

    private fun filled(profile: String) = Fake(profile, rng = RngMeterStore.SelectedDrop("Jaderald", 0.0263, NOW - 120_000)).apply {
        nucleus = NucleusCounts(
            runs = 486,
            runTimes = listOf(260_000L, 300_000L, 425_000L, 430_000L, 500_000L),
            lastRunMs = 401_000,
            bestRunMs = 252_000,
            loot = mapOf("Divan Fragment" to 62L, "Jaderald" to 3L, "Gemstone Powder" to 90_000L),
        )
        fishing = FishingCounts(catches = 1000, doubleHooks = 120, cocoons = 37, catchesByCreature = mapOf("Sea Walker" to 600L, "Night Squid" to 400L))
        dragons = DragonProfitStats(
            summoned = DragonPartition(kills = mapOf(DragonType.SUPERIOR.name to 312L), eyes = mapOf(DragonType.SUPERIOR.name to 1204L)),
            lootshare = DragonPartition(kills = mapOf(DragonType.OLD.name to 1048L), drops = mapOf(DragonType.OLD.name to mapOf("OLD_FRAGMENT" to 412L))),
        )
        detector = DetectorLootCounts(treasures = 3812, sets = 10, setTimeMs = 600_000, lastSetMs = 41_000)
    }

    private fun context(self: Boolean, soul: SoulStatsAnswer? = SoulStatsAnswer(SoulStatsEnvelope(), SoulStatsClient.CODE_OK)): ProfileContext {
        val f = json.parseToJsonElement(javaClass.getResource("/profileviewer/fixtures/sam.json")!!.readText()).jsonObject
        val identity = PlayerIdentity(f["uuid"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content)
        val profiles = SkyblockProfiles.from(f["profiles"]!!.jsonObject)
        val profile = profiles.pick(null)!!
        return ProfileContext(
            identity, profiles, profile, profile.member(identity.uuidUndashed)!!, isSelf = self,
            caps = null, capsLoaded = true, soulAnswer = soul, nowMs = NOW, opener = {}, looker = {},
        )
    }

    private fun paint(sectionId: String, build: (Ui) -> Unit): Recorder {
        val c = Recorder()
        val ui = Ui(sectionId, c.state)
        build(ui)
        val root = StackNode(ui.nodes, KitStyle.GAP)
        val h = root.height(c, 969)
        root.paint(c, 0, 0, 969, h)
        return c
    }

    @Test
    fun `soul section shows all five cards with the viewed profile's data`() {
        val ctx = context(self = true)
        LocalSoulCards.source = filled(ctx.profile.cuteName)
        val text = paint(SoulSection.id) { SoulSection.build(ctx, it) }.all()
        for (t in listOf("Crystal Nucleus runs", "Fishing tracker", "Dragons", "Metal Detector", "Experimentation RNG meter", "ONLY YOU")) {
            assertTrue(t in text, "missing $t in $text")
        }
        assertTrue(text.indexOf("Crystal Nucleus runs") < text.indexOf("Fishing tracker"))
        assertTrue(text.indexOf("Fishing tracker") < text.indexOf("Dragons"))
        assertTrue(text.indexOf("Dragons") < text.indexOf("Metal Detector"))
        assertTrue(text.indexOf("Metal Detector") < text.indexOf("Experimentation RNG meter"))
        // Nucleus: runs, best, median of five, last, histogram axis, loot without powder (most valuable first).
        for (t in listOf("486", "4:12", "7:05", "6:41", "4 min", "9 min", "Jaderald", "Divan Fragment")) assertTrue(t in text, "missing $t")
        assertFalse("Gemstone Powder" in text)
        assertTrue(text.indexOf("Jaderald") < text.indexOf("Divan Fragment"))
        // Fishing: catches, double hooks with their share, cocoons, creature bars.
        for (t in listOf("1,000", "120 (12.0%)", "37", "Sea Walker", "600", "Night Squid")) assertTrue(t in text, "missing $t")
        // Dragons: summoned, lootshared, eyes, drops.
        for (t in listOf("312 Summoned", "1,048 Lootshared", "1,204 Summoning Eyes", "412 old fragment")) assertTrue(t in text, "missing $t")
        // Metal Detector: sets, average of timed sets, last set, treasures.
        for (t in listOf("10 Full sets", "1:00 Average set", "0:41 Last set", "3,812 Treasures")) assertTrue(t in text, "missing $t")
        // RNG meter: selected drop, base odds, when it was read.
        for (t in listOf("Jaderald Selected drop", "0.0263% Base odds", "2 min ago")) assertTrue(t in text, "missing $t")
        // The intro: its inline chip and the share toggle.
        assertTrue("Cards marked ONLY YOU never leave your computer" in text, text)
        assertTrue("Share my Soul stats" in text)
    }

    @Test
    fun `empty buckets show empty states, or nothing where the mock hides the card`() {
        val ctx = context(self = true)
        val fake = Fake(ctx.profile.cuteName, rngTableRead = false)
        fake.fishing = FishingCounts(catches = 5, catchesByCreature = mapOf("Squid" to 5L))
        LocalSoulCards.source = fake
        val text = paint(SoulSection.id) { SoulSection.build(ctx, it) }.all()
        for (t in listOf("No runs recorded yet", "No dragons recorded yet", "No treasures found yet", "Meter not read yet", "Squid")) {
            assertTrue(t in text, "missing $t in $text")
        }

        val ui = Ui("mining", ViewState())
        assertFalse(LocalSoulCards.nucleus(ui, ctx, empty = false))
        assertFalse(LocalSoulCards.detector(ui, ctx, empty = false))
        assertTrue(ui.nodes.isEmpty())
        val fishing = paint(FishingSection.id) { FishingSection.build(ctx, it) }.all()
        assertTrue("Fishing tracker" in fishing)
    }

    @Test
    fun `nothing recorded anywhere shows one empty state instead of five`() {
        val ctx = context(self = true)
        LocalSoulCards.source = Fake(ctx.profile.cuteName)
        val text = paint(SoulSection.id) { SoulSection.build(ctx, it) }.all()
        assertTrue("Nothing recorded yet" in text, text)
        assertFalse("Crystal Nucleus runs" in text)
        assertEquals(emptyList(), LocalSoulCards.overviewRows(ctx))
    }

    @Test
    fun `another profile's bucket is not the viewed one`() {
        val ctx = context(self = true)
        LocalSoulCards.source = filled("Some Other Profile")
        assertEquals(emptyList(), LocalSoulCards.overviewRows(ctx))
        val text = paint(SoulSection.id) { SoulSection.build(ctx, it) }.all()
        assertFalse("486" in text)
    }

    @Test
    fun `other players never get local cards`() {
        val ctx = context(self = false)
        LocalSoulCards.source = filled(ctx.profile.cuteName)
        val text = paint(SoulSection.id) { SoulSection.build(ctx, it) }.all()
        for (t in listOf("Crystal Nucleus runs", "Fishing tracker", "Metal Detector", "Experimentation RNG meter", "ONLY YOU")) {
            assertFalse(t in text, "$t shown for another player")
        }
        assertFalse("Fishing tracker" in paint(FishingSection.id) { FishingSection.build(ctx, it) }.all())
        assertEquals(emptyList(), LocalSoulCards.overviewRows(ctx))
    }

    @Test
    fun `overview rows match the mock`() {
        val ctx = context(self = true)
        LocalSoulCards.source = filled(ctx.profile.cuteName)
        assertEquals(
            listOf(
                "Crystal Nucleus" to "486 runs · best 4:12",
                "Sea creatures caught" to "1,000",
                "Dragons" to "312 summoned · 1,048 lootshared",
            ),
            LocalSoulCards.overviewRows(ctx),
        )
    }

    @Test
    fun `run histogram buckets by minute and folds slow outliers into the last bucket`() {
        val h = assertNotNull(LocalSoulCards.runHistogram(listOf(250_000L, 260_000L, 430_000L, 3_600_000L)))
        assertEquals(4, h.fromMin)
        assertEquals(14, h.toMin)
        assertTrue(h.overflow)
        assertEquals(listOf(2.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0), h.counts)
        assertEquals(null, LocalSoulCards.runHistogram(emptyList()))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
