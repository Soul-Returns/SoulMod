package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Events
import com.soulreturns.util.MessageDetector
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Replays live client sequences (latest.log, 2026-11-15) in the client's dispatch order: the state
 * reader handles each chat line first (Soul.kt registers it before Island chests and Storage), its
 * ProfileChanged resets both features, then both features see the line.
 */
class ProfileChatIdentityTest {
    private val strawberry = "bee5bdcf-58ea-4c19-8608-fce0c9a342ea"
    private val blueberry = "94f5a14d-a28f-4a3b-a404-1a3d7d9af888"

    /** One feature's identity wiring: Storage resets on join/world/ProfileChanged, Island chests also on LocationChangeEvent. */
    private class Feature(val resetsOnLocation: Boolean) {
        val id = FreshProfileId()
    }

    private val storage = Feature(resetsOnLocation = false)
    private val chests = Feature(resetsOnLocation = true)
    private val features = listOf(storage, chests)
    private var world = Any()

    @BeforeTest
    fun start() {
        SkyblockProfileState.updateProfile(null)
        active = this
    }

    @AfterTest
    fun stop() {
        active = null
        SkyblockProfileState.updateProfile(null)
    }

    private fun chat(raw: String) {
        val line = MessageDetector.stripColorCodes(raw).trim()
        // SkyblockStateReader's chat handler.
        ProfileChatLines.name(line)?.let(SkyblockProfileState::updateProfile)
        ProfileChatLines.id(line)?.let(SkyblockProfileState::updateProfileId)
        features.forEach { it.id.offer(line, world) }
    }

    /** World join: ClientPlayConnectionEvents.JOIN and the level change reset both features. */
    private fun join() {
        world = Any()
        features.forEach { it.id.reset() }
    }

    /** The mod API location packet, right after the join. */
    private fun location() = features.filter { it.resetsOnLocation }.forEach { it.id.reset() }

    /** The tab list's `Profile: <name>`, read by the poll ~2 s after the join. */
    private fun tab(name: String) = SkyblockProfileState.updateProfile(name)

    private fun assertResolved(expected: String?) {
        assertEquals(expected, storage.id.resolved(world, SkyblockProfileState.profileId), "storage")
        assertEquals(expected, chests.id.resolved(world, SkyblockProfileState.profileId), "island chests")
    }

    private fun normalJoin(name: String, id: String) {
        join()
        location()
        chat("§aYou are playing on profile: §e$name§b (Co-op)")
        chat("Profile ID: $id")
        tab(name)
    }

    /** 21:29:51 and 21:30:23: the tab list reports the new name before the switch lines. */
    private fun switchTo(name: String, id: String, server: String) {
        chat("Switching to profile $name...")
        chat("Sending to server $server...")
        join()
        location()
        tab(name)
        chat("§aYour profile was changed to: §e$name§b (Co-op)")
        chat("Profile ID: $id")
    }

    @Test
    fun normalJoinResolves() {
        normalJoin("Strawberry", strawberry)
        assertResolved(strawberry)
        assertEquals("Strawberry", SkyblockProfileState.current)
    }

    @Test
    fun switchToAnotherProfileResolvesItsId() {
        normalJoin("Strawberry", strawberry)
        chat("Switching to profile Blueberry...")
        chat("Sending to server mini206B...")
        join()
        location()
        // The old profile's id is still the state's until the tab list moves on; nothing may show it.
        assertEquals(strawberry, SkyblockProfileState.profileId)
        assertResolved(null)
        tab("Blueberry")
        assertResolved(null)
        chat("§aYour profile was changed to: §eBlueberry§b (Co-op)")
        assertResolved(null)
        chat("Profile ID: $blueberry")
        assertResolved(blueberry)
        assertEquals("Blueberry", SkyblockProfileState.current)
    }

    @Test
    fun switchBackResolvesTheFirstProfileAgain() {
        normalJoin("Strawberry", strawberry)
        switchTo("Blueberry", blueberry, "mini206B")
        assertResolved(blueberry)
        switchTo("Strawberry", strawberry, "mini18AR")
        assertResolved(strawberry)
        assertEquals("Strawberry", SkyblockProfileState.current)
    }

    @Test
    fun switchResolvesWhenTheTabListCatchesUpAfterTheLines() {
        normalJoin("Strawberry", strawberry)
        chat("Switching to profile Blueberry...")
        chat("Sending to server mini206B...")
        join()
        location()
        chat("§aYour profile was changed to: §eBlueberry§b (Co-op)")
        chat("Profile ID: $blueberry")
        tab("Blueberry")
        assertEquals(blueberry, SkyblockProfileState.profileId)
        assertResolved(blueberry)
    }

    @Test
    fun idLineWithoutNameLineDoesNotResolve() {
        normalJoin("Strawberry", strawberry)
        join()
        location()
        chat("Profile ID: $strawberry")
        assertResolved(null)
    }

    @Test
    fun nameLineFromThePreviousWorldDoesNotCount() {
        normalJoin("Strawberry", strawberry)
        chat("§aYour profile was changed to: §eStrawberry§b (Co-op)")
        join()
        chat("Profile ID: $strawberry")
        assertResolved(null)
    }

    @Test
    fun serverChangeAfterSwitchNeedsTheNewJoinLines() {
        normalJoin("Strawberry", strawberry)
        switchTo("Blueberry", blueberry, "mini206B")
        assertResolved(blueberry)
        chat("Sending to server mini3BP...")
        // 18:53:23: the previous server's late pair can land after "Sending to server".
        chat("§aYou are playing on profile: §eBlueberry§b (Co-op)")
        chat("Profile ID: $blueberry")
        join()
        location()
        assertResolved(null)
        chat("§aYou are playing on profile: §eBlueberry§b (Co-op)")
        chat("Profile ID: $blueberry")
        assertResolved(blueberry)
    }

    @Test
    fun nameLinesParseBothForms() {
        assertEquals("Banana", ProfileChatLines.name(MessageDetector.stripColorCodes("§r§aYou are playing on profile: §eBanana").trim()))
        assertEquals("Strawberry", ProfileChatLines.name(MessageDetector.stripColorCodes("§aYour profile was changed to: §eStrawberry§b (Co-op)").trim()))
        assertNull(ProfileChatLines.name("Switching to profile Strawberry..."))
        assertEquals(strawberry, ProfileChatLines.id("Profile ID: BEE5BDCF58EA4C198608FCE0C9A342EA"))
    }

    private fun onProfileChanged() = features.forEach { it.id.reset() }

    companion object {
        private var active: ProfileChatIdentityTest? = null

        init {
            // The real bus: ProfileChanged reaches the features after the state reader published it.
            Events.subscribe<ProfileChanged> { active?.onProfileChanged() }
        }
    }
}
