package com.soulreturns.features.profileviewer

import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.ProfileSections
import com.soulreturns.features.profileviewer.gui.ProfileViewerLayout
import com.soulreturns.features.profileviewer.gui.ProfileViewerLayout.RailRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The shell geometry against `mocks/profile-viewer/mock.js` `draw()` and `mock.css`'s rail. */
class ProfileViewerLayoutTest {
    @Test
    fun `card at 1280x720 Soul px (1920x1080 at UI 1_5, 1280x720 at 1)`() {
        val c = ProfileViewerLayout.card(1280f, 720f)
        assertEquals(1177, c.w)
        assertEquals(648, c.h)
        assertEquals(51, c.x0)
        assertEquals(36, c.y0)
        assertFalse(c.miniRail)
        assertFalse(c.searchCollapsed)
        assertEquals(176, c.railW)
        assertEquals(210, c.searchW)
    }

    @Test
    fun `card at 960x540 (1920x1080 at UI 2) keeps the full rail and search`() {
        val c = ProfileViewerLayout.card(960f, 540f)
        assertEquals(883, c.w)
        assertEquals(486, c.h)
        assertFalse(c.miniRail)
        assertFalse(c.searchCollapsed)
    }

    @Test
    fun `narrow preset 900x520 at UI 1_5 collapses rail and search`() {
        val c = ProfileViewerLayout.card(600f, 346.6667f)
        assertEquals(552, c.w)
        assertEquals(312, c.h)
        assertEquals(24, c.x0)
        assertEquals(17, c.y0)
        assertTrue(c.miniRail)
        assertTrue(c.searchCollapsed)
        assertEquals(44, c.railW)
        assertEquals(150, c.searchW)
    }

    @Test
    fun `the test client 1708x960 at Soul scale 4 over 3`() {
        val c = ProfileViewerLayout.card(1708 / (4f / 3f), 960 / (4f / 3f))
        assertEquals(1178, c.w)
        assertEquals(648, c.h)
    }

    @Test
    fun `huge screens cap the card`() {
        val c = ProfileViewerLayout.card(2560f, 1440f)
        assertEquals(1200, c.w)
        assertEquals(800, c.h)
    }

    @Test
    fun `rail rows follow the registry groups`() {
        val (rows, h) = ProfileViewerLayout.railRows(ProfileSections.ALL.map { it.id to it.group }, mini = false)
        // Overview at the top, no label for the top group.
        assertEquals(RailRow(ProfileViewerLayout.ROW_ITEM, 8, 26, "overview", null), rows[0])
        // "Items" label after 1 px, then Gear.
        assertEquals(RailRow(ProfileViewerLayout.ROW_GROUP, 35, 26, null, "Items"), rows[1])
        assertEquals(RailRow(ProfileViewerLayout.ROW_ITEM, 62, 26, "gear", null), rows[2])
        // Soul after a separator, last.
        assertEquals(ProfileViewerLayout.ROW_SEP, rows[rows.size - 2].kind)
        assertEquals("soul", rows.last().sectionId)
        // 17 items, 4 labels, 1 separator, 21 gaps, 8 + 8 padding.
        assertEquals(8 + 17 * 26 + 4 * 26 + 13 + 21 + 8, h)
        assertEquals(17, rows.count { it.kind == ProfileViewerLayout.ROW_ITEM })
    }

    @Test
    fun `the mini rail swaps group labels for separators`() {
        val (rows, _) = ProfileViewerLayout.railRows(ProfileSections.ALL.map { it.id to it.group }, mini = true)
        assertEquals(0, rows.count { it.kind == ProfileViewerLayout.ROW_GROUP })
        assertEquals(5, rows.count { it.kind == ProfileViewerLayout.ROW_SEP })
        assertEquals(ProfileSection.GROUP_SOUL, ProfileSections.ALL.last().group)
    }

    @Test
    fun `popovers stay inside the card`() {
        // Search: right-aligned to the field, 250 wide.
        assertEquals(1000 - 250, ProfileViewerLayout.popoverX(790, 1000, 250, 51, 1177, alignLeft = false))
        // Profiles: left-aligned, pushed in from the right edge.
        assertEquals(51 + 1177 - 316 - 6, ProfileViewerLayout.popoverX(1100, 1180, 316, 51, 1177, alignLeft = true))
        // Never past the left edge.
        assertEquals(51 + 6, ProfileViewerLayout.popoverX(20, 100, 250, 51, 1177, alignLeft = false))
    }

    @Test
    fun `body starts under the section header and the banner`() {
        assertEquals(36 + 52 + 12 + 14 + 8, ProfileViewerLayout.bodyTop(36, banner = false))
        assertEquals(36 + 52 + 12 + 14 + 8 + 28 + 8, ProfileViewerLayout.bodyTop(36, banner = true))
    }
}
