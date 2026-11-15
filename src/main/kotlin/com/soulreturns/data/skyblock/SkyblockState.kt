package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.util.SoulLogger

/**
 * Whether the player is on SkyBlock, and which flavour of profile they are on.
 *
 * **Two sources answer "am I on SkyBlock" and they answer different questions.** Both are kept, one
 * is exposed:
 *
 *  - [onSkyblock] comes from the Hypixel Mod API location packet
 *    ([com.soulreturns.api.LocationApi.inSkyblock], `serverType == "SKYBLOCK"`). It is authoritative
 *    about *which server type you were routed to*: no parsing, and it is already true in a SkyBlock
 *    lobby whose scoreboard has not rendered yet. **This is the one to gate features on.**
 *  - The scoreboard sidebar *title* is authoritative about *whether the SkyBlock UI is actually up*,
 *    and is the only source that distinguishes Co-op / Guest / Bingo / Stranded / Ironman. That is
 *    exposed as [profileType], not as a second boolean — a feature asking "am I on SkyBlock" should
 *    never have to pick between two answers.
 *
 * Subscribe to [OnSkyblockChanged] to react to entering/leaving SkyBlock.
 */
object SkyblockState {
    private val logger = SoulLogger("Soul/Skyblock")

    @Volatile
    var onSkyblock: Boolean = false
        private set

    /** [UNKNOWN] until the SkyBlock sidebar renders; reset on server change and disconnect. */
    @Volatile
    var profileType: ProfileType = ProfileType.UNKNOWN
        private set

    /** [SkyblockStateReader] only. Publishes [OnSkyblockChanged] on transition. */
    internal fun updateOnSkyblock(new: Boolean) {
        if (new == onSkyblock) return
        onSkyblock = new
        logger.debug("On SkyBlock: $new")
        Events.publish(OnSkyblockChanged(!new, new))
    }

    /** [SkyblockStateReader] only. Silent — no event; nothing has asked for one yet. */
    internal fun updateProfileType(new: ProfileType) {
        if (new == profileType) return
        logger.debug("Profile type: ${profileType.name} -> ${new.name}")
        profileType = new
    }
}

/**
 * The flavour of the active SkyBlock profile, as advertised by the scoreboard sidebar title.
 *
 * A profile can be more than one of these at once (a co-op ironman profile is both), so this is a
 * single-valued *summary*, resolved in the priority order documented on [fromScoreboardTitle].
 * Anything that needs the exact combination should read the title itself.
 *
 * Dispatch on this with `if`/`else if` or a `when` over [name] — **never** `when (enumValue)`, which
 * makes Kotlin emit a synthetic `$WhenMappings` class that Fabric's KnotClassLoader can fail to
 * resolve.
 */
enum class ProfileType {
    /** A plain profile. */
    NORMAL,

    /** A shared profile — sidebar title `SKYBLOCK CO-OP`. */
    COOP,

    /** Visiting someone else's island — `SKYBLOCK GUEST`. Not your profile at all. */
    GUEST,

    /** No trading, no bazaar, no auction house — `SKYBLOCK ♲`. */
    IRONMAN,

    /** The solo-island game mode — `SKYBLOCK ☀`. */
    STRANDED,

    /** A bingo card profile — `SKYBLOCK Ⓑ`. */
    BINGO,

    /** The sidebar is absent or its title is not a SkyBlock title (including: not on SkyBlock). */
    UNKNOWN,

    ;

    companion object {
        /**
         * The set of SkyBlock sidebar titles, lifted verbatim from SkyHanni's
         * `SkyBlockLocationData.scoreboardTitlePattern` (LGPL-2.1, attributed in
         * `/soul` → About → Used Software). They have hit every variant in production:
         * `SKYBLOCK`, `SKYBLOCK CO-OP`, `SKYBLOCK GUEST`, and the one-char mode markers — plus the
         * `SKIBLOCK` typo that ships on Hypixel Alpha.
         *
         * An exact-equality check was tried in the legacy mod and missed Co-op / Bingo / Stranded
         * profiles entirely. Do not "simplify" this.
         */
        private val TITLE_PATTERN = Regex("SK[YI]BLOCK(?: CO-OP| GUEST)?(?: [♲☀Ⓑ])?")

        /**
         * Resolve the flavour from an already color-stripped, trimmed sidebar title, or [UNKNOWN] if
         * it is not a SkyBlock title.
         *
         * Priority when a title carries more than one marker: [GUEST] (you are not on your own
         * profile, so nothing else about it is relevant) → mode marker → [COOP] → [NORMAL].
         */
        fun fromScoreboardTitle(strippedTitle: String): ProfileType {
            if (!TITLE_PATTERN.matches(strippedTitle)) return UNKNOWN
            return if (strippedTitle.contains("GUEST")) {
                GUEST
            } else if (strippedTitle.endsWith('♲')) {
                IRONMAN
            } else if (strippedTitle.endsWith('☀')) {
                STRANDED
            } else if (strippedTitle.endsWith('Ⓑ')) {
                BINGO
            } else if (strippedTitle.contains("CO-OP")) {
                COOP
            } else {
                NORMAL
            }
        }
    }
}

/** Entered or left SkyBlock, per the Hypixel Mod API location packet. */
data class OnSkyblockChanged(val previous: Boolean, val current: Boolean) : Event
