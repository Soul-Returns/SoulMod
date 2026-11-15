package com.soulreturns.platform.updater

/** Pure rules for when the updater acts by itself, so they are testable. */
object UpdatePolicy {
    /**
     * Startup and hourly checks, the title-screen question and offer. A development run
     * (`FabricLoader.isDevelopmentEnvironment`, the same notion as [ModsFolder.locate]) stays silent;
     * only an explicit check (`/soul update check`, Check now) and `/soul dev update` previews run there.
     */
    fun automatic(isDevelopment: Boolean): Boolean = !isDevelopment
}
