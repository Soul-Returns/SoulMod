package com.soulreturns.config.gui

/**
 * The rows of `/soul` → About → Used software, and the single place a new third-party thing is
 * declared. Rule: anything the jar bundles, requires at runtime, or contains data / code / tables
 * taken or adapted from another project gets a row **in the same commit** (AGENTS.md,
 * rule://porting). `UsedSoftwareTest` fails when a bundled dependency, a bundled resource file or a
 * "ported from X" comment has no row; it also fails when a row's [Entry.covers] points at something
 * that is gone.
 *
 * Ideas are not copyrightable, verbatim tables and translated code are: a project only consulted
 * for behavior is a [Kind.REFERENCE] row (thanks, no obligations); everything else carries its
 * license, and the notice text the license asks for lives in `assets/soul/licenses/` (the test
 * checks that every bundled row is named in `THIRD-PARTY-NOTICES.md`).
 */
object UsedSoftware {
    enum class Kind {
        /** A library jar nested into ours (Loom `include`). */
        BUNDLED_LIB,

        /** Source code (or a shader) adapted from the project. */
        BUNDLED_CODE,

        /** Data, tables, fonts or textures taken from the project and shipped in the jar or the source. */
        BUNDLED_DATA,

        /** Not in the jar but required to run (Fabric API, Fabric Language Kotlin). */
        REQUIRED,

        /** A public data service the mod or its backend reads. */
        SERVICE,

        /** Only consulted for behavior or numbers; nothing copied. */
        REFERENCE,
    }

    /**
     * One row. [what] is the text next to the name; the license is appended in parentheses.
     * [covers] says what the row accounts for, which is what the test checks:
     *  - `mod:<id>`: a `depends` entry of `fabric.mod.json`;
     *  - `lib:<group>:<artifact>`: a dependency coordinate in `build.gradle.kts`;
     *  - `res:<path>`: a file or directory under `src/main/resources/` (prefix match);
     *  - `font:<family>`: a bundled font family (TTF / MSDF atlas file name prefix, lowercase).
     * [aliases] are the spellings the name takes in source comments (`ported from <alias>`).
     */
    class Entry(
        val name: String,
        val what: String,
        val url: String,
        val license: String?,
        val kind: Kind,
        val covers: List<String> = emptyList(),
        val aliases: List<String> = emptyList(),
    ) {
        val text: String get() = if (license == null) what else "$what ($license)"
    }

    /** Roughly by relevance to a SkyBlock player; platform and tooling last. */
    val entries: List<Entry> = listOf(
        Entry(
            "devonian", "Dungeon room dataset, block-id table, secret-sync relay protocol", "https://github.com/Synnerz/devonian", "GPL-3.0",
            Kind.BUNDLED_DATA, covers = listOf("res:assets/soul/dungeons"),
        ),
        Entry(
            "SkyHanni", "Scoreboard titles, rarity and SkyBlock level colors, sea-creature and price semantics", "https://github.com/hannibal002/SkyHanni", "LGPL-2.1",
            Kind.BUNDLED_DATA,
        ),
        Entry(
            "Skyblocker", "Arrow Align layouts, Metal Detector chest spots, secret-sync message format; storage compatibility and API field reference", "https://github.com/SkyblockerMod/Skyblocker", "LGPL-3.0",
            Kind.BUNDLED_DATA,
        ),
        Entry(
            "Odin", "Rounded-rectangle shader; the leap menu's class corners, sorting and the class colors (also used on the dungeon map); reference for terminal, device and boss-timer behavior", "https://github.com/odtheking/Odin", "BSD-3-Clause",
            Kind.BUNDLED_CODE, covers = listOf("res:assets/soul/shaders/core/round_rect"),
        ),
        Entry(
            "NoFrills", "Reference for device solvers and inventory buttons, no code copied", "https://github.com/WhatYouThing/NoFrills", "GPL-3.0",
            Kind.REFERENCE,
        ),
        Entry(
            "Ascent M7 Guides", "Archer strategy reference by kix0n, checked against recorded runs; no guide text or images copied", "https://discord.com", null,
            Kind.REFERENCE,
        ),
        Entry(
            "SBCombo", "Kill Combo bonus table and chat patterns by Eval, Debug, MPG1 and Altpapier; no license file, kept with credit by the maintainer's decision", "https://github.com/Eval427/SBCombo", null,
            Kind.BUNDLED_DATA,
        ),
        Entry(
            "SkyCrypt", "Slayer XP table, skill and Catacombs XP cross-checks; storage API indexing reference", "https://github.com/SkyCryptWebsite/SkyCrypt", "MIT",
            Kind.BUNDLED_DATA,
        ),
        Entry(
            "NotEnoughUpdates REPO", "Pet level XP table", "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO", "MIT",
            Kind.BUNDLED_DATA, aliases = listOf("NEU"),
        ),
        Entry(
            "SkyHelper-Networth", "Pet level table, Catacombs class XP deltas; storage API field reference", "https://github.com/Altpapier/SkyHelper-Networth", "MIT",
            Kind.BUNDLED_DATA, aliases = listOf("SkyHelper"),
        ),
        Entry(
            "Hypixel API", "Bazaar, items, profiles and skill XP curves, read through the Soul backend", "https://api.hypixel.net", null,
            Kind.SERVICE,
        ),
        Entry(
            "Elite SkyBlock", "Public lowest-BIN mirror, upstream of /prices", "https://api.eliteskyblock.com/resources/auctions/neu", null,
            Kind.SERVICE,
        ),
        Entry(
            "Inter", "Bundled UI font, MSDF", "https://github.com/rsms/inter", "SIL OFL 1.1",
            Kind.BUNDLED_DATA, covers = listOf("font:inter"),
        ),
        Entry(
            "Nunito", "Bundled UI font, MSDF", "https://github.com/googlefonts/nunito", "SIL OFL 1.1",
            Kind.BUNDLED_DATA, covers = listOf("font:nunito"),
        ),
        Entry(
            "Baloo 2", "Bundled UI font, MSDF", "https://github.com/EkType/Baloo2", "SIL OFL 1.1",
            Kind.BUNDLED_DATA, covers = listOf("font:baloo2"),
        ),
        Entry(
            "msdf-atlas-gen", "Generates the MSDF atlas, text shader technique", "https://github.com/Chlumsky/msdf-atlas-gen", "MIT",
            Kind.BUNDLED_CODE, covers = listOf("res:assets/soul/msdf", "res:assets/soul/textures/font", "res:assets/soul/shaders/core/msdf_text"),
        ),
        Entry(
            "Hypixel Mod API", "Location / party packets, bundled", "https://github.com/HypixelDev/ModAPI", "MIT",
            Kind.BUNDLED_LIB, covers = listOf("mod:hypixel-mod-api", "lib:maven.modrinth:hypixel-mod-api", "lib:net.hypixel:mod-api"),
        ),
        Entry(
            "JLayer", "MP3 decoder for imported sounds, bundled", "https://central.sonatype.com/artifact/javazoom/jlayer/1.0.1", "LGPL-2.0-or-later",
            Kind.BUNDLED_LIB, covers = listOf("lib:javazoom:jlayer"),
        ),
        Entry(
            "Fabric Loader", "Mod loader", "https://github.com/FabricMC/fabric-loader", "Apache-2.0",
            Kind.REQUIRED, covers = listOf("mod:fabricloader", "lib:net.fabricmc:fabric-loader"),
        ),
        Entry(
            "Fabric API", "Mod platform", "https://github.com/FabricMC/fabric", "Apache-2.0",
            Kind.REQUIRED, covers = listOf("mod:fabric-api", "lib:net.fabricmc.fabric-api:fabric-api"),
        ),
        Entry(
            "Fabric Language Kotlin", "Kotlin runtime and coroutines", "https://github.com/FabricMC/fabric-language-kotlin", "Apache-2.0",
            Kind.REQUIRED, covers = listOf("mod:fabric-language-kotlin", "lib:net.fabricmc:fabric-language-kotlin"),
        ),
        Entry(
            "kotlinx.serialization", "Config persistence", "https://github.com/Kotlin/kotlinx.serialization", "Apache-2.0",
            Kind.REQUIRED, covers = listOf("lib:org.jetbrains.kotlinx:kotlinx-serialization-json"),
        ),
    )

    /**
     * Files under `src/main/resources/` that are the project's own work (no row needed). A new
     * resource that is neither here nor covered by an [Entry] fails `UsedSoftwareTest`.
     */
    val ownResources: List<String> = listOf(
        "fabric.mod.json",
        "soul.accesswidener",
        "soul.mixins.json",
        "assets/soul/lang",
        // Icons are drawn as SVG paths in mocks/config-menu/mock.js and rasterized by scripts/config_icons.mjs.
        "assets/soul/textures/gui/icons",
        "assets/soul/textures/gui/combo_star.png",
        "assets/soul/textures/gui/map_arrow.png",
        "assets/soul/shaders/core/pet_glow.fsh",
        // The license and notice texts themselves.
        "assets/soul/licenses",
        "assets/soul/fonts/OFL-",
    )

    /** Words that follow `ported from` / `copied from` / `adapted from` in comments and are the project's own sources. */
    val internalSources: Set<String> = setOf("legacy", "mock", "mocks", "vanilla", "minecraft")
}
