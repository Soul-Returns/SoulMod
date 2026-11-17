package com.soulreturns.config.gui.menu

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.ConfigMenuState
import com.soulreturns.config.SeenNew
import com.soulreturns.config.cfg

/** One What's-new line of a release, naming the features (`f:<card>`) or Settings tabs (`s:<tab>`) it touches. */
class ReleaseNote(val text: String, val ids: List<String>)

/**
 * NEW per mod version (spec: `RELEASES` in `mocks/config-menu/mock.js`): what each release added
 * or changed. Only the running version's set is NEW. Every release needs an entry for its exact
 * version, written with the `release-notes` skill (`make bump` refuses without it).
 */
object Releases {
    val notes: Map<String, List<ReleaseNote>> =
        mapOf(
            "0.3.1-beta.9" to
                listOf(
                    ReleaseNote("Experimentation Table: solvers for all three games that block misclicks", listOf("f:experiments")),
                ),
            "0.3.1-beta.8" to listOf(),
            "0.3.1-beta.7" to listOf(),
            "0.3.1-beta.6" to
                listOf(
                    ReleaseNote("Search everywhere: a Forge tab that plans forge recipes from what you own", listOf("f:searcheverywhere")),
                ),
            "0.3.1-beta.5" to listOf(),
            "0.3.1-beta.4" to
                listOf(
                    ReleaseNote("Commissions HUD: your commissions with their progress", listOf("f:commissionshud")),
                    ReleaseNote("Highlight completed commissions in the Commissions menu", listOf("f:commissionsdone")),
                    ReleaseNote("Hide Powder Coating particles around Divan armor", listOf("f:powdercoating")),
                ),
            "0.3.1-beta.3" to
                listOf(
                    ReleaseNote("Search everywhere: one search over island chests and storage", listOf("f:searcheverywhere", "f:islandchests", "f:storageoverlay")),
                ),
            "0.3.1-beta.2" to
                listOf(
                    ReleaseNote("Storage overlay: Ender Chest and backpacks in one searchable wall", listOf("f:storageoverlay")),
                ),
            "0.3.1-beta.1" to
                listOf(
                    ReleaseNote("Island chests: blue slot highlights for the item you are locating", listOf("f:islandchests")),
                ),
            "0.3.0" to
                listOf(
                    ReleaseNote("Terminal solver: all six F7/M7 terminals show what to click", listOf("f:terminals")),
                    ReleaseNote("Device solvers: Simon Says, Lights, Arrow Align and Sharpshooter", listOf("f:devices")),
                    ReleaseNote("Leap menu: teammate tiles with locations and dungeon map highlighting", listOf("f:leapmenu", "f:dmap")),
                    ReleaseNote("Archer roles: your next objective and who to leap to in F7/M7", listOf("f:archerroles", "f:leapmenu")),
                    ReleaseNote("Island chests: search stored items and enchantments, share with your co-op", listOf("f:islandchests")),
                    ReleaseNote("Boss timers: pad tick, crush window, Storm phases and Goldor countdowns", listOf("f:bosstimers")),
                    ReleaseNote("Dragon spawn countdown and Goldor's section not open warning", listOf("f:dragoncountdown", "f:sectionwarning")),
                    ReleaseNote("Hide fire and the Haunted Skulls from Soulweaver Gloves", listOf("f:hidefire", "f:soulskulls")),
                ),
            "0.3.0-beta.6" to
                listOf(
                    ReleaseNote("Island chests: search books by enchantment and see their names and levels", listOf("f:islandchests")),
                    ReleaseNote("Island chests: compact locator, Escape to cancel, sharing defaults on", listOf("f:islandchests")),
                ),
            "0.3.0-beta.5" to
                listOf(
                    ReleaseNote("Island chests: one search key, also editable in /soul keybinds", listOf("f:islandchests")),
                    ReleaseNote("Updates: restart prompt appears in-game only while idle", listOf("s:about")),
                ),
            "0.3.0-beta.4" to
                listOf(
                    ReleaseNote("Island chests: find stored items and share remembered chests with your co-op", listOf("f:islandchests")),
                    ReleaseNote("Archer roles: your next objective and who to leap to in F7/M7", listOf("f:archerroles", "f:leapmenu")),
                ),
            "0.3.0-beta.3" to
                listOf(
                    ReleaseNote("Leap menu: marks the hovered teammate on the dungeon map, tiles show at once", listOf("f:leapmenu", "f:dmap")),
                    ReleaseNote("Dungeon map: class colors match the leap menu", listOf("f:dmap")),
                    ReleaseNote("Updates: the update window opens before the main menu", listOf("s:about")),
                    ReleaseNote("Boss timers: terminals countdown and death tick exact to the tick", listOf("f:bosstimers")),
                    ReleaseNote("Dragon spawn countdown ends on time on Hypixel", listOf("f:dragoncountdown")),
                    ReleaseNote("Terminal solver: Melody ignores teammates' sounds more reliably", listOf("f:terminals")),
                ),
            "0.3.0-beta.2" to
                listOf(
                    ReleaseNote("Leap menu: Spirit Leap with each class in its own corner and where everyone is", listOf("f:leapmenu")),
                    ReleaseNote("Dragon spawn countdown for M7 phase 5", listOf("f:dragoncountdown")),
                    ReleaseNote("Section not open warning for Goldor's phase", listOf("f:sectionwarning")),
                    ReleaseNote("Boss timers: terminals-open countdown, steadier crush and death tick", listOf("f:bosstimers")),
                    ReleaseNote("Device solvers: Lights handles a grid lit in Storm's phase", listOf("f:devices")),
                ),
            "0.3.0-beta.1" to
                listOf(
                    ReleaseNote("Terminal solver: Goldor's terminals in a clear Soul window", listOf("f:terminals")),
                    ReleaseNote("Device solvers: Simon Says, Lights, Arrow Align and Sharpshooter", listOf("f:devices")),
                    ReleaseNote("Boss timers: pad tick, crush window and Storm's phases at your crosshair", listOf("f:bosstimers")),
                    ReleaseNote("Hide Soulweaver skulls in dungeons", listOf("f:soulskulls")),
                    ReleaseNote("Hide fire: no flames on your screen or on burning entities", listOf("f:hidefire")),
                ),
            "0.2.1" to
                listOf(
                    ReleaseNote("Auto size: picks the UI size that fits your monitor", listOf("s:interface")),
                    ReleaseNote("Updates show on the title screen, with a one-time question about automatic updates", listOf("s:about")),
                ),
            "0.1.2" to
                listOf(
                    ReleaseNote("Equipment beside your armor, optionally inside the inventory frame", listOf("f:equipment")),
                    ReleaseNote("Inventory buttons: fixed rings around the inventory plus screen-edge spots", listOf("f:invbuttons")),
                    ReleaseNote("Move GUI rebuilt: attach HUDs to each other and to vanilla elements", listOf("s:huds")),
                    ReleaseNote("Crystal Hollows weather: mute the sounds and hide the visuals separately", listOf("f:weathermute", "f:weatherhide")),
                    ReleaseNote("Scavenged tools counter for Metal Detector runs", listOf("f:toolscounter")),
                    ReleaseNote("Pet HUD: your active pet with its level, XP bar and XP per hour", listOf("f:pethud")),
                    ReleaseNote("Pause menu: Soul GUI and Soul Config buttons", listOf("s:shortcuts")),
                ),
        )

    fun of(version: String): List<ReleaseNote> = notes[version].orEmpty()
}

/** Pure rules of the menu state, unit-tested; [MenuState] applies them to the live config. */
object MenuRules {
    const val RECENT_MAX = 3

    fun withRecent(state: ConfigMenuState, cardId: String): ConfigMenuState {
        if (state.recent.firstOrNull() == cardId) return state
        return state.copy(recent = (listOf(cardId) + state.recent.filter { it != cardId }).take(RECENT_MAX))
    }

    fun withPinToggled(state: ConfigMenuState, cardId: String): ConfigMenuState =
        state.copy(pins = if (cardId in state.pins) state.pins - cardId else state.pins + cardId)

    /** Moves [cardId] to [index] in the pin order (index counted without it). */
    fun withPinMoved(state: ConfigMenuState, cardId: String, index: Int): ConfigMenuState {
        if (cardId !in state.pins) return state
        val rest = state.pins.filter { it != cardId }.toMutableList()
        rest.add(index.coerceIn(0, rest.size), cardId)
        return state.copy(pins = rest)
    }

    fun newIds(version: String): List<String> = Releases.of(version).flatMap { it.ids }

    fun seenIds(state: ConfigMenuState, version: String): List<String> = if (state.seenNew.version == version) state.seenNew.ids else emptyList()

    /** NEW = in [version]'s release and not seen yet (panel opened, toggled, reached via search, tab visited). */
    fun isNew(state: ConfigMenuState, version: String, id: String): Boolean = id in newIds(version) && id !in seenIds(state, version)

    fun withSeen(state: ConfigMenuState, version: String, id: String): ConfigMenuState {
        if (!isNew(state, version, id)) return state
        return state.copy(seenNew = SeenNew(version, seenIds(state, version) + id))
    }

    /** A What's-new line is NEW while any id it names is unseen; seen lines stay listed, just without the emphasis. */
    fun isNoteNew(state: ConfigMenuState, version: String, note: ReleaseNote): Boolean = note.ids.any { isNew(state, version, it) }

    /** The jump target of a line: its first unseen id, else its first id (the line stays clickable once seen). */
    fun noteTarget(state: ConfigMenuState, version: String, note: ReleaseNote): String =
        note.ids.firstOrNull { isNew(state, version, it) } ?: note.ids.first()
}

/** The live menu state in `config.json` (`menu`), changed through [MenuRules]. */
object MenuState {
    val version: String get() = com.soulreturns.Soul.version

    private fun update(block: (ConfigMenuState) -> ConfigMenuState) {
        val now = cfg.menu
        val next = block(now)
        if (next != now) ConfigManager.update { copy(menu = next) }
    }

    /** A feature was toggled or edited in the menu: Recently changed, and it counts as seen. */
    fun touch(cardId: String) = update { MenuRules.withSeen(MenuRules.withRecent(it, cardId), version, "f:$cardId") }

    fun togglePin(cardId: String) = update { MenuRules.withPinToggled(it, cardId) }

    fun movePin(cardId: String, index: Int) = update { MenuRules.withPinMoved(it, cardId, index) }

    fun isNew(id: String): Boolean = MenuRules.isNew(cfg.menu, version, id)

    fun markSeen(id: String) = update { MenuRules.withSeen(it, version, id) }

    fun set(block: ConfigMenuState.() -> ConfigMenuState) = update { it.block() }
}
