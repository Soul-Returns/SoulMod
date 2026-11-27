package com.soulreturns.data.skyblock

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.HypixelNetwork
import com.soulreturns.api.LocationApi
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.dev.DevState
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.PlayerTabOverlay
import net.minecraft.client.multiplayer.PlayerInfo
import net.minecraft.network.chat.Component
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard

/**
 * The single poller behind [SkyblockLocation], [SkyblockProfileState], [SkyblockState] and the
 * tab list widgets ([TabWidgets]).
 *
 * **One reader, one walk.** The legacy mod had three of these — `LocationReader`, `ProfileReader`,
 * `SkyblockReader` — each registering its own end-tick handler and each walking the whole tab list
 * for its own regex. That is three walks a tick for three patterns, and three places to fix when
 * Hypixel changes the tab layout. This walks the tab list once and reads the sidebar once, then feeds
 * every holder. Nothing else in the mod parses the tab list or scoreboard for SkyBlock state.
 *
 * **Poll rate.** Every [POLL_INTERVAL_TICKS] ticks (¼ s). Area and profile change on the order of once
 * a minute, so per-tick polling was 5× the work for no observable difference. A server change asks for
 * a poll on the very next tick, so a warp is never waiting on the interval.
 *
 * **A missing signal is not a departure.** Hypixel leaves the tab list empty for seconds after a join,
 * and the sidebar can blink out while chunks load. A `null` read therefore has to survive
 * [NULL_CONFIRM_POLLS] consecutive polls (~½ s) before it clears a known-good area or sublocation,
 * while any non-null read applies at once. Without that, a transient blank tab list published a
 * spurious "left the area, came back" pair — the flapping that downstream "did I leave?" logic
 * misreads. The profile goes further and never clears on a miss at all; see [profile].
 *
 * **Clearing is driven by signals, not by absence.** A server change clears the *place* values
 * ([SkyblockLocation], [SkyblockState.profileType]) at once, bypassing the debounce, so nothing can
 * read a stale island mid-warp — legacy waited for the next poll to return null and left one readable.
 * A disconnect clears everything. The profile is cleared on disconnect and when [SkyblockState]
 * transitions off SkyBlock, which are the only two things that actually end it.
 *
 * **What a warp looks like on the bus:** `AreaChanged(Hub -> null)` the moment the location packet
 * lands, then `AreaChanged(null -> Dwarven Mines)` once the new tab list syncs — two events, because
 * two things happened. `ProfileChanged` does not fire at all unless the profile really changed.
 *
 * No network calls, no config, no toggle: this is infrastructure every SkyBlock feature reads.
 */
object SkyblockStateReader {
    /** ¼ s. Area/profile move on the order of a minute; the sidebar sublocation, seconds. */
    private const val POLL_INTERVAL_TICKS = 5

    /** Consecutive misses before a `null` read is believed. 2 polls ≈ ½ s. */
    private const val NULL_CONFIRM_POLLS = 2

    /** The island, from the tab list. `Area: Dwarven Mines`. */
    private val AREA_PATTERN = Regex("Area:\\s*(.+)")

    /**
     * Profile name, ported verbatim from the legacy `ProfileReader`. The parenthesised qualifier is
     * the load-bearing part: Hypixel renders co-op and gamemode profiles as `Profile (Co-op): Banana`
     * / `Profile (Stranded): Banana`, so a `startsWith("Profile:")` check misses every co-op player.
     */
    private val PROFILE_PATTERN = Regex("Profile(?:\\s*\\([^)]+\\))?:\\s*([A-Za-z][A-Za-z0-9_]*)")

    /**
     * A sublocation name: ASCII letters, spaces and apostrophes, **anchored on a letter**.
     *
     * Deliberately restrictive at the end — it stops at the state indicators Hypixel appends to the
     * location line, such as the Garden's pest counter (`The Garden ൠ x8`) or a dungeon's `(F5)`.
     * `\p{L}` would *match* `ൠ`, since it is classified as a letter, which defeats the purpose.
     *
     * Deliberately letter-anchored at the start, which the legacy pattern was not: it had no leading
     * `[a-zA-Z]` because it only ever ran on the text *after* the `⏣`, so nothing non-alphabetic was
     * left in front of it. Run against a whole line, `[a-zA-Z\\s']+` matches the single leading SPACE
     * and trims to nothing — which is exactly why the sublocation stayed null even once the line was
     * being found. Anchoring on a letter is what actually makes this skip a leading marker glyph.
     */
    private val SUBLOCATION_NAME_PATTERN = Regex("[a-zA-Z][a-zA-Z\\s']*")

    /**
     * The SkyBlock clock, e.g. `5:20pm ☀`. This is the anchor for the location line — see
     * [readSubLocation].
     */
    private val SIDEBAR_CLOCK_PATTERN = Regex("\\d{1,2}:\\d{2}\\s*[ap]m", RegexOption.IGNORE_CASE)

    /**
     * The glyphs Hypixel has used to mark the sidebar's location line, newest first. Written as
     * escapes on purpose — one of them cannot be typed or seen at all, and the other is
     * indistinguishable by eye from several similar glyphs.
     *
     *  - `U+E067` — current (2026-08-11). A **private-use-area** codepoint from Hypixel's own resource
     *    pack: it renders as their location icon in game, as nothing anywhere else, and it does not
     *    survive a copy-paste, which is what made this change so quiet.
     *  - `U+23E3` `⏣` — what they used for years, and what every SkyBlock mod still looks for.
     *
     * Both are matched, so neither a revert nor a repeat of this change costs anything here. Add the
     * next one to the front of this list; `/soul dev scoreboard` prints the codepoints.
     */
    private val LOCATION_MARKERS = charArrayOf('\uE067', '\u23E3')

    /**
     * Vanilla's tab list order (private `PlayerTabOverlay.PLAYER_COMPARATOR`; unobfuscated 26.2 keeps
     * the reflection stable, as in DungeonRunState). Hypixel names its fake entries `!A-a`..`!D-t`, so
     * the entry-name fallback gives the same order there.
     */
    private val tabOrder: Comparator<PlayerInfo> by lazy {
        try {
            val field = PlayerTabOverlay::class.java.getDeclaredField("PLAYER_COMPARATOR")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(null) as Comparator<PlayerInfo>
        } catch (t: Throwable) {
            SoulLogger("Soul/Skyblock").warn("Could not reflect the tab list comparator, sorting by entry name", t)
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.profile.name }
        }
    }

    private var ticks = 0
    private var pollNextTick = false

    private val area = PolledValue(SkyblockLocation::updateArea)
    private val subLocation = PolledValue(SkyblockLocation::updateSubLocation)

    // The profile is identity, not a place: it survives a warp, and the tab list is empty for seconds
    // after one. Letting a miss clear it would fire ProfileChanged(Banana -> null -> Banana) on every
    // single warp, and a consumer that reloads profile data on that event would refetch each time.
    // It is cleared only by the authoritative paths — see [clearProfile].
    private val profile = PolledValue(SkyblockProfileState::updateProfile, clearsOnMiss = false)

    fun register() {
        DevState.register("skyblock") {
            linkedMapOf(
                "on_skyblock" to SkyblockState.onSkyblock,
                "profile_type" to SkyblockState.profileType.name,
                "area" to SkyblockLocation.area,
                "sub_location" to SkyblockLocation.subLocation,
                "profile" to SkyblockProfileState.current,
                "profile_id" to SkyblockProfileState.profileId,
                "mode" to LocationApi.mode,
                "server_id" to LocationApi.serverId,
                "tab_widgets" to TabWidgets.snapshot,
            )
        }
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { mc ->
                ticks++
                if (pollNextTick || ticks % POLL_INTERVAL_TICKS == 0) {
                    pollNextTick = false
                    poll(mc)
                }
            },
        )

        // A server change invalidates every *place* the tab list and sidebar were describing. Clear now
        // so nothing can read a stale island mid-warp, and re-acquire on the next tick rather than up
        // to ¼ s later. The profile is not a place and is left alone (see [profile]).
        //
        // `onSkyblock` is deliberately NOT touched here: it comes from LocationApi, which is fed by
        // this same event, so reconciling it in the next tick's poll keeps LocationApi the single
        // source of that answer instead of depending on which subscriber the bus calls first.
        Events.subscribe<HypixelModApi.LocationChangeEvent> {
            clearPlaceState()
            pollNextTick = true
        }

        // Hypixel's profile lines name the profile before the tab list syncs and carry its exact id,
        // which nothing else on screen shows. The name goes through the same PolledValue as the tab
        // list read, so the later tab read of the same name is a no-op. Both name forms (join and
        // `/profile` switch) count: the name must move before its id line, or a later tab read of
        // the new name would clear the id just announced.
        Events.subscribe<ChatMessage> { msg ->
            if (msg.source != ChatMessage.Source.SERVER) return@subscribe
            val line = MessageDetector.stripColorCodes(msg.raw).trim()
            ProfileChatLines.name(line)?.let(profile::offer)
            ProfileChatLines.id(line)?.let(SkyblockProfileState::updateProfileId)
        }

        // DISCONNECT does NOT always run on the client thread: on a client shutdown it fires on the
        // netty IO thread (observed as `[Netty Epoll IO #1]` in the log), which would race the tick
        // poll over this reader's state and — worse — hand every AreaChanged/ProfileChanged consumer a
        // thread it cannot touch Minecraft from. Everything here stays single-threaded instead.
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> onClientThread { clearAll() } }
    }

    private fun clearAll() {
        clearPlaceState()
        clearProfile()
        TabWidgets.clear()
        // Not routed through LocationApi: its own DISCONNECT handler runs independently, and
        // "disconnected" means "not on SkyBlock" without needing to know which ran first.
        SkyblockState.updateOnSkyblock(false)
    }

    /**
     * Run now if we are already on the client thread, otherwise hand it to the client's task queue.
     *
     * A queued task submitted during shutdown may never run, because the loop it would drain on has
     * already ended — which is fine and is the reason this is not `executeBlocking`: the only thing
     * lost is a state reset nothing can observe in a process that is exiting.
     */
    private inline fun onClientThread(crossinline block: () -> Unit) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) block() else mc.execute { block() }
    }

    /** Everything that is scoped to the server you are on. */
    private fun clearPlaceState() {
        area.clear()
        subLocation.clear()
        SkyblockState.updateProfileType(ProfileType.UNKNOWN)
        TabWidgets.clearIsland()
        SkyblockSidebar.clear()
    }

    private fun clearProfile() = profile.clear()

    private fun poll(mc: Minecraft) {
        // Leaving SkyBlock is the only thing that ends a profile without a disconnect. Act on the
        // TRANSITION, not on the state: if the location packet never arrives at all (an unsupported
        // server, or Hypixel changing the packet), `inSkyblock` is simply always false, and a
        // state-based check would then wipe a profile the tab list is reporting perfectly well.
        val wasOnSkyblock = SkyblockState.onSkyblock
        SkyblockState.updateOnSkyblock(LocationApi.inSkyblock)
        if (wasOnSkyblock && !SkyblockState.onSkyblock) {
            clearProfile()
            TabWidgets.clear()
        }

        val connection = mc.player?.connection
        if (connection == null) {
            // No world: "nothing to read", not "left the area". The debounce decides.
            area.offer(null)
            subLocation.offer(null)
            return
        }

        // One walk, in the order the tab list draws it: area and profile (order-independent) plus the
        // widget lines for TabWidgets (order-dependent: a widget is a header and the lines under it).
        // Legacy text, because the pet's rarity is only in its name's color.
        val ordered = connection.listedOnlinePlayers.sortedWith(tabOrder)
        val tabLines = ArrayList<String>(ordered.size)
        var freshArea: String? = null
        var freshProfile: String? = null
        for (info in ordered) {
            val legacy = info.tabListDisplayName?.toLegacyText().orEmpty()
            tabLines += legacy
            if (legacy.isEmpty()) continue
            val line = MessageDetector.stripColorCodes(legacy).trim()
            if (freshArea == null) {
                freshArea = AREA_PATTERN.matchEntire(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            }
            if (freshProfile == null) {
                freshProfile = PROFILE_PATTERN.find(line)?.groupValues?.get(1)
            }
        }
        // A dungeon tab list has NO `Area:` entry at all (it carries `Dungeon: Catacombs` instead), so
        // the tab list alone leaves the area unknown for a whole run. The location packet's `map` fills
        // that hole: it carries the SAME vocabulary as the tab list — verified `map=Dwarven Mines` with
        // `Area: Dwarven Mines`, `map=Private Island` with `Area: Private Island` — and reads `Dungeon`
        // exactly where the tab list is silent.
        //
        // It is a fallback, never an override, so every island the tab list does describe keeps the tab
        // list's own wording and the two can never disagree about the same place. Gated on `inSkyblock`
        // because `map` is populated on other Hypixel games too, where it is a minigame map name and has
        // no business being a SkyBlock area.
        val packetArea = if (LocationApi.inSkyblock) LocationApi.map?.ignorePlaceholder() else null
        area.offer(freshArea.ignorePlaceholder() ?: packetArea)
        profile.offer(freshProfile)
        // After the profile: a switch clears TabWidgets (ProfileChanged), then this fills it anew.
        if (SkyblockState.onSkyblock) TabWidgets.apply(tabLines)

        // One sidebar read, every scoreboard-derived value: the title gives the profile flavour, the
        // lines give the sublocation, the footer names the network (HypixelNetwork).
        val scoreboard = connection.scoreboard()
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (objective == null) {
            subLocation.offer(null)
            SkyblockSidebar.clear()
            // Leave profileType alone: the flavour cannot change without a profile switch, and a
            // profile switch moves you to another instance, which clears it via LocationChangeEvent.
            return
        }
        SkyblockState.updateProfileType(
            ProfileType.fromScoreboardTitle(MessageDetector.stripColorCodes(objective.displayName.string).trim()),
        )
        // Hypixel renders the sidebar highest score first, and position is what identifies the
        // location line and the footer, so this ordering is load-bearing rather than cosmetic.
        val rendered =
            scoreboard.listPlayerScores(objective)
                .sortedByDescending { it.value() }
                .map { renderLine(scoreboard, it.owner(), it.ownerName()) }
        val lines = rendered.map { it.string }
        SkyblockSidebar.update(objective.displayName.toLegacyText(), rendered.map { it.toLegacyText() })
        HypixelNetwork.onSidebar(lines)
        subLocation.offer(readSubLocation(lines.map(MessageDetector::stripColorCodes)))
    }

    /**
     * The sublocation, from the sidebar's location line.
     *
     * **Hypixel swapped the `⏣` marker for a private-use glyph** (2026-08-11): the line now reads
     * `§7\uE067 §aYour Island` where it used to read `§7⏣ §aYour Island`. Every SkyBlock mod finds this
     * line by searching for `⏣`, so they all stopped finding it — and because `U+E067` renders as
     * nothing outside the game and is dropped by a copy-paste, the line *looks* like it simply lost its
     * marker. So both glyphs are matched, and the line's **position** is kept as a fallback for the
     * next time this happens, since the sidebar's top block is stable enough to anchor on:
     *
     * ```
     * 08/11/26 m61A     <- real-world date + instance id
     *                   <- blank
     * Late Autumn 9th   <- SkyBlock date
     * 5:20pm ☀          <- clock            } the anchor
     * Dwarven Village   <- location         } directly beneath it
     * ```
     *
     * So: look for any known marker first (see [LOCATION_MARKERS]), otherwise take the line below the
     * clock. Either way [SUBLOCATION_NAME_PATTERN] extracts the name, skipping a leading marker glyph
     * whether or not we recognise it.
     *
     * Verified against four real sidebars (2026-08-11): Private Island `Your Island`, Dwarven Mines
     * `Dwarven Village`, Lotus Atoll `Lotus Atoll`, and Catacombs F5 `The Catacombs (F5)` → `The
     * Catacombs`. **A dungeon sidebar does carry a clock line** (`8:50pm`, without the weather glyph
     * the overworld ones have), so the anchor holds inside a run.
     *
     * **Known gap:** a sidebar with no clock at all yields `null` — no such sidebar has been seen, but
     * the Rift renders its own clock format and is worth checking when a Rift feature lands.
     *
     * Diagnose with `/soul dev scoreboard`, which prints every line with non-ASCII escaped — the only
     * way to tell a missing glyph from one that merely looks right.
     */
    private fun readSubLocation(lines: List<String>): String? {
        for (line in lines) {
            val marker = line.indexOfFirst { it in LOCATION_MARKERS }
            if (marker >= 0) return extractName(line.substring(marker + 1))
        }

        val clock = lines.indexOfFirst { SIDEBAR_CLOCK_PATTERN.containsMatchIn(it) }
        if (clock < 0) return null
        return extractName(lines.getOrNull(clock + 1) ?: return null)
    }

    /** The first run of name-ish characters, or `null` if the line holds none (a blank separator). */
    private fun extractName(text: String): String? =
        SUBLOCATION_NAME_PATTERN.find(text)?.value?.trim()?.ifEmpty { null }?.ignorePlaceholder()

    /**
     * Drop Hypixel's `None` placeholder.
     *
     * For about two seconds after a server transfer Hypixel reports the location as the literal string
     * `None` — observed on a Hub → Hub warp, with the real `Village` arriving two seconds later. It is
     * a placeholder for "not decided yet", not a place, and publishing it as one hands every consumer
     * a transition to somewhere that does not exist, plus two extra events per warp.
     *
     * Treating it as unknown is also why it costs nothing: an unknown value is exactly what the poll
     * already handles, and the real name lands as a single clean transition when it arrives.
     */
    private fun String?.ignorePlaceholder(): String? = if (this == null || equals("None", ignoreCase = true)) null else this

    /**
     * Rebuild a sidebar line the way vanilla renders it: the score holder's team prefix, then the
     * holder's own name, then the suffix. Hypixel keeps the visible text in the prefix and suffix and
     * uses the holder name as an invisible per-line key, so reading either half alone loses most of
     * the line.
     */
    private fun renderLine(
        scoreboard: Scoreboard,
        owner: String,
        ownerName: Component,
    ): Component {
        val team = scoreboard.getPlayersTeam(owner) ?: return ownerName
        return PlayerTeam.formatNameForTeam(team, ownerName)
    }

    /**
     * A polled nullable value that trusts a reading immediately but distrusts its absence.
     *
     * [offer] applies any non-null read at once. A `null` read reaches [commit] only after
     * [NULL_CONFIRM_POLLS] consecutive misses — so a tab list that has not synced yet cannot
     * masquerade as a departure — and only when [clearsOnMiss]; a value whose disappearance carries no
     * meaning (the profile) ignores misses entirely and waits for [clear]. [clear] is the
     * authoritative path and skips the debounce.
     *
     * One class rather than three copies of the counter: getting this subtly different in three places
     * is precisely the failure mode this reader exists to remove.
     */
    private class PolledValue(
        private val commit: (String?) -> Unit,
        private val clearsOnMiss: Boolean = true,
    ) {
        private var last: String? = null
        private var misses = 0

        fun offer(fresh: String?) {
            if (fresh == null && last != null) {
                if (!clearsOnMiss) return
                if (++misses < NULL_CONFIRM_POLLS) return
            }
            misses = 0
            last = fresh
            commit(fresh)
        }

        fun clear() {
            misses = 0
            last = null
            commit(null)
        }
    }
}
