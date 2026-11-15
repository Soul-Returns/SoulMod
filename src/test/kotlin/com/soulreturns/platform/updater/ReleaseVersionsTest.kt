package com.soulreturns.platform.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseVersionsTest {
    private fun newer(a: String, b: String) = ReleaseVersions.isNewer(a, b)

    @Test
    fun numericComponentsCompareNumerically() {
        assertTrue(newer("0.1.10", "0.1.9"))
        assertTrue(newer("0.2.0", "0.1.99"))
        assertTrue(newer("1.0.0", "0.99.99"))
        assertFalse(newer("0.1.2", "0.1.2"))
        assertFalse(newer("0.1.1", "0.1.2"))
    }

    @Test
    fun prereleaseSortsBeforeItsRelease() {
        assertTrue(newer("0.2.0", "0.2.0-beta1"))
        assertFalse(newer("0.2.0-beta1", "0.2.0"))
        assertTrue(newer("0.2.0-beta1", "0.1.9"))
        assertTrue(newer("0.2.1-beta1", "0.2.0"))
    }

    @Test
    fun prereleasesOrderLikeFabric() {
        assertTrue(newer("0.2.0-beta2", "0.2.0-beta1"))
        assertTrue(newer("0.2.0-rc1", "0.2.0-beta9"))
        assertTrue(newer("0.2.0-beta.10", "0.2.0-beta.9"))
        // SemVer compares alphanumeric identifiers lexically; that is why `make bump` refuses -beta10.
        assertFalse(newer("0.2.0-beta10", "0.2.0-beta9"))
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertFalse(newer("0.2.0+mc26.2", "0.2.0"))
        assertFalse(newer("0.2.0", "0.2.0+mc26.2"))
    }

    @Test
    fun garbageNeverCountsAsNewer() {
        assertNull(ReleaseVersions.parse("Unknown"))
        assertNull(ReleaseVersions.parse("0.2.x"))
        assertFalse(newer("Unknown", "0.1.0"))
        assertFalse(newer("0.2.0", "Unknown"))
    }

    @Test
    fun channelFollowsThePrereleasePart() {
        assertEquals("stable", ReleaseVersions.channelOf(ReleaseVersions.parse("0.2.0")!!))
        assertEquals("beta", ReleaseVersions.channelOf(ReleaseVersions.parse("0.2.0-beta1")!!))
        assertEquals("stable", ReleaseVersions.channelOf(ReleaseVersions.parse("0.2.0+build.5")!!))
    }
}
