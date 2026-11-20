package com.soulreturns.features.profileviewer

import com.soulreturns.config.cfg
import com.soulreturns.data.hypixel.HypixelProfileClient
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.PlayerIdentityResolver
import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.calc.LevelCalculator
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MojangUuid
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import java.util.Locale

/**
 * The `/sp` flow: validate the name → resolve it to a uuid → fetch that player's SkyBlock profiles
 * → choose one → hand it to the renderer.
 *
 * ### The renderer is a seam — three of them
 * The flow decides nothing about presentation. It announces a lookup starting
 * ([onLookupStarted]), hands over a loaded profile ([present]) and hands over a failure
 * ([presentFailure]); all three default to the chat behaviour this lane shipped with, and
 * `ProfileViewerScreen.register()` repoints them at the screen without touching anything below.
 *
 * The failure and start seams exist because a `Screen` covers Minecraft's chat HUD: a screen that
 * opens on `/sp` must be able to say "loading" and "that player does not exist" *itself*, since a
 * chat line printed behind it cannot be read. Every failure still prints its chat line as well, so
 * nothing is lost for anyone who never opens the screen.
 *
 * ### Why suspend functions
 * The legacy version was a three-stage `CompletableFuture` chain on its own thread pool, which made
 * the error paths hard to follow (each stage had to decide whether a null meant "already reported" or
 * "keep going"). This is one linear function on [SoulScope.io], with every failure reported where it
 * happens.
 */
object ProfileViewerService {
    private val logger = SoulLogger("Soul/Spv")

    /**
     * Where a loaded profile goes. Replace to render somewhere other than chat.
     */
    @Volatile
    var present: (ProfileView) -> Unit = ::renderToChat

    /**
     * Called the moment a lookup starts, with the trimmed name, so a renderer can show a loading
     * state before the network work finishes. Runs on the caller's thread (the client thread, for
     * `/sp`). Default: nothing — the chat renderer already prints its own "Loading …" line.
     */
    @Volatile
    var onLookupStarted: (String) -> Unit = {}

    /**
     * Called once the name resolved to an account, before the profiles are fetched, so a renderer
     * can show the player's face and exact name early. Runs on [SoulScope.io]. Default: nothing.
     */
    @Volatile
    var onResolved: (PlayerIdentity) -> Unit = {}

    /**
     * Where a lookup that produced no profile goes, **in addition to** the chat line it always
     * prints. Runs on whichever thread the failure happened on — usually [SoulScope.io], so a
     * renderer that touches Minecraft must hop to the client thread itself.
     */
    @Volatile
    var presentFailure: (ProfileLookupFailure) -> Unit = {}

    /**
     * Start the flow for [rawName]. Returns immediately; everything happens on [SoulScope.io] and
     * reports to chat.
     *
     * @param profileOverride a `cute_name` to show instead of the selected profile.
     */
    fun open(rawName: String, profileOverride: String? = null) {
        SpvFixtures.seedLocal(null)
        onLookupStarted(rawName.trim())
        SoulScope.io.launch { load(rawName, profileOverride) }
    }

    /**
     * The flow itself. Every exit reports to chat, so a caller has nothing to handle.
     *
     * @return true when a profile was presented.
     */
    suspend fun load(rawName: String, profileOverride: String? = null): Boolean {
        val name = rawName.trim()
        if (!enabled()) {
            return fail(
                name,
                "§cThe Profile Viewer is disabled in §f/soul§c → Profile Viewer.",
                "Profile Viewer is off",
                "Turn it back on in /soul → Profile Viewer.",
            )
        }

        if (!PlayerIdentityResolver.isValidName(name)) {
            return fail(name, "§cNot a valid username: §f$rawName", "Not a valid username", rawName, ProfileLookupFailure.INVALID_NAME)
        }

        soulChat("§7Loading §f$name§7…")

        val resolved = ownIdentity(name)?.let { PlayerIdentityResolver.ResolveOutcome(it, PlayerIdentityResolver.ResolveOutcome.CACHED, "your session") }
            ?: PlayerIdentityResolver.resolveDetailed(name)
        val identity = resolved.identity
        if (identity == null) {
            return if (resolved.source == PlayerIdentityResolver.ResolveOutcome.UNKNOWN_PLAYER) {
                fail(name, "§cUnknown player: §f$name", "Unknown player", "Mojang has no account called \"$name\".", ProfileLookupFailure.NOT_FOUND)
            } else {
                fail(name, "§cCould not look up §f$name§c — ${resolved.detail}.", "Could not look up $name", resolved.detail, ProfileLookupFailure.OFFLINE)
            }
        }
        onResolved(identity)

        val fetch = HypixelProfileClient.profiles(identity.uuidUndashed, HypixelProfileClient.INTENT_LOOKUP)
        if (fetch !is BackendResult.Ok) {
            return reportFetchFailure(identity, fetch as BackendResult.Failure)
        }

        val profiles = fetch.value
        if (profiles.isEmpty) {
            return fail(
                identity.name,
                "§cNo SkyBlock profiles found for §f${identity.name}§c.",
                "No SkyBlock profiles",
                "${identity.name} has never played SkyBlock.",
                ProfileLookupFailure.NO_PROFILES,
                identity,
            )
        }

        val profile = profiles.pick(profileOverride)
        if (profile == null) {
            // Only reachable with an override that matched nothing — pick() falls back for the rest.
            return fail(
                identity.name,
                "§cProfile §f$profileOverride§c not found. Available: §7" +
                    profiles.cuteNames().joinToString("§8, §7"),
                "No profile called \"$profileOverride\"",
                "Available: " + profiles.cuteNames().joinToString(", "),
                ProfileLookupFailure.OTHER,
                identity,
            )
        }

        val member = profile.member(identity.uuidUndashed)
        if (member == null) {
            // Hypixel returns every profile the uuid is a member of, so this means the response and
            // its own members map disagree — worth saying rather than rendering a blank profile.
            logger.warn("members[] has no entry for ${identity.uuidUndashed} on profile ${profile.profileId}")
            return fail(
                identity.name,
                "§c§f${identity.name}§c is not a member of profile §f${profile.cuteName}§c.",
                "No data on that profile",
                "Hypixel lists ${identity.name} on ${profile.cuteName} but sent no member data for them.",
                ProfileLookupFailure.OTHER,
                identity,
            )
        }

        present(ProfileView(identity, profiles, profile, member, fetch.fromCache))
        return true
    }

    private fun reportFetchFailure(identity: PlayerIdentity, failure: BackendResult.Failure): Boolean {
        val name = identity.name
        val line = when (failure.status) {
            HypixelProfileClient.HYPIXEL_REFUSED -> "§cHypixel refused the lookup: §7${failure.message}"
            429 -> {
                val seconds = failure.retryAfter?.seconds
                "§cToo many profile lookups — try again in §f${seconds ?: 60}s§c." +
                    " §8(Hypixel allows 30 per minute; repeats within a minute are served from cache.)"
            }
            401, 403 -> "§cThe backend rejected the lookup (${failure.status}). §8Are you logged in?"
            BackendResult.Failure.NO_AUTH -> "§cNot authenticated with the Soul backend yet — try again in a moment."
            BackendResult.Failure.TIMEOUT -> "§cThe profile lookup timed out."
            BackendResult.Failure.TRANSPORT -> "§cCould not reach the Soul backend."
            else -> "§cProfile lookup failed: §7${failure.message}"
        }
        logger.debug("Profile fetch for '$name' failed: ${failure.status} ${failure.message}")
        val kind = when (failure.status) {
            429 -> ProfileLookupFailure.RATE_LIMITED
            BackendResult.Failure.NO_AUTH, BackendResult.Failure.TIMEOUT, BackendResult.Failure.TRANSPORT -> ProfileLookupFailure.OFFLINE
            else -> ProfileLookupFailure.OTHER
        }
        return fail(
            name,
            line,
            "Could not load $name",
            MessageDetector.stripColorCodes(line),
            kind,
            identity,
            failure.retryAfter?.seconds,
        )
    }

    /**
     * Report one failed lookup: the chat line every caller has always got, plus the same news through
     * [presentFailure] for a renderer that chat cannot reach. Always returns false, so a failure exit
     * in [load] is one `return fail(...)`.
     */
    private fun fail(
        name: String,
        chatLine: String,
        headline: String,
        detail: String? = null,
        kind: String = ProfileLookupFailure.OTHER,
        identity: PlayerIdentity? = null,
        retryAfterSeconds: Long? = null,
    ): Boolean {
        soulChat(chatLine)
        presentFailure(ProfileLookupFailure(name, headline, detail, kind, identity, retryAfterSeconds))
        return false
    }

    private fun enabled(): Boolean = try {
        cfg.profileViewer.enabled
    } catch (_: Throwable) {
        // Config not ready — do not block the user over it.
        true
    }

    /**
     * You, from the game session: your own lookup needs no name resolve, and the session spells your name
     * the way it is stored (an offline session's name can belong to a different account at Mojang).
     */
    private fun ownIdentity(name: String): PlayerIdentity? = try {
        val user = Minecraft.getInstance().user
        if (user.name.equals(name, ignoreCase = true)) PlayerIdentity(MojangUuid.undashed(user.profileId), user.name) else null
    } catch (_: Throwable) {
        null
    }

    // ------------------------------------------------------------------ chat rendering

    /**
     * A compact chat summary covering what the screen shows — Dungeons, Skills and Slayers.
     *
     * Still the **default** value of [present], and deliberately kept now that
     * `ProfileViewerScreen.register()` repoints that seam at a real screen: it is the surface that
     * works before the screen is wired (and if it ever fails to), it needs no render thread, and it
     * exercises the same member-view accessors `/soul dev profile` reads.
     */
    fun renderToChat(view: ProfileView) {
        val member = view.member
        val profile = view.profile

        val mode = if (profile.isSpecialGameMode) " §8(${profile.gameMode})" else ""
        val cached = if (view.fromCache) " §8· cached" else ""
        soulChat("§6${view.identity.name}§7 — profile §f${profile.cuteName}$mode$cached")

        val others = view.profiles.cuteNames().filter { !it.equals(profile.cuteName, ignoreCase = true) }
        if (others.isNotEmpty()) {
            soulChat("  §8profiles: §7" + others.joinToString("§8, §7") + " §8(/sp ${view.identity.name} <profile>)")
        }

        if (member.apiAccessDisabled) {
            soulChat("  §e⚠ Hypixel API access is off for this player — inventory data is hidden.")
        }

        // ---- Dungeons
        if (member.hasDungeons) {
            val cata = member.catacombsLevel
            soulChat(
                "  §bCatacombs §f${cata.level}§7 " + pct(cata.progress) +
                    " §8(${LevelCalculator.formatXp(member.catacombsExperience)} XP)",
            )
            val master = member.masterCatacombsLevel
            if (member.masterCatacombsExperience > 0) {
                soulChat("  §bMaster Catacombs §f${master.level}§7 " + pct(master.progress))
            }
            soulChat(
                "  §bClasses §8(avg §f" + String.format(Locale.ROOT, "%.2f", member.classAverage) + "§8) §7" +
                    member.dungeonClasses.joinToString("§8, §7") { klass ->
                        val marker = if (klass.selected) "§e*" else ""
                        "$marker${klass.displayName} §f${klass.level.level}§7"
                    },
            )
        } else {
            soulChat("  §8No dungeon data.")
        }

        // ---- Skills
        val skills = member.skills.filter { !it.def.cosmetic }
        if (skills.isEmpty()) {
            soulChat("  §8No skill data §7(API access off, or a brand-new profile).")
        } else {
            val average = member.skillAverage
            soulChat(
                "  §aSkills §8(avg §f" + String.format(Locale.ROOT, "%.2f", average ?: 0.0) +
                    "§8 over ${member.skillCount}) §7" +
                    skills.joinToString("§8, §7") { "${it.def.displayName} §f${it.level.level}§7" },
            )
        }

        // ---- Slayers
        val slayers = member.slayers.filter { it.experience > 0 }
        if (slayers.isEmpty()) {
            soulChat("  §8No slayer data.")
        } else {
            soulChat(
                "  §dSlayers §8(${LevelCalculator.formatXp(member.totalSlayerXp)} XP) §7" +
                    slayers.joinToString("§8, §7") { "${it.def.bossName} §f${it.level.level}§7" },
            )
        }
    }

    private fun pct(progress: Double): String =
        if (progress >= 1.0) "§8(max)" else String.format(Locale.ROOT, "§8(%.1f%%)", progress * 100.0)
}

/**
 * A lookup that produced no profile, in the words a screen can show. [headline] is one short line;
 * [detail] is the optional second line with the specifics (the available profile names, the backend's
 * message). Both are plain text — no `§` codes, unlike the chat line that accompanies them.
 *
 * @property kind one of the constants below, so a screen picks its state without parsing text.
 * @property identity the resolved account when the failure happened after resolving.
 * @property retryAfterSeconds the backend's `Retry-After` for [RATE_LIMITED].
 */
data class ProfileLookupFailure(
    val name: String,
    val headline: String,
    val detail: String? = null,
    val kind: String = OTHER,
    val identity: PlayerIdentity? = null,
    val retryAfterSeconds: Long? = null,
) {
    companion object {
        const val INVALID_NAME = "invalid-name"
        const val NOT_FOUND = "not-found"
        const val NO_PROFILES = "no-profiles"
        const val RATE_LIMITED = "rate-limited"

        /** Soul's server could not be reached (or it could not reach Mojang): transport, timeout, no auth. */
        const val OFFLINE = "offline"
        const val OTHER = "other"
    }
}

/**
 * Everything a renderer needs about one loaded profile: who it belongs to, every profile they have
 * (for the switcher), the chosen one, and that player's data inside it.
 *
 * @property preloaded side data that came with the view instead of from the network (dev fixtures);
 *   null means the renderer fetches Soul stats and skill caps itself.
 */
data class ProfileView(
    val identity: PlayerIdentity,
    val profiles: SkyblockProfiles,
    val profile: SkyblockProfile,
    val member: SkyblockProfileMember,
    /** True when the response came from the client cache rather than the network. */
    val fromCache: Boolean,
    val preloaded: PreloadedExtras? = null,
)

/**
 * Soul stats and skill caps handed over with a [ProfileView] (`/soul dev spv fixture`), and how old
 * the view should claim to be ([loadedAgoMs], for the stale state).
 */
data class PreloadedExtras(
    val soulStats: com.soulreturns.data.hypixel.SoulStatsAnswer,
    val caps: com.soulreturns.data.hypixel.PlayerSkillCaps?,
    val loadedAgoMs: Long = 0,
)
