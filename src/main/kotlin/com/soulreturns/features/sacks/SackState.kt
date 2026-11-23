package com.soulreturns.features.sacks

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SelfProfileLoaded
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import com.soulreturns.stats.TrackerSessions
import kotlinx.serialization.Serializable

/** Where a stored count last came from. */
enum class SackSource { API, MENU, CHAT }

/**
 * One item's count in the sacks.
 *
 * @property updatedAt epoch ms of the last change from any source.
 * @property exactAt epoch ms of the last exact read from a sack menu, 0 if never.
 */
@Serializable
data class SackCount(
    val count: Long = 0L,
    val source: SackSource = SackSource.API,
    val updatedAt: Long = 0L,
    val exactAt: Long = 0L,
)

/**
 * One tracked item's session: net change since it started.
 *
 * @property startClock window start on [TrackerSessions.clock] (client running time, continued across
 *   restarts), so a restart does not count the offline gap; 0 = nothing changed yet.
 */
@Serializable
data class SackSession(val net: Long = 0L, val startClock: Long = 0L)

/** The `sacks` namespace payload: one profile's counts by SkyBlock id, plus the tracked items' sessions. */
@Serializable
data class SackProfileData(
    val items: Map<String, SackCount> = emptyMap(),
    val sessions: Map<String, SackSession> = emptyMap(),
)

/** The `sackNames` namespace payload: normalized display name → SkyBlock id, learned from sack menus. */
@Serializable
data class SackNameData(val byName: Map<String, String> = emptyMap())

/**
 * Sack contents per profile, from three sources:
 * - **API prefill**: `sacks_counts` of the self profile ([SkyblockSelfProfile]) replaces counts that
 *   were not seen since this profile became active (older data from a previous session).
 * - **Menu reads** ([SackMenus]): `Stored: N/cap` lore, exact; live while a sack menu stays open.
 * - **Chat deltas**: the `[Sacks]` summary's hover breakdown, names resolved through menu-learned
 *   names first, then the item catalog. Changes a menu read already counted are held in
 *   [PendingMenuDeltas] and consumed by the matching summary line, so each change counts once.
 *
 * Counts and the session (net change and its window per tracked item) persist per profile
 * (`stats.json`, namespace `sacks`). The session follows the shared tracker-session lifetime
 * ([TrackerSessions]) and also resets on a real profile switch, on the panel's reset and when an item
 * gets tracked. Client thread only, like every event it consumes.
 */
object SackState {
    const val NAMESPACE = "sacks"
    const val NAMES_NAMESPACE = "sackNames"

    /** Rates use at least this window, so the first gain does not read as millions per hour. */
    private const val MIN_RATE_WINDOW_MS = 60_000L

    /** A menu read of an unchanged count is re-stamped at most this often (each stamp is a stats write). */
    private const val EXACT_RESTAMP_MS = 60_000L

    private val counts: StatsHandle<SackProfileData> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = SackProfileData.serializer(),
            default = { SackProfileData() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    private val names: StatsHandle<SackNameData> by lazy {
        PersistentStats.namespace(
            id = NAMES_NAMESPACE,
            serializer = SackNameData.serializer(),
            default = { SackNameData() },
            key = { "names" },
        )
    }

    private fun sessions(): Map<String, SackSession> = counts.read().sessions

    /** Menu-counted changes the next `[Sacks]` summary will report again. */
    private val pending = PendingMenuDeltas()

    private var activeProfile: String? = null

    /** When [activeProfile] became active; counts older than this are from a previous session. */
    private var profileSince = System.currentTimeMillis()

    /** For `/soul dev sacks`. */
    var lastSummary: String = "none yet"
        private set

    val unresolvedNames = LinkedHashSet<String>()

    fun register() {
        TrackerSessions.resetOnFreshStart(counts) { copy(sessions = emptyMap()) }
        Events.subscribe<ProfileChanged> { event ->
            val current = event.current ?: return@subscribe
            // Leaving SkyBlock reports null and coming back the same name: not a switch.
            if (current == activeProfile) return@subscribe
            // The first profile after launch resumes its stored session; only a real switch resets.
            val switched = activeProfile != null
            activeProfile = current
            profileSince = System.currentTimeMillis()
            pending.clear()
            if (switched) resetSessions()
        }
        Events.subscribe<SelfProfileLoaded> { prefill() }
        Events.subscribe<ChatMessage> { onChat(it) }
    }

    /** Other features that need the counts (the Forge tab's owned sacks); see [enabled]. */
    private val consumers = java.util.concurrent.CopyOnWriteArrayList<() -> Boolean>()

    /** Keep counting whenever [needed] says so, even with the Sack tracker off. */
    fun requireCounts(needed: () -> Boolean) {
        consumers += needed
    }

    /** Counts are collected (menus, chat, API) while the Sack tracker or another consumer needs them. */
    fun enabled(): Boolean = cfg.sacks.hud || consumers.any { it() }

    fun count(id: String): SackCount? = counts.read().items[id]

    fun allCounts(): Map<String, SackCount> = counts.read().items

    /** Drops [id]'s stored count, for ids a sack row only borrows ([SackMenus.itemId]). */
    fun forget(id: String) {
        if (id in counts.read().items) counts.update { copy(items = items - id) }
    }

    fun learnedNames(): Map<String, String> = names.read().byName

    fun profileKey(): String = counts.currentKey()

    /** [id]'s session, or null while nothing changed since tracking or reset. */
    fun session(id: String): SackSession? = sessions()[id]

    /** Session time of [id] on [TrackerSessions.clock], or null before the first change. */
    fun sessionElapsedMs(id: String): Long? {
        val session = sessions()[id] ?: return null
        if (session.startClock == 0L) return null
        return (TrackerSessions.clock() - session.startClock).coerceAtLeast(0L)
    }

    /** Items per hour over the session window, or null before the first change. */
    fun ratePerHour(id: String): Double? {
        val elapsed = sessionElapsedMs(id) ?: return null
        return sessions().getValue(id).net * 3_600_000.0 / elapsed.coerceAtLeast(MIN_RATE_WINDOW_MS)
    }

    /** Time since the earliest running item session started (the panel's session clock), or null before any change. */
    fun sessionElapsedMs(): Long? = sessions().keys.mapNotNull { sessionElapsedMs(it) }.maxOrNull()

    fun resetSessions() {
        if (sessions().isNotEmpty()) counts.update { copy(sessions = emptyMap()) }
    }

    /** [id] just got tracked: start its session fresh and fill its count from the API if unknown. */
    fun onTracked(id: String) {
        onUntracked(id)
        prefill()
    }

    fun onUntracked(id: String) {
        if (id in sessions()) counts.update { copy(sessions = sessions - id) }
    }

    /**
     * An exact read from a sack menu. A change against a count seen during this profile session also
     * moves the session; one against older data is just a correction.
     */
    fun onMenuRead(id: String, displayName: String, count: Long, now: Long) {
        learnName(displayName, id)
        val prev = count(id)
        if (prev != null && prev.count == count && prev.source == SackSource.MENU && now - prev.exactAt < EXACT_RESTAMP_MS) return
        if (prev != null && prev.count != count) {
            pending.record(id, count - prev.count, now)
            if (prev.updatedAt >= profileSince) recordChange(id, count - prev.count, 0L)
        }
        counts.update { copy(items = items + (id to SackCount(count, SackSource.MENU, now, now))) }
    }

    /** Apply the self profile's `sacks_counts` to counts not seen since this profile became active. */
    fun prefill() {
        if (!enabled()) return
        val snapshot = SkyblockSelfProfile.snapshot ?: return
        if (!snapshot.profileName.equals(SkyblockProfileState.current, ignoreCase = true)) return
        val api = snapshot.member.sacks
        if (api.isEmpty()) return
        val at = snapshot.loadedAt.toEpochMilli()
        counts.update {
            val next = HashMap(items)
            for ((id, count) in api) {
                val prev = next[id]
                if (prev == null || prev.updatedAt < profileSince || (prev.source == SackSource.API && prev.updatedAt < at)) {
                    next[id] = SackCount(count, SackSource.API, at, prev?.exactAt ?: 0L)
                }
            }
            copy(items = next)
        }
    }

    private fun onChat(message: ChatMessage) {
        if (!enabled() || !SkyblockState.onSkyblock || message.source != ChatMessage.Source.SERVER) return
        val plain = SackParsing.strip(message.raw)
        val window = SackParsing.summaryWindow(plain) ?: return
        val component = message.component ?: return
        val lines = SackParsing.summaryLines(SackParsing.hoverTexts(component))
        applySummary(window, lines, System.currentTimeMillis())
        lastSummary = "${lines.size} item lines, window ${window}s: $plain"
    }

    internal fun applySummary(windowSeconds: Int, lines: List<SackParsing.SummaryLine>, now: Long) {
        if (lines.isEmpty()) return
        val windowMs = windowSeconds.coerceAtLeast(1) * 1000L
        val updates = HashMap<String, SackCount>()
        for (line in lines) {
            val id = resolve(line.name)
            if (id == null) {
                if (unresolvedNames.size < 200) unresolvedNames += line.name
                continue
            }
            val delta = line.delta - pending.consume(id, line.delta, now, windowMs)
            if (delta == 0L) continue
            val prev = updates[id] ?: count(id)
            if (prev != null) {
                updates[id] = prev.copy(count = (prev.count + delta).coerceAtLeast(0L), source = SackSource.CHAT, updatedAt = now)
            }
            recordChange(id, delta, windowMs)
        }
        if (updates.isNotEmpty()) counts.update { copy(items = items + updates) }
    }

    /** Menu-learned names first (exact), then the item catalog's display names. */
    fun resolve(name: String): String? {
        val key = SackParsing.normalizeName(name)
        names.read().byName[key]?.let { return it }
        return ItemCatalogClient.byDisplayName(SackParsing.strip(name).trim())?.id
    }

    private fun learnName(displayName: String, id: String) {
        val key = SackParsing.normalizeName(displayName)
        if (key.isEmpty() || names.read().byName[key] == id) return
        names.update { copy(byName = byName + (key to id)) }
    }

    /**
     * A change of a tracked item. The window starts at the first one, backdated by the chat summary's
     * window: the summary reports items gathered over the previous N seconds.
     */
    private fun recordChange(id: String, delta: Long, windowMs: Long) {
        if (delta == 0L || !cfg.sacks.hud || cfg.sacks.tracked.none { it.id == id }) return
        counts.update {
            val prev = sessions[id] ?: SackSession()
            val start = if (prev.startClock == 0L) (TrackerSessions.clock() - windowMs).coerceAtLeast(1L) else prev.startClock
            copy(sessions = sessions + (id to SackSession(prev.net + delta, start)))
        }
    }
}
