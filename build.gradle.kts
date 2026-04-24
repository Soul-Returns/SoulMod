plugins {
	id("fabric-loom") version "1.15-SNAPSHOT"
    `maven-publish`
	id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("com.gradleup.shadow") version "8.3.5"
}

version = "${property("mod.version")}+${stonecutter.current.version}"
base.archivesName = property("archives_base_name") as String

val requiredJava = when {
    stonecutter.eval(stonecutter.current.version, ">=1.21.0") -> JavaVersion.VERSION_21
    else -> JavaVersion.VERSION_1_8
}

repositories {
    /**
     * Restricts dependency search of the given [groups] to the [maven URL][url],
     * improving the setup speed.
     */
    fun strictMaven(url: String, alias: String, vararg groups: String) = exclusiveContent {
        forRepository { maven(url) { name = alias } }
        filter { groups.forEach(::includeGroup) }
    }
    strictMaven("https://www.cursemaven.com", "CurseForge", "curse.maven")
    strictMaven("https://api.modrinth.com/maven", "Modrinth", "maven.modrinth")

    mavenCentral()
    maven("https://jitpack.io")
    maven ("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1")
    maven("https://maven.wispforest.io")
}

dependencies {
    // Extra fabric api modules
//    val apiModules = setOf(
//        "fabric-rendering-v1"
//    )

    minecraft("com.mojang:minecraft:${stonecutter.current.version}")

	mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")

//    apiModules.forEach {
//        modImplementation(fabricApi.module(it, property("deps.fabric_api") as String))
//    }

    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("deps.fabric_api")}")

    // MixinExtras
    modImplementation("io.github.llamalad7:mixinextras-fabric:0.4.1")
    include("io.github.llamalad7:mixinextras-fabric:0.4.1")

	modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")

    // owo-lib (config + UI framework)
    modImplementation("io.wispforest:owo-lib:${project.property("deps.owo")}")
    annotationProcessor("io.wispforest:owo-lib:${project.property("deps.owo")}")
    include("io.wispforest:owo-lib:${project.property("deps.owo")}")

    modRuntimeOnly("me.djtheredstoner:${project.property("deps.devauth")}")

    // DevAuth depends on Apache HttpClient; include it as a plain runtime library
    runtimeOnly("org.apache.httpcomponents:httpclient:4.5.14")
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json") // Useful for interface injection
    accessWidenerPath = rootProject.file("src/main/resources/soul.accesswidener")

    decompilerOptions.named("vineflower") {
        options.put("mark-corresponding-synthetics", "1") // Adds names to lambdas - useful for mixins
    }

    runConfigs.all {
        ideConfigGenerated(true)
        vmArgs("-Dmixin.debug.export=true") // Exports transformed classes for debugging
        runDir = "../../run" // Shares the run directory between versions
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).all {
	compilerOptions {
		jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
	}
}

// owo-config's annotation processor generates SoulConfig.java from SoulConfigModel.java.
// Kotlin code (SoulConfigHolder, every feature) references the generated SoulConfig class,
// so we run the AP in a dedicated task BEFORE both compileKotlin and compileJava and add
// its output to the Java sourceset.
val generatedConfigSrcDir = layout.buildDirectory.dir("generated/sources/owoConfig/java/main")
val generatedConfigClassesDir = layout.buildDirectory.dir("generated/classes/owoConfig/java/main")

val generateOwoConfig by tasks.registering(JavaCompile::class) {
    group = "build"
    description = "Runs the owo-config annotation processor to generate SoulConfig.java."
    // Stonecutter relocates sources, so derive the source from the main sourceset rather than a hardcoded path.
    source = sourceSets.named("main").get().java.matching { include("**/SoulConfigModel.java") }
    classpath = configurations.compileClasspath.get() + configurations.annotationProcessor.get()
    options.annotationProcessorPath = configurations.annotationProcessor.get()
    options.generatedSourceOutputDirectory.set(generatedConfigSrcDir)
    destinationDirectory.set(generatedConfigClassesDir)
    sourceCompatibility = requiredJava.toString()
    targetCompatibility = requiredJava.toString()
    options.compilerArgs.add("-proc:full")
}

sourceSets.named("main") {
    java.srcDir(generatedConfigSrcDir)
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configureEach {
    dependsOn(generateOwoConfig)
}
tasks.withType<Jar>().configureEach {
    dependsOn(generateOwoConfig)
}
tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateOwoConfig)
    // The AP already ran in generateOwoConfig and produced the source file; don't re-run it here.
    options.compilerArgs.add("-proc:none")
}




java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
}



tasks {
    processResources {
        inputs.property("id", project.property("mod.id"))
        inputs.property("name", project.property("mod.name"))
        inputs.property("version", project.property("mod.version"))
        inputs.property("minecraft", project.property("mod.mc_dep"))

        val props = mapOf(
            "id" to project.property("mod.id"),
            "name" to project.property("mod.name"),
            "version" to project.property("mod.version"),
            "minecraft" to project.property("mod.mc_dep")
        )

        filesMatching("fabric.mod.json") { expand(props) }

        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    // Builds the version into a shared folder in `build/libs/${mod version}/`
    register<Copy>("buildAndCollect") {
        group = "build"
        from(remapJar.map { it.archiveFile }, remapSourcesJar.map { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
        dependsOn("build")
    }
}