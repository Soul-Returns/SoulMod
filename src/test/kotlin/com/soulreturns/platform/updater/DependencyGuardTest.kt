package com.soulreturns.platform.updater

import net.fabricmc.loader.api.Version
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyGuardTest {
    /** The running loader of the test client: 26.2, Loader 0.19.3, FAPI 0.153.0, FLK 1.13.12. */
    private val installed = mapOf(
        "minecraft" to "26.2",
        "java" to "25",
        "fabricloader" to "0.19.3",
        "fabric-api" to "0.153.0+26.2",
        "fabric-language-kotlin" to "1.13.12+kotlin.2.4.0",
        "hypixel-mod-api" to "1.0.2+build.1+mc26.1",
        "sodium" to "0.7.0",
    )
    private val lookup = { id: String -> installed[id]?.let { Version.parse(it) } }

    private fun modJson(depends: String, breaks: String = "{}", extra: String = "") =
        """{"schemaVersion":1,"id":"soul","version":"0.2.0","depends":$depends,"breaks":$breaks$extra}"""

    private val today =
        """{"fabricloader":">=0.19.3","fabric-language-kotlin":">=1.13.12","minecraft":"~26.2","java":">=25","fabric-api":"*","hypixel-mod-api":">=1.0.2"}"""

    @Test
    fun todaysDependsAreSatisfied() {
        val meta = assertNotNull(JarMetadataReader.fromModJson(modJson(today)))
        assertEquals(emptyList(), DependencyGuard.check(meta, lookup))
    }

    @Test
    fun tooOldAndMissingAreNamed() {
        val meta = assertNotNull(JarMetadataReader.fromModJson(modJson("""{"fabric-api":">=0.160.0","fabricloader":">=0.20.0","owo-lib":"*"}""")))
        val problems = DependencyGuard.check(meta, lookup)
        assertEquals(listOf("fabric-api", "fabricloader", "owo-lib"), problems.map { it.id })
        assertEquals("needs Fabric API >=0.160.0 (installed: 0.153.0+26.2)", problems[0].describe())
        assertEquals("needs owo-lib * (not installed)", problems[2].describe())
    }

    @Test
    fun anotherMinecraftOrJavaIsRefused() {
        val meta = assertNotNull(JarMetadataReader.fromModJson(modJson("""{"minecraft":"~26.3","java":">=26"}""")))
        assertEquals(listOf("minecraft", "java"), DependencyGuard.check(meta, lookup).map { it.id })
    }

    @Test
    fun arrayValueIsSatisfiedByAnyEntry() {
        val meta = assertNotNull(JarMetadataReader.fromModJson(modJson("""{"minecraft":["~26.1","~26.2"]}""")))
        assertEquals(emptyList(), DependencyGuard.check(meta, lookup))
    }

    @Test
    fun aNestedJarSatisfiesItsOwnDependency() {
        val depends = """{"hypixel-mod-api":">=1.0.3"}"""
        val bare = assertNotNull(JarMetadataReader.fromModJson(modJson(depends)))
        assertEquals(listOf("hypixel-mod-api"), DependencyGuard.check(bare, lookup).map { it.id })
        val nested = assertNotNull(JarMetadataReader.fromModJson(modJson(depends), nested = mapOf("hypixel-mod-api" to "1.0.3+build.1")))
        assertEquals(emptyList(), DependencyGuard.check(nested, lookup))
    }

    @Test
    fun breaksAgainstAnInstalledModIsAProblem() {
        val meta = assertNotNull(JarMetadataReader.fromModJson(modJson("{}", breaks = """{"sodium":"<0.8.0","iris":"*"}""")))
        val problems = DependencyGuard.check(meta, lookup)
        assertEquals(listOf("sodium"), problems.map { it.id })
        assertTrue(problems.single().breaks)
    }

    @Test
    fun readsDependsAndNestedModsFromARealJar() {
        val nestedJar = zip(mapOf("fabric.mod.json" to """{"id":"hypixel-mod-api","version":"1.0.3+build.1","provides":["hypixel_mod_api"]}"""))
        val outer = zip(
            mapOf(
                "fabric.mod.json" to modJson(today, extra = ""","jars":[{"file":"META-INF/jars/hma.jar"}]"""),
                "META-INF/jars/hma.jar" to nestedJar,
            ),
        )
        val file = Files.createTempFile("soul-test", ".jar")
        try {
            Files.write(file, outer)
            val meta = assertNotNull(JarMetadataReader.read(file))
            assertEquals("soul", meta.id)
            assertEquals("0.2.0", meta.version)
            assertEquals(listOf(">=1.0.2"), meta.depends["hypixel-mod-api"])
            assertEquals(mapOf("hypixel-mod-api" to "1.0.3+build.1", "hypixel_mod_api" to "1.0.3+build.1"), meta.nested)
            assertEquals("soul" to "0.2.0", JarMetadataReader.readId(file))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun zip(entries: Map<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(if (content is ByteArray) content else content.toString().toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
