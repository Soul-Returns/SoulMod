package com.soulreturns.platform.updater

import java.nio.file.Files
import java.nio.file.Path

/** Where an update may be placed, or why it may not (then the updater only notifies). */
sealed interface InstallTarget {
    /** [origin] is the running Soul jar, directly inside [mods]. */
    data class Folder(val mods: Path, val origin: Path) : InstallTarget

    data class Unsupported(val reason: String) : InstallTarget
}

/** A `soul` jar in the mods folder (identified by its `fabric.mod.json`, never by file name). */
data class SoulJar(val path: Path, val version: String)

/**
 * The mods-folder half of the jar swap (research doc §2.3, §7.1 steps 6-8).
 *
 * Fabric reads every regular, non-hidden `*.jar` directly in the mods folder; with two `soul` jars it
 * loads the higher version and falls back to the lower one when the higher cannot resolve. So an
 * update is a second jar next to the running one, and the newer version removes the older jars on
 * its first start: the unselected jar was only opened and closed during discovery, so it is not
 * locked even on Windows. Files starting with `.` are ignored by Fabric, which is what makes the
 * `.soul-<ver>.jar.part` download safe to leave half-written.
 */
object ModsFolder {
    const val PART_PREFIX = ".soul-"
    const val PART_SUFFIX = ".jar.part"

    /**
     * Pure: decides whether this run can install updates.
     *
     * @param originKind `ModOrigin.Kind` name of the `soul` container.
     * @param modsFolderProperty the `fabric.modsFolder` system property (resolved against the working
     *   directory like Fabric does), null when unset.
     */
    fun locate(
        isDevelopment: Boolean,
        originKind: String,
        originPaths: List<Path>,
        gameDir: Path,
        modsFolderProperty: String?,
    ): InstallTarget {
        if (isDevelopment) return InstallTarget.Unsupported("development run")
        if (originKind != "PATH") return InstallTarget.Unsupported("Soul was not loaded from a file ($originKind)")
        val origin = originPaths.singleOrNull() ?: return InstallTarget.Unsupported("Soul was loaded from ${originPaths.size} paths")
        if (!origin.fileName.toString().endsWith(".jar", ignoreCase = true)) return InstallTarget.Unsupported("Soul was not loaded from a jar")
        val mods = (modsFolderProperty?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: gameDir.resolve("mods")).toAbsolutePath().normalize()
        val abs = origin.toAbsolutePath().normalize()
        if (abs.parent != mods) return InstallTarget.Unsupported("Soul's jar is not directly in the mods folder")
        return InstallTarget.Folder(mods, abs)
    }

    /** Pure: the `soul` jars older than [running], never [origin], never one whose version does not parse. */
    fun olderJars(jars: List<SoulJar>, origin: Path, running: String): List<SoulJar> {
        val current = ReleaseVersions.parse(running) ?: return emptyList()
        return jars.filter { jar ->
            if (jar.path.toAbsolutePath().normalize() == origin.toAbsolutePath().normalize()) return@filter false
            val v = ReleaseVersions.parse(jar.version) ?: return@filter false
            v < current
        }
    }

    /** Pure: `soul` jars newer than [running]. Present at runtime = Fabric could not load them. */
    fun newerJars(jars: List<SoulJar>, running: String): List<SoulJar> {
        val current = ReleaseVersions.parse(running) ?: return emptyList()
        return jars.filter { jar -> ReleaseVersions.parse(jar.version)?.let { it > current } == true }
    }

    /** Every regular `*.jar` directly in [mods] that Fabric would see and whose mod id is `soul`. */
    fun scan(mods: Path): List<SoulJar> {
        if (!Files.isDirectory(mods)) return emptyList()
        val out = ArrayList<SoulJar>()
        Files.newDirectoryStream(mods).use { stream ->
            for (path in stream) {
                val name = path.fileName.toString()
                if (name.startsWith(".") || !name.endsWith(".jar", ignoreCase = true) || !Files.isRegularFile(path)) continue
                val (id, version) = JarMetadataReader.readId(path) ?: continue
                if (id == ReleaseVerifier.MOD_ID) out += SoulJar(path, version)
            }
        }
        return out
    }

    /** Leftover `.soul-*.jar.part` downloads from an interrupted session. */
    fun leftoverParts(mods: Path): List<Path> {
        if (!Files.isDirectory(mods)) return emptyList()
        Files.newDirectoryStream(mods, "$PART_PREFIX*$PART_SUFFIX").use { stream -> return stream.toList() }
    }
}
