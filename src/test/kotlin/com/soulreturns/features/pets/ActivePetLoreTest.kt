package com.soulreturns.features.pets

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Action lines captured in the live Pets menu, 2026-11-17. */
class ActivePetLoreTest {
    @Test
    fun activePetLore() {
        assertTrue(
            ActivePetLore.isActive(
                listOf("§7MAX LEVEL", "§7▸ 36,238,137 XP", "", "§eClick to despawn!", "§eShift Left-click to toggle as favorite!", "§eRight-click to convert to an item!"),
            ),
        )
    }

    @Test
    fun inactivePetLore() {
        assertFalse(ActivePetLore.isActive(listOf("§7MAX LEVEL", "", "§eLeft-click to summon!", "§eShift Left-click to toggle as favorite!")))
    }

    @Test
    fun wordsInsideAnotherLine() {
        assertFalse(ActivePetLore.isActive(listOf("§7Never Click to despawn! this", "§eShift Click to despawn! now")))
    }

    @Test
    fun emptyLore() {
        assertFalse(ActivePetLore.isActive(emptyList()))
    }
}
