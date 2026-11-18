plugins {
    java
}

// Compiles against the Paper server that `s` unpacked (26.x ships unobfuscated, so server internals
// are plain Mojang names and need no paperweight). `s build` passes the run dir; default is ../run.
val paperServerDir: File = file(providers.gradleProperty("paperServerDir").getOrElse("../run"))
val paperJars = fileTree(paperServerDir) {
    include("versions/**/*.jar", "libraries/**/*.jar")
}

repositories {
    mavenCentral()
    maven("https://repo.hypixel.net/repository/Hypixel/") { name = "Hypixel" }
}

dependencies {
    compileOnly(paperJars)

    // Round-trip tests decode the plugin's bytes with Hypixel's own mod-api classes.
    testImplementation("net.hypixel:mod-api:1.0.2")
    testImplementation(fileTree(paperServerDir) { include("libraries/io/netty/**/*.jar") })
    // The experiment model tests read the item JSON resources and fixtures with Paper's own Gson.
    testImplementation(fileTree(paperServerDir) { include("libraries/com/google/code/gson/**/*.jar") })
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

tasks.jar {
    archiveFileName = "SoulTestKit.jar"
}

tasks.processResources {
    doFirst {
        check(paperServerDir.resolve("versions").isDirectory) {
            "No unpacked Paper server in $paperServerDir; run tools/testserver/s build"
        }
    }
    // CorpseSim places corpses on the mod's own spot list.
    from("../../../src/main/resources/assets/soul/mineshaft/corpse_spots.json")
}
