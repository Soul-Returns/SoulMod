// Build logic only: the pure version rule (ModVersion). Its tests live in the root project's
// src/test (compiled there from this source dir, see build.gradle.kts), so `./gradlew test` and CI cover it.
plugins {
    kotlin("jvm") version "2.4.0"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}
