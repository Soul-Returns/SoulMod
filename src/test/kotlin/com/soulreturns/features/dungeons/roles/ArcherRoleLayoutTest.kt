package com.soulreturns.features.dungeons.roles

import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.leap.LeapMenu
import com.soulreturns.features.dungeons.leap.TeammateLocations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcherRoleLayoutTest {
    private fun cue(target: DungeonClass? = DungeonClass.MAGE, name: String? = null, waiting: Boolean = false) =
        ArcherCue("S1", "Leap → Mage", "Destination unknown · ?", target, name, waiting = waiting)

    private fun tile(name: String, cls: DungeonClass, dead: Boolean = false) =
        LeapMenu.Tile(name, cls, 10, dead, 0, null, null, TeammateLocations.UNKNOWN, false)

    @Test
    fun cueAnchorMatchesApprovedMockAndItsClamp() {
        // 2560x1440 / Soul 2 and 1920x1080 / Soul 1.5 both have a 720 Soul-pixel viewport.
        assertEquals(514f, ArcherRoleLayout.cueY(720f))
        assertEquals(330f, ArcherRoleLayout.cueY(480f))
        assertEquals(694f, ArcherRoleLayout.cueY(1080f))
    }

    @Test
    fun menuOnlyVariantKeepsWaitAdviceButNotLeapOrDuplicateObjectives() {
        assertTrue(ArcherRoleLayout.showsCue(cue(), true))
        assertFalse(ArcherRoleLayout.showsCue(cue(), false))
        assertTrue(ArcherRoleLayout.showsCue(cue(waiting = true), false))
        val objective = ArcherCue("S1", "S1 T4", "", objective = RoleObjective("s1.t4", "S1 T4", 89.0, 122.0, 101.0))
        assertFalse(ArcherRoleLayout.showsCue(objective, true))
        assertTrue(ArcherRoleLayout.showsCue(objective.copy(waiting = true), false))
    }

    @Test
    fun recommendationFindsTheTargetWithoutChangingTileOrder() {
        val tiles = listOf(
            tile("Waccoons", DungeonClass.MAGE), tile("PuppyGirlSunny", DungeonClass.BERSERK),
            tile("go9o", DungeonClass.HEALER), tile("Maaxnn", DungeonClass.TANK),
        )
        assertEquals(0, ArcherRoleLayout.targetIndex(cue(), tiles))
        assertEquals(2, ArcherRoleLayout.targetIndex(cue(DungeonClass.HEALER, waiting = true), tiles))
    }

    @Test
    fun classFallbackRequiresOneLivingTeammate() {
        val first = tile("First", DungeonClass.MAGE)
        val second = tile("Second", DungeonClass.MAGE)
        assertEquals(-1, ArcherRoleLayout.targetIndex(cue(), listOf(first, null, second)))
        assertEquals(0, ArcherRoleLayout.targetIndex(cue(), listOf(first, tile("Dead", DungeonClass.MAGE, true))))
        assertEquals(-1, ArcherRoleLayout.targetIndex(cue(DungeonClass.UNKNOWN), listOf(first)))
        assertEquals(-1, ArcherRoleLayout.targetIndex(cue(null), listOf(first)))
    }

    @Test
    fun explicitTargetDoesNotRetargetWhenAbsentOrDead() {
        val other = tile("Other", DungeonClass.MAGE)
        assertEquals(-1, ArcherRoleLayout.targetIndex(cue(name = "Waccoons"), listOf(other)))
        assertEquals(-1, ArcherRoleLayout.targetIndex(cue(name = "Waccoons"), listOf(other, tile("Waccoons", DungeonClass.MAGE, true))))
        assertEquals(1, ArcherRoleLayout.targetIndex(cue(name = "waccoons"), listOf(other, tile("Waccoons", DungeonClass.MAGE))))
    }
}
