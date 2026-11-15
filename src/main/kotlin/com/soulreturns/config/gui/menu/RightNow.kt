package com.soulreturns.config.gui.menu

import com.soulreturns.config.gui.model.FeatureCard
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.fishing.FishingTimer
import com.soulreturns.features.mining.metaldetector.MetalDetector
import com.soulreturns.features.mining.nucleus.NucleusRuns
import com.soulreturns.features.sacks.SackMenus
import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Activity detectors of Start › Right now (BACKLOG "Config menu UX rework"). Each reads state the
 * mod already keeps; boss phase, slayer quest, Diana and Kuudra have no confirmed signal yet and
 * are left out (BACKLOG follow-ups).
 */
class Detector(val id: String, val label: String, val icon: String) {
    companion object {
        const val DUNGEON = "dungeon"
        const val FISHING = "fishing"
        const val FESTIVAL = "festival"
        const val NUCLEUS = "nucleus"
        const val METAL_DETECTOR = "metaldetector"
        const val SACK_MENU = "sackmenu"

        /** Group order on Start: detectors in this order, then the area. */
        val all: List<Detector> =
            listOf(
                Detector(DUNGEON, "Dungeon run", "skull"),
                Detector(FISHING, "Fishing", "hook"),
                Detector(FESTIVAL, "Fishing Festival", "fish"),
                Detector(NUCLEUS, "Nucleus run", "gem"),
                Detector(METAL_DETECTOR, "Metal Detector held", "target"),
                Detector(SACK_MENU, "Sack menu open", "sack"),
            )

        fun of(id: String): Detector? = all.firstOrNull { it.id == id }
    }
}

/** What the player is doing right now: area, sublocation, active detectors, dungeon floor. */
data class NowContext(
    val area: String?,
    val subLocation: String? = null,
    val active: Set<String> = emptySet(),
    val floor: String? = null,
)

/** One Start › Right now group: a detector's or the area's features. */
class NowGroup(val key: String, val label: String, val isArea: Boolean, val icon: String, val cards: List<FeatureCard>)

object RightNow {
    /** Location API area names shown under the name a player knows. */
    private val AREA_LABELS = mapOf("Dungeon" to "Catacombs", "Mineshaft" to "Glacite Mineshafts")

    fun areaLabel(area: String): String = AREA_LABELS[area] ?: area

    fun label(detector: String, ctx: NowContext): String {
        val base = Detector.of(detector)?.label ?: detector
        return if (detector == Detector.DUNGEON && !ctx.floor.isNullOrEmpty()) "$base · ${ctx.floor}" else base
    }

    /** A card matters in this area (by area or sublocation name). */
    fun inArea(card: FeatureCard, ctx: NowContext): Boolean =
        card.where.any { it == ctx.area || it == ctx.subLocation }

    /**
     * Right now, ported from the mock's `rightNow()`: one group per active detector (detector
     * order), then the area; a card shows in its first group only, and empty groups are dropped.
     */
    fun groups(cards: List<FeatureCard>, ctx: NowContext): List<NowGroup> {
        val seen = HashSet<String>()
        val out = ArrayList<NowGroup>()
        fun add(key: String, label: String, isArea: Boolean, icon: String, list: List<FeatureCard>) {
            val fresh = list.filter { seen.add(it.id) }
            if (fresh.isNotEmpty()) out += NowGroup(key, label, isArea, icon, fresh)
        }
        for (d in Detector.all) if (d.id in ctx.active) add(d.id, label(d.id, ctx), false, d.icon, cards.filter { d.id in it.contexts })
        val area = ctx.area
        if (area != null) add("area", areaLabel(area), true, "pin", cards.filter { inArea(it, ctx) })
        return out
    }

    fun cards(cards: List<FeatureCard>, ctx: NowContext): List<FeatureCard> = groups(cards, ctx).flatMap { it.cards }

    /** The tab an activity opens on right now (its first right-now card's), else null. */
    fun tabFor(activity: String, cards: List<FeatureCard>, ctx: NowContext): String? =
        cards(cards, ctx).firstOrNull { it.activity == activity }?.tab

    /** Why an activity (or one of its tabs) is marked: the labels of the groups that hold its cards. */
    fun why(activity: String, tab: String?, groups: List<NowGroup>): String =
        groups.filter { g -> g.cards.any { it.activity == activity && (tab == null || it.tab == tab) } }.joinToString(" · ") { it.label }

    /** Reads the live state (client thread). [openedOver] is the screen the menu was opened from. */
    fun live(openedOver: Screen?): NowContext {
        if (!SkyblockState.onSkyblock) return NowContext(null)
        val active = LinkedHashSet<String>()
        if (DungeonRunState.inRun) active += Detector.DUNGEON
        if (!FishingTimer.isPaused) active += Detector.FISHING
        if (FishingFestivalState.active) active += Detector.FESTIVAL
        if (NucleusRuns.inHollows() && NucleusRuns.runElapsedMs() != null) active += Detector.NUCLEUS
        val held = SkyblockItemUtils.getSkyblockId(Minecraft.getInstance().player?.mainHandItem)
        if (held == MetalDetector.DETECTOR_ID && SkyblockLocation.isInSubLocation(MetalDetector.SUBLOCATION)) active += Detector.METAL_DETECTOR
        if ((openedOver as? AbstractContainerScreen<*>)?.let { SackMenus.isSackMenu(it) } == true) active += Detector.SACK_MENU
        val floor = DungeonRunState.floor.shortName.takeIf { DungeonRunState.inRun && it.isNotEmpty() }
        return NowContext(SkyblockLocation.area, SkyblockLocation.subLocation, active, floor)
    }
}
