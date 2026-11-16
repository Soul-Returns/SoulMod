import java.io.File
import java.util.Properties

plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    // 26.1+ uses the NEW no-remap Loom plugin id `net.fabricmc.fabric-loom` (the old `fabric-loom`
    // id is the legacy remapping plugin that requires a `mappings` dependency — wrong for the
    // unobfuscated era). With this plugin there is no `mappings` line and deps use plain
    // `implementation` instead of `modImplementation`.
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

version = property("mod_version").toString()
group = property("maven_group").toString()

base {
    archivesName.set(property("archives_base_name").toString())
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://api.modrinth.com/maven") { name = "Modrinth" }
    maven("https://repo.hypixel.net/repository/Hypixel/") { name = "Hypixel" }
    // REI's API for its exclusion zones (StorageReiPlugin); nothing else comes from this repository.
    exclusiveContent {
        forRepository { maven("https://maven.shedaniel.me/") { name = "Shedaniel" } }
        filter { includeGroup("me.shedaniel"); includeGroup("me.shedaniel.cloth") }
    }
    mavenCentral()
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    // No `mappings` dependency: the new net.fabricmc.fabric-loom plugin does not remap, and 26.1+
    // ships unobfuscated (real names + parameters), so there is nothing to map.

    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    // kotlinx.serialization runtime is bundled by Fabric Language Kotlin (jar-in-jar) and is
    // present in both the dev runtime and production. Declared compileOnly so we compile
    // against it without shipping a second copy. Keep this version aligned with what FLK
    // ships for kotlin 2.4.0; bump if a gradle sync reports a mismatch.
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Hypixel Mod API — official location/party packets (instance id drives the dungeon map's
    // websocket channel). Two artifacts: the core API (compile classpath; its classes ship inside
    // the Fabric mod's nested jar, which the compile classpath can't see) and the Fabric
    // integration mod from Modrinth, bundled jar-in-jar via `include` so users don't have to
    // install it separately.
    compileOnly("net.hypixel:mod-api:1.0.2")
    implementation("maven.modrinth:hypixel-mod-api:1.0.2+build.1+mc26.1")
    include("maven.modrinth:hypixel-mod-api:1.0.2+build.1+mc26.1")

    // Roughly Enough Items API (MIT) and the cloth basic-math `Rectangle` its exclusion zones use
    // (Unlicense): compile-only, never bundled. The `rei_client` entrypoint that needs them only
    // loads when the player has REI installed (which brings both at runtime).
    compileOnly("me.shedaniel:RoughlyEnoughItems-api-fabric:26.2.820") { isTransitive = false }
    compileOnly("me.shedaniel.cloth:basic-math:0.6.1") { isTransitive = false }

    // JLayer (LGPL-2.0-or-later) decodes imported .mp3 files for the sound library; Minecraft only ships an
    // Ogg decoder. Plain library, so Loom wraps it as a nested jar.
    implementation("javazoom:jlayer:1.0.1")
    include("javazoom:jlayer:1.0.1")

    // DevAuth Neo — authenticates a real Microsoft account in the dev environment so the client
    // can join online servers (Hypixel). `localRuntime` = dev runtime only, never bundled into
    // the published jar and never exposed to consumers. Enable via JVM args on the run config
    // -Ddevauth.enabled=1 + the device-code flow flags.
    "localRuntime"("net.litetex.mcm:dev-auth-neo:1.1.1")

    // Unit tests for pure code (config migrations and recovery). kotlinx-serialization is
    // compileOnly above (FLK provides it in game), so tests need it on their own classpath.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

tasks.test {
    useJUnitPlatform()
    inputs.files(fileTree("release-notes")).withPropertyName("releaseNotes")
}

/**
 * Dev backend URL for the client run — see the `soul.backendUrl` property below.
 * Read at configure time, so changing local.properties takes effect on the next
 * `runClient` with no edit to a tracked file.
 */
val soulBackendUrl: String = run {
    val fromEnv = System.getenv("SOUL_BACKEND_URL")?.takeIf { it.isNotBlank() }
    val fromLocal = rootProject.file("local.properties")
        .takeIf { it.isFile }
        ?.let { f -> Properties().also { p -> f.inputStream().use(p::load) } }
        ?.getProperty("soul.backendUrl")
        ?.takeIf { it.isNotBlank() }
    (fromEnv ?: fromLocal ?: "http://localhost:29500").trimEnd('/')
}

/** Print the backend URL `runClient` will use — the check before launching a client
 *  against a paired backend lane. `./gradlew -q soulBackendUrl` */
tasks.register("soulBackendUrl") {
    group = "soul"
    description = "Print the dev backend URL runClient will use."
    val resolved = soulBackendUrl
    doLast { println(resolved) }
}

/**
 * The Wayland socket this session is using, or null when there is none.
 *
 * Read from `XDG_RUNTIME_DIR` (falling back to `/run/user/<uid>`) rather than from
 * `WAYLAND_DISPLAY`, because the whole point is to work when that variable is missing — which is
 * the case for any client launched from a supervised dev stack rather than from a login shell.
 */
val waylandDisplay: String? by lazy {
    if (System.getenv("WAYLAND_DISPLAY")?.isNotBlank() == true) return@lazy System.getenv("WAYLAND_DISPLAY")
    // No XDG_RUNTIME_DIR means no user session to find a socket in — null, rather than a guess.
    val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return@lazy null
    // `java.io.File` needs the import above: bare `java` resolves to the Java plugin extension here.
    File(runtimeDir).listFiles()
        ?.map { it.name }
        ?.filter { it.matches(Regex("wayland-\\d+")) }
        ?.minOrNull()
}

// Dev-only companion mod `soul-devbridge` (src/devbridge): a loopback event stream + snapshot server
// for live research (tools/devbridge/listen). On the runClient classpath and packaged by
// `devbridgeJar` for the agent test client, never part of `jar`/`build` or the release.
val devbridge: SourceSet = sourceSets.create("devbridge") {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}

// The world capture's pure format code (Packed sections) is unit tested from src/test.
dependencies {
    testImplementation(devbridge.output)
}

loom {
    // Widens `AbstractContainerScreen.hoveredSlot` (protected) for the dev clipboard dumps.
    accessWidenerPath = rootProject.file("src/main/resources/soul.accesswidener")

    mods {
        register("soul") { sourceSet(sourceSets.main.get()) }
        register("soul-devbridge") { sourceSet(devbridge) }
    }

    runs {
        // Share the launch configuration between CLI and IDE runs.
        named("client") {
            source(devbridge)
            // Formerly IntelliJ-only VM args. `property(k, v)` becomes a `-Dk=v` JVM arg.
            property("devauth.enabled", "1")
            property("devauth.microsoft.oauth2.grant-flow", "device-code")
            property("devauth.microsoft.oauth2.predefined-provider", "multimc")
            // Take the Wayland GLFW backend instead of XWayland. GLX pins the game to X11
            // whenever both platforms are supported and SharedConstants.DEBUG_PREFER_WAYLAND is
            // false, and under XWayland Hyprland's input path corrupts held keys and mouse
            // deltas (camera spinning to the floor, keys that never release). The flag only
            // reads at all with MC_DEBUG_ENABLED set — debugFlag() returns false otherwise.
            property("MC_DEBUG_ENABLED", "true")
            property("MC_DEBUG_PREFER_WAYLAND", "true")
            // Resolve the backend per checkout, allowing local.properties overrides.
            property("soul.backendUrl", soulBackendUrl)

            // Pin the game directory to the PRIMARY checkout's run/ so Minecraft settings
            // (options.txt, servers.dat, saves, resourcepacks, config) persist no matter which
            // worktree launches the client. Without this, each worktree gets a fresh empty run/
            // and you'd lose all settings every launch. DevAuth's token cache lives in
            // ~/.dev-auth-neo (home), so login persists independently of this.
            programArgs("--gameDir", "/home/soul/projects/SoulMod/run")

            // Gradle daemons may predate the desktop session; pass the discovered display explicitly.
            waylandDisplay?.let { environmentVariable("WAYLAND_DISPLAY", it) }
        }
    }
}

java {
    // 26.2 requires Java 25.
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${base.archivesName.get()}" }
    }
}

tasks.named<ProcessResources>("processDevbridgeResources") {
    // The Soul bridge API this bridge is built for, from the one constant in DevBridgeApi.kt.
    val bridgeApi = Regex("const val VERSION = (\\d+)")
        .find(file("src/main/kotlin/com/soulreturns/features/dev/DevBridgeApi.kt").readText())!!.groupValues[1]
    inputs.property("version", project.version)
    inputs.property("bridgeApi", bridgeApi)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "bridgeApi" to bridgeApi)
    }
}

/** The dev bridge mod for the agent test client (`tools/testclient/c install|update`). Outside build/libs on purpose. */
tasks.register<Jar>("devbridgeJar") {
    group = "soul"
    description = "Package the dev-only soul-devbridge mod into build/devbridge/."
    from(devbridge.output)
    archiveBaseName.set("soul-devbridge")
    destinationDirectory.set(layout.buildDirectory.dir("devbridge"))
}

/** Diagnostic: what the client run will set WAYLAND_DISPLAY to (blank = nothing found). */
tasks.register("soulWaylandDisplay") {
    doLast { println(waylandDisplay ?: "") }
}
