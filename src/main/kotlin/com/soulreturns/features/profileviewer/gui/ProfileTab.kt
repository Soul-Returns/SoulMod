package com.soulreturns.features.profileviewer.gui

import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkyblockProfile
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.hypixel.SkillCaps
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.features.profileviewer.gui.tabs.DungeonsTab
import com.soulreturns.features.profileviewer.gui.tabs.OverviewTab
import com.soulreturns.features.profileviewer.gui.tabs.SkillsTab
import com.soulreturns.features.profileviewer.gui.tabs.SlayersTab
import com.soulreturns.features.profileviewer.gui.tabs.SoulStatsTab

/**
 * Everything a [ProfileTab] may read. Rebuilt by [ProfileViewerScreen] whenever the shown profile
 * changes, so [profile] and [member] are always the ones the pager is currently on — not the ones
 * the lookup happened to land on.
 *
 * @property soulStats what `GET /player/{uuid}/soul-stats` returned, or null for every reason it can
 *   be empty (not shared, unknown, rate limited, unreachable, no namespace registered yet). Null
 *   means *hide the Soul tab*, never "show an error" — see
 *   [com.soulreturns.data.hypixel.SoulStatsClient].
 * @property skillCaps the account-specific skill ceilings, or null when they could not be looked up —
 *   in which case a skill falls back to whatever cap the profile response alone can justify. Never an
 *   error; see [com.soulreturns.data.hypixel.SkillCapsClient].
 * @property self true when this is the local player looking at themselves.
 */
class ProfileContext(
    val identity: PlayerIdentity,
    val profiles: SkyblockProfiles,
    val profile: SkyblockProfile,
    val member: SkyblockProfileMember,
    val soulStats: SoulStatsEnvelope?,
    val skillCaps: SkillCaps?,
    val fromCache: Boolean,
    val self: Boolean,
)

/**
 * One tab of the Profile Viewer.
 *
 * A tab is a **stateless object in its own file**: it draws into the [ProfileCanvas] it is handed and
 * owns no layout, no scroll offset and no height arithmetic. Adding one means writing one file and
 * adding it to [ProfileTabs.ALL] — never editing the screen.
 *
 * [draw] runs on the render thread, may run **twice per frame** (the canvas measures a section before
 * it draws it — see [ProfileCanvas.section]) and must therefore be free of side effects. It gets no
 * click regions on purpose: a tab presents data, and anything interactive belongs in the screen's own
 * chrome where its state can live.
 */
interface ProfileTab {
    /** Stable id, for remembering the selected tab across a profile switch. */
    val id: String

    /** The label on the tab strip. */
    val label: String

    /**
     * Whether this tab exists for [ctx]. A tab with nothing to show is **absent from the strip**
     * rather than present and empty — an empty tab invites the user to wonder what broke.
     */
    fun visible(ctx: ProfileContext): Boolean = true

    fun draw(c: ProfileCanvas, ctx: ProfileContext)
}

/** The tab strip, in order. Add a tab here and nowhere else. */
object ProfileTabs {
    val ALL: List<ProfileTab> = listOf(OverviewTab, DungeonsTab, SkillsTab, SlayersTab, SoulStatsTab)
}
