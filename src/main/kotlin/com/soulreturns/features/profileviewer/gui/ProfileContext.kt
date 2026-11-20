package com.soulreturns.features.profileviewer.gui

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkillCaps
import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.platform.presence.PresenceService
import kotlinx.serialization.json.JsonObject

/**
 * Everything a [ProfileSection] may read about the player on screen, rebuilt every frame by the
 * screen. Raw Hypixel JSON is on [member]`.raw` and [profile]`.raw`; typed accessors belong in a
 * per-section-group file under `data/hypixel/` (see `mocks/profile-viewer/IMPLEMENTATION.md`).
 *
 * @property caps this profile's skill caps from Soul's server, null when it did not answer
 *   ([capsKnown] false) — the Skills section then notes the unknown cap.
 * @property soulAnswer the raw soul-stats answer, null while loading. [soulState] is the decided state.
 * @property nowMs the frame's clock, for "N min ago" and countdowns.
 */
class ProfileContext(
    val identity: PlayerIdentity,
    val profiles: SkyblockProfiles,
    val profile: SkyblockProfile,
    val member: SkyblockProfileMember,
    val isSelf: Boolean,
    val caps: SkillCaps?,
    val capsLoaded: Boolean,
    val soulAnswer: SoulStatsAnswer?,
    val nowMs: Long,
    private val opener: (String) -> Unit,
    private val looker: (String) -> Unit,
) {
    val name: String get() = identity.name

    /** Caps answered for this profile (false while loading or when the endpoint said nothing). */
    val capsKnown: Boolean get() = capsLoaded && caps != null

    val soulState: String = ProfileViewerState.soul(soulAnswer, isSelf)

    /** A soul-stats namespace payload, or null (not shared, not recorded, still loading). */
    fun soulNamespace(name: String): JsonObject? = soulAnswer?.envelope?.namespaces?.get(name)

    /** Every soul-stats namespace in the backend's order. */
    val soulNamespaces: Map<String, JsonObject> get() = soulAnswer?.envelope?.namespaces ?: emptyMap()

    /** Decoded item blobs; see [ProfileItems]. */
    val items: ProfileItems get() = ProfileItems

    /** Switch to section [id] (call from a click lambda). */
    fun open(id: String) = opener(id)

    /** Look up another player (a co-op member's name, a party member). */
    fun lookup(name: String) = looker(name)

    /** `"Your"` or `"<name>'s"`, for API-off and empty texts. */
    val possessive: String get() = if (isSelf) "Your" else "$name's"

    /** Your "Share my Soul stats" setting; only meaningful when [isSelf]. */
    val shareSoulStats: Boolean get() = cfg.profileViewer.shareSoulStats

    /** Flips "Share my Soul stats" exactly as the config screen does (the backend learns it from presence). */
    fun setShareSoulStats(on: Boolean) {
        ConfigManager.update { copy(profileViewer = profileViewer.copy(shareSoulStats = on)) }
        PresenceService.onPresencePreferenceChanged()
    }
}
