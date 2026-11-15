package com.soulreturns.platform.updater

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModsFolderTest {
    private val game = Path.of("/game").toAbsolutePath()
    private val mods = game.resolve("mods")
    private val origin = mods.resolve("soul-0.2.0.jar")

    private fun jar(name: String, version: String) = SoulJar(mods.resolve(name), version)

    @Test
    fun cleanupTakesOnlyOlderSoulJarsAndNeverTheRunningOne() {
        val jars = listOf(
            jar("soul-0.2.0.jar", "0.2.0"),
            jar("soul-0.1.2.jar", "0.1.2"),
            jar("soul-0.2.0-beta1.jar", "0.2.0-beta1"),
            jar("renamed-by-player.jar", "0.1.0"),
            jar("soul-0.2.0 (1).jar", "0.2.0"),
            jar("soul-0.3.0.jar", "0.3.0"),
            jar("weird.jar", "not-a-version"),
        )
        val older = ModsFolder.olderJars(jars, origin, "0.2.0").map { it.path.fileName.toString() }
        assertEquals(listOf("soul-0.1.2.jar", "soul-0.2.0-beta1.jar", "renamed-by-player.jar"), older)
    }

    @Test
    fun cleanupNeverRunsWithoutAParseableRunningVersion() {
        assertEquals(emptyList(), ModsFolder.olderJars(listOf(jar("soul-0.1.0.jar", "0.1.0")), origin, "Unknown"))
    }

    @Test
    fun newerJarsMeanFabricFellBack() {
        val jars = listOf(jar("soul-0.1.2.jar", "0.1.2"), jar("soul-0.3.0.jar", "0.3.0"), jar("soul-0.2.0.jar", "0.2.0"))
        assertEquals(listOf("0.3.0"), ModsFolder.newerJars(jars, "0.2.0").map { it.version })
    }

    @Test
    fun installsOnlyFromAJarDirectlyInTheModsFolder() {
        val ok = ModsFolder.locate(false, "PATH", listOf(origin), game, null)
        assertEquals(InstallTarget.Folder(mods, origin), ok)

        assertIs<InstallTarget.Unsupported>(ModsFolder.locate(true, "PATH", listOf(origin), game, null))
        assertIs<InstallTarget.Unsupported>(ModsFolder.locate(false, "NESTED", listOf(origin), game, null))
        assertIs<InstallTarget.Unsupported>(ModsFolder.locate(false, "PATH", listOf(origin, origin), game, null))
        assertIs<InstallTarget.Unsupported>(ModsFolder.locate(false, "PATH", listOf(game.resolve("build/classes")), game, null))
        assertIs<InstallTarget.Unsupported>(ModsFolder.locate(false, "PATH", listOf(mods.resolve("sub/soul.jar")), game, null))
    }

    @Test
    fun honoursTheModsFolderProperty() {
        val custom = Path.of("/elsewhere/mods").toAbsolutePath()
        val jar = custom.resolve("soul-0.2.0.jar")
        assertEquals(InstallTarget.Folder(custom, jar), ModsFolder.locate(false, "PATH", listOf(jar), game, custom.toString()))
        assertIs<InstallTarget.Unsupported>(ModsFolder.locate(false, "PATH", listOf(origin), game, custom.toString()))
    }

    @Test
    fun notifyGateNeedsAJoinedWorldAndStaysOutOfRuns() {
        assertTrue(NotifyGate.ready(joined = true, inDungeon = false, inKuudra = false))
        assertFalse(NotifyGate.ready(joined = false, inDungeon = false, inKuudra = false))
        assertFalse(NotifyGate.ready(joined = true, inDungeon = true, inKuudra = false))
        assertFalse(NotifyGate.ready(joined = true, inDungeon = false, inKuudra = true))
    }
}
