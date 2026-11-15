package com.soulreturns.config.gui.menu

import com.soulreturns.config.ConfigMenuState
import com.soulreturns.config.SeenNew
import com.soulreturns.config.gui.ConfigRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The config menu's pure rules, with expected values from the mock (`mocks/config-menu/mock.js`:
 * `score()`, `results()`, `rightNow()`, `isNewId()` / `markSeen()`, `noteRecent()`).
 */
class ConfigMenuLogicTest {
    private val index by lazy { MenuSearch.index(ConfigRegistry.cards, ConfigRegistry.settingsTabs.filter { it.id != "dev" }, ConfigRegistry.optionSynonyms) }

    private fun search(q: String) = MenuSearch.results(index, q, emptyList())

    private fun group(q: String, title: String) = search(q).firstOrNull { it.title == title }?.items.orEmpty().map { it.name }

    // ---------------------------------------------------------------- palette

    @Test
    fun scoresFollowTheMock() {
        val sprint = index.first { it.kind == SearchItem.Kind.FEATURE && it.card?.id == "sprint" }
        // "toggle sprint": a word starts with the token (5) + the first synonym is the query (3).
        assertEquals(8.0, MenuSearch.score(sprint, listOf("sprint")))
        val implosion = index.first { it.kind == SearchItem.Kind.FEATURE && it.card?.id == "implosion" }
        // A synonym prefix (3.5) + the first synonym is the query (3).
        assertEquals(6.5, MenuSearch.score(implosion, listOf("hyperion")))
        // Every token must hit something.
        assertEquals(0.0, MenuSearch.score(sprint, listOf("sprint", "zzz")))
        // Advanced settings rank a little lower.
        val adv = index.first { it.kind == SearchItem.Kind.SETTING && it.name == "Toggle sprint underwater" }
        assertEquals(5.0 - 0.3, MenuSearch.score(adv, listOf("sprint")))
    }

    @Test
    fun rankingAndSynonyms() {
        assertEquals("Toggle sprint", group("sprint", "FEATURES").first())
        assertEquals(listOf("Quiet Implosion"), group("hyperion", "FEATURES").take(2))
        assertEquals("Price tooltips", group("prices", "FEATURES").first())
        assertTrue("Price tooltips" in group("lbin", "FEATURES"))
        assertTrue("Dark UI" in group("beta", "FEATURES"))
        assertTrue("UI Scale" in group("gui scale", "SETTINGS"))
        assertEquals("Settings › Shortcuts", group("keybinds", "PAGES").first())
        assertEquals(emptyList(), search("qqqzzz"))
        // Position rows are covered by their feature.
        assertTrue(index.none { it.kind == SearchItem.Kind.SETTING && it.name == "Position" && !it.path.startsWith("Settings") })
        // Caps per group.
        assertTrue(search("e").all { g -> g.items.size <= (if (g.title == "PAGES") 4 else 6) })
    }

    @Test
    fun emptyQueryListsRightNowThenPages() {
        val nucleus = ConfigRegistry.card("nucleus")!!
        val groups = MenuSearch.results(index, "  ", listOf(nucleus))
        assertEquals(listOf("RIGHT NOW", "PAGES"), groups.map { it.title })
        assertEquals(listOf("Nucleus run tracker"), groups[0].items.map { it.name })
        assertEquals(ConfigRegistry.activities.map { it.name } + "Settings › Interface", groups[1].items.map { it.name })
        assertEquals(listOf("PAGES"), MenuSearch.results(index, "", emptyList()).map { it.title })
    }

    @Test
    fun bestMatchIsSelectedEvenBelowAWeakerGroup() {
        val groups = search("ui scale")
        val flat = groups.flatMap { it.items }
        assertEquals("UI Scale", flat[MenuSearch.bestIndex(groups)].name)
    }

    // ---------------------------------------------------------------- right now

    @Test
    fun rightNowGroupsDetectorsFirstThenArea() {
        val ctx = NowContext("Crystal Hollows", "Mines of Divan", setOf(Detector.NUCLEUS))
        val groups = RightNow.groups(ConfigRegistry.cards, ctx)
        assertEquals(listOf("nucleus", "area"), groups.map { it.key })
        assertEquals(listOf("nucleus", "checklist", "bal"), groups[0].cards.map { it.id })
        assertEquals(listOf("nucchat", "weathermute", "weatherhide", "mdsolver", "mdloot", "toolscounter", "commissionshud", "commissionsdone"), groups[1].cards.map { it.id })
        assertEquals("Crystal Hollows", groups[1].label)
        assertEquals("hollows", RightNow.tabFor("mining", ConfigRegistry.cards, ctx))
        assertEquals("Nucleus run · Crystal Hollows", RightNow.why("mining", null, groups))
        assertEquals("Crystal Hollows", RightNow.why("mining", "divan", groups))
    }

    @Test
    fun rightNowLabelsAndEmptyStates() {
        val dungeon = RightNow.groups(ConfigRegistry.cards, NowContext("Dungeon", active = setOf(Detector.DUNGEON), floor = "F7"))
        assertEquals(listOf("Dungeon run · F7"), dungeon.map { it.label })
        val mineshaft = RightNow.groups(ConfigRegistry.cards, NowContext("Mineshaft", "Glacite Mineshafts"))
        assertEquals("Glacite Mineshafts", mineshaft.single().label)
        assertEquals(emptyList(), RightNow.groups(ConfigRegistry.cards, NowContext(null)))
        assertEquals(emptyList(), RightNow.groups(ConfigRegistry.cards, NowContext("Garden")))
        // A detector with no area: the sack menu shows the sack tracker.
        assertEquals(listOf("sacks"), RightNow.cards(ConfigRegistry.cards, NowContext("Hub", active = setOf(Detector.SACK_MENU))).map { it.id }.filter { it == "sacks" })
    }

    // ---------------------------------------------------------------- seen NEW, recent, pins

    @Test
    fun seenNewIsPerVersion() {
        val v = "0.1.2"
        var s = ConfigMenuState()
        assertTrue(MenuRules.isNew(s, v, "f:equipment"))
        assertTrue(MenuRules.isNew(s, v, "s:huds"))
        assertFalse(MenuRules.isNew(s, v, "f:sprint"))
        s = MenuRules.withSeen(s, v, "f:equipment")
        assertFalse(MenuRules.isNew(s, v, "f:equipment"))
        assertEquals(SeenNew(v, listOf("f:equipment")), s.seenNew)
        // Seeing something that is not NEW stores nothing.
        assertEquals(s, MenuRules.withSeen(s, v, "f:sprint"))
        // A release line is NEW until every id it names is seen, and is listed either way.
        val weather = Releases.of(v).first { "weather" in it.text }
        s = MenuRules.withSeen(s, v, "f:weathermute")
        assertTrue(MenuRules.isNoteNew(s, v, weather))
        assertEquals("f:weatherhide", MenuRules.noteTarget(s, v, weather))
        s = MenuRules.withSeen(s, v, "f:weatherhide")
        assertFalse(MenuRules.isNoteNew(s, v, weather))
        assertEquals("f:weathermute", MenuRules.noteTarget(s, v, weather))
        // Seen ids of an older version count for nothing.
        val old = ConfigMenuState(seenNew = SeenNew("0.1.1", listOf("f:equipment")))
        assertTrue(MenuRules.isNew(old, v, "f:equipment"))
        assertEquals(SeenNew(v, listOf("f:invbuttons")), MenuRules.withSeen(old, v, "f:invbuttons").seenNew)
        // A version without release notes has nothing NEW.
        assertFalse(MenuRules.isNew(ConfigMenuState(), "9.9.9", "f:equipment"))
    }

    @Test
    fun whatsNewListStaysWhenEverythingIsSeen() {
        val v = "0.1.2"
        val lines = Releases.of(v)
        assertTrue(lines.isNotEmpty())
        var s = ConfigMenuState()
        for (id in MenuRules.newIds(v)) s = MenuRules.withSeen(s, v, id)
        assertTrue(lines.none { MenuRules.isNoteNew(s, v, it) })
        // The list is the release's lines in full and each still jumps somewhere.
        assertEquals(lines, Releases.of(v))
        assertTrue(lines.all { MenuRules.noteTarget(s, v, it) in it.ids })
    }

    @Test
    fun recentKeepsTheLastThreeNewestFirst() {
        var s = ConfigMenuState()
        for (id in listOf("a", "b", "c", "a", "d")) s = MenuRules.withRecent(s, id)
        assertEquals(listOf("d", "a", "c"), s.recent)
    }

    @Test
    fun pinsToggleAndReorder() {
        var s = ConfigMenuState(pins = listOf("dmap", "sprint"))
        s = MenuRules.withPinToggled(s, "prices")
        assertEquals(listOf("dmap", "sprint", "prices"), s.pins)
        s = MenuRules.withPinMoved(s, "prices", 0)
        assertEquals(listOf("prices", "dmap", "sprint"), s.pins)
        s = MenuRules.withPinMoved(s, "prices", 9)
        assertEquals(listOf("dmap", "sprint", "prices"), s.pins)
        s = MenuRules.withPinToggled(s, "dmap")
        assertEquals(listOf("sprint", "prices"), s.pins)
    }

    // ---------------------------------------------------------------- registry

    @Test
    fun registryTreeIsConsistent() {
        val ids = ConfigRegistry.cards.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "card ids are unique")
        for (c in ConfigRegistry.cards) {
            val act = ConfigRegistry.activity(c.activity)
            assertTrue(act != null && act.tabs.any { it.id == c.tab }, "${c.id} sits in a declared tab")
            assertTrue(c.master != null || c.openAction != null || c.simple.isNotEmpty(), "${c.id} has a switch, opens something or lists independent toggles")
            for (ctx in c.contexts) assertTrue(Detector.of(ctx) != null, "${c.id}: unknown detector $ctx")
        }
        for (p in ConfigRegistry.onboardingPicks) for (id in p.cards) assertTrue(ConfigRegistry.card(id)?.master != null, "pick ${p.id}: $id")
        for ((version, notes) in Releases.notes) for (n in notes) {
            assertTrue(n.ids.isNotEmpty(), "$version: \"${n.text}\" names no card or Settings tab")
            for (id in n.ids) {
                val ok = if (id.startsWith("f:")) ConfigRegistry.card(id.drop(2)) != null else ConfigRegistry.settingsTabs.any { "s:${it.id}" == id }
                assertTrue(ok, "$version: release note names $id")
            }
        }
        // Empty tabs are hidden.
        assertEquals(listOf("hollows", "divan", "mineshafts", "dwarven", "powder"), ConfigRegistry.tabsOf("mining").map { it.id })
        assertEquals(listOf("mobs", "diana", "bosses"), ConfigRegistry.tabsOf("combat").map { it.id })
    }
}
