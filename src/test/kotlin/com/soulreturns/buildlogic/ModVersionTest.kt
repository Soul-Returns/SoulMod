package com.soulreturns.buildlogic

import com.soulreturns.platform.updater.ReleaseVersions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModVersionTest {
    @Test
    fun stableReleaseIsFollowedByTheNextBetaZeroDev() {
        assertEquals("0.3.4-beta.0.dev", ModVersion.dev("0.3.3"))
        assertEquals("1.0.10-beta.0.dev", ModVersion.dev("1.0.9"))
        assertEquals("0.4.1-beta.0.dev", ModVersion.dev("0.4.0"))
    }

    @Test
    fun betaReleaseIsFollowedByItsOwnDev() {
        assertEquals("0.3.4-beta.2.dev", ModVersion.dev("0.3.4-beta.2"))
        assertEquals("0.3.4-beta.10.dev", ModVersion.dev("0.3.4-beta.10"))
        assertEquals("0.3.4-rc1.dev", ModVersion.dev("0.3.4-rc1"))
    }

    @Test
    fun aDevNameStaysAsItIs() {
        assertEquals("0.3.4-beta.0.dev", ModVersion.dev("0.3.4-beta.0.dev"))
        assertEquals("0.3.4-beta.2.dev", ModVersion.dev("0.3.4-beta.2.dev"))
    }

    @Test
    fun anExplicitVersionWinsUnchanged() {
        assertEquals("0.3.3", ModVersion.resolve("0.3.3", "0.3.3"))
        assertEquals("0.3.4-beta.1", ModVersion.resolve("0.3.3", "0.3.4-beta.1"))
        assertEquals("0.3.4-beta.0.dev", ModVersion.resolve("0.3.3", null))
        assertEquals("0.3.4-beta.0.dev", ModVersion.resolve("0.3.3", "  "))
    }

    @Test
    fun malformedDeclaredVersionFailsTheBuild() {
        assertFailsWith<IllegalStateException> { ModVersion.dev("0.3") }
        assertFailsWith<IllegalStateException> { ModVersion.dev("0.3.3+build.5") }
        assertFailsWith<IllegalStateException> { ModVersion.dev("") }
    }

    /** The point of the `.dev` name, in the order the loader and the updater use. */
    @Test
    fun aDevBuildSortsAboveItsReleaseAndBelowTheNextOnes() {
        fun between(release: String, vararg later: String) {
            val dev = ModVersion.dev(release)
            assertNotNull(ReleaseVersions.parse(dev), "$dev must parse like Fabric parses it")
            assertTrue(ReleaseVersions.isNewer(dev, release), "$dev above $release")
            for (next in later) assertTrue(ReleaseVersions.isNewer(next, dev), "$next above $dev")
        }
        between("0.3.3", "0.3.4-beta.1", "0.3.4-beta.2", "0.3.4", "0.4.0")
        between("0.3.4-beta.2", "0.3.4-beta.3", "0.3.4-beta.10", "0.3.4")
        between("0.3.4-beta.9", "0.3.4-beta.10", "0.3.4")
        assertTrue(ReleaseVersions.isNewer(ModVersion.dev("0.3.3"), "0.3.4-beta.0"))
    }
}
