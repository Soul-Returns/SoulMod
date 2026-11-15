package com.soulreturns.platform.updater

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdatePolicyTest {
    @Test
    fun developmentRunsNeverActAutomatically() {
        assertFalse(UpdatePolicy.automatic(isDevelopment = true))
        assertTrue(UpdatePolicy.automatic(isDevelopment = false))
    }

    @Test
    fun sameNotionAsTheInstallTarget() {
        val game = Path.of("/game").toAbsolutePath()
        val jar = game.resolve("mods/soul-1.0.0.jar")
        for (dev in listOf(true, false)) {
            val target = ModsFolder.locate(dev, "PATH", listOf(jar), game, null)
            assertEquals(!UpdatePolicy.automatic(dev), target is InstallTarget.Unsupported)
        }
        assertIs<InstallTarget.Folder>(ModsFolder.locate(false, "PATH", listOf(jar), game, null))
    }
}
