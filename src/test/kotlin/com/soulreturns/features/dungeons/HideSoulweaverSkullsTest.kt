package com.soulreturns.features.dungeons

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HideSoulweaverSkullsTest {
    /**
     * The Haunted Skull head's `textures` value. The dev bridge does not record item components, so
     * it is not from a recording: Skyblocker (`SOULWEAVER_HAUNTED_SKULL`), SkyHanni's repo
     * (`DUNGEONS_SOUL_WEAVER`) and NoFrills (payload `hashCode` -1020507406, checked below) carry
     * this exact string.
     */
    private val hauntedSkull =
        "eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ=="

    /** Wither essence skull from the F7 run (`block_entity_data` of the 2026-11-12 recording), a room decoration head. */
    private val witherEssenceSkull =
        "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYzE1Yjc0NzRmODMzYTNmMmE3OWE4ZDY4NGYxMDAwZjNjMWRiMmRjYzczODIyNjY1Y2YxMWMxYTg2YjRlYzBkMCJ9fX0="

    /** Blood Ichor head, a different SkyBlock skull (SkyHanni repo), with Mojang's pretty-printed JSON. */
    private val bloodIchor =
        "ewogICJ0aW1lc3RhbXAiIDogMTYxNTg4ODAwMDU1MywKICAicHJvZmlsZUlkIiA6ICI5ZDIyZGRhOTVmZGI0MjFmOGZhNjAzNTI1YThkZmE4ZCIsCiAgInByb2ZpbGVOYW1lIiA6ICJTYWZlRHJpZnQ0OCIsCiAgInNpZ25hdHVyZVJlcXVpcmVkIiA6IHRydWUsCiAgInRleHR1cmVzIiA6IHsKICAgICJTS0lOIiA6IHsKICAgICAgInVybCIgOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS9jMDM0MDkyM2E2ZGU0ODI1YTE3NjgxM2QxMzM1MDNlZmYxODZkYjA4OTZlMzJiNjcwNDkyOGMyYTJiZjY4NDIyIgogICAgfQogIH0KfQ=="

    @Test
    fun literalIsTheOneNoFrillsMatches() {
        assertEquals(-1020507406, hauntedSkull.hashCode())
    }

    @Test
    fun hauntedSkullMatches() {
        assertTrue(HideSoulweaverSkulls.matchesTexture(hauntedSkull))
    }

    @Test
    fun otherSkullsDoNot() {
        assertFalse(HideSoulweaverSkulls.matchesTexture(witherEssenceSkull))
        assertFalse(HideSoulweaverSkulls.matchesTexture(bloodIchor))
    }

    @Test
    fun differentWrapperSameSkinStillMatches() {
        val json = """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/${HideSoulweaverSkulls.TEXTURE_HASH}","metadata":{"model":"slim"}}}}"""
        assertTrue(HideSoulweaverSkulls.matchesTexture(Base64.getEncoder().encodeToString(json.toByteArray())))
    }

    @Test
    fun missingOrBrokenValuesDoNot() {
        assertFalse(HideSoulweaverSkulls.matchesTexture(null))
        assertFalse(HideSoulweaverSkulls.matchesTexture(""))
        assertFalse(HideSoulweaverSkulls.matchesTexture("not base64 !!"))
        assertFalse(HideSoulweaverSkulls.matchesTexture(Base64.getEncoder().encodeToString("{}".toByteArray())))
    }
}
