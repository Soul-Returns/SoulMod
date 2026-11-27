package com.soulreturns.features.scoreboard

import com.soulreturns.features.scoreboard.lines.AreaLine
import com.soulreturns.features.scoreboard.lines.BankLine
import com.soulreturns.features.scoreboard.lines.BitsLine
import com.soulreturns.features.scoreboard.lines.ColdLine
import com.soulreturns.features.scoreboard.lines.CopperLine
import com.soulreturns.features.scoreboard.lines.CoreValues
import com.soulreturns.features.scoreboard.lines.CustomTextLine
import com.soulreturns.features.scoreboard.lines.EmptyLine
import com.soulreturns.features.scoreboard.lines.FooterLine
import com.soulreturns.features.scoreboard.lines.GemsLine
import com.soulreturns.features.scoreboard.lines.HeaderLine
import com.soulreturns.features.scoreboard.lines.HeatLine
import com.soulreturns.features.scoreboard.lines.IslandLine
import com.soulreturns.features.scoreboard.lines.KernelsLine
import com.soulreturns.features.scoreboard.lines.MoneyChunkLine
import com.soulreturns.features.scoreboard.lines.MoneyProgressValues
import com.soulreturns.features.scoreboard.lines.MotesLine
import com.soulreturns.features.scoreboard.lines.NewLinesLine
import com.soulreturns.features.scoreboard.lines.NorthStarsLine
import com.soulreturns.features.scoreboard.lines.ObjectiveLine
import com.soulreturns.features.scoreboard.lines.PlayersLine
import com.soulreturns.features.scoreboard.lines.PowderLine
import com.soulreturns.features.scoreboard.lines.PowerLine
import com.soulreturns.features.scoreboard.lines.ProfileLine
import com.soulreturns.features.scoreboard.lines.PurseLine
import com.soulreturns.features.scoreboard.lines.QuiverLine
import com.soulreturns.features.scoreboard.lines.RealTimeLine
import com.soulreturns.features.scoreboard.lines.SbLevelLine
import com.soulreturns.features.scoreboard.lines.ServerLine
import com.soulreturns.features.scoreboard.lines.SlayerLine
import com.soulreturns.features.scoreboard.lines.SlayerStatsLine
import com.soulreturns.features.scoreboard.lines.SoulflowLine
import com.soulreturns.features.scoreboard.lines.SowdustLine
import com.soulreturns.features.scoreboard.lines.SkyblockDateLine
import com.soulreturns.features.scoreboard.lines.soul.BalLine
import com.soulreturns.features.scoreboard.lines.soul.ComboLine
import com.soulreturns.features.scoreboard.lines.soul.DragonEyesLine
import com.soulreturns.features.scoreboard.lines.soul.DrillFuelLine
import com.soulreturns.features.scoreboard.lines.soul.DungeonBlessingsLine
import com.soulreturns.features.scoreboard.lines.soul.DungeonRoomLine
import com.soulreturns.features.scoreboard.lines.soul.FishingSessionLine
import com.soulreturns.features.scoreboard.lines.soul.FriendsContestLine
import com.soulreturns.features.scoreboard.lines.soul.IslandWeatherLine
import com.soulreturns.features.scoreboard.lines.soul.MimicPrinceLine
import com.soulreturns.features.scoreboard.lines.soul.MineshaftCorpsesLine
import com.soulreturns.features.scoreboard.lines.soul.NucleusRunLine
import com.soulreturns.features.scoreboard.lines.soul.PetXpLine
import com.soulreturns.features.scoreboard.lines.soul.SackGainsLine
import com.soulreturns.features.scoreboard.lines.soul.SkillGainLine
import com.soulreturns.features.scoreboard.lines.soul.SoulValues
import com.soulreturns.features.scoreboard.lines.TabWidgetCatalog
import com.soulreturns.features.scoreboard.lines.TuningsLine
import com.soulreturns.features.scoreboard.lines.VisitingLine
import com.soulreturns.features.scoreboard.lines.TimeLine
import com.soulreturns.features.scoreboard.lines.CookieLine
import com.soulreturns.features.scoreboard.lines.GodPotionLine
import com.soulreturns.features.scoreboard.lines.MayorLine
import com.soulreturns.features.scoreboard.lines.PartyLine
import com.soulreturns.features.scoreboard.lines.PetLine
import com.soulreturns.features.scoreboard.lines.events.EventDefs
import com.soulreturns.features.scoreboard.lines.events.EventLine
import com.soulreturns.features.scoreboard.lines.events.EventsLine

/**
 * Every line type and live value, in picker order. Adding one = its file under `lines/` plus one row
 * here; nothing else in the core changes.
 */
object ScoreboardCatalog {
    const val BASICS = "Basics"
    const val TIME_PLACE = "Time & place"
    const val MONEY = "Money"
    const val PROGRESS = "Progress"
    const val BUFFS = "Buffs & world"
    const val EVENTS = "Events"
    const val TAB_LIST = "From the tab list"
    const val SOUL = "Soul"

    /** Picker groups, in order. */
    val groups = listOf(BASICS, TIME_PLACE, MONEY, PROGRESS, BUFFS, EVENTS, TAB_LIST, SOUL)

    val lines: List<LineType> by lazy {
        listOf(
            HeaderLine,
            FooterLine,
            CustomTextLine,
            EmptyLine,
            NewLinesLine,
            ServerLine,
            SkyblockDateLine,
            TimeLine,
            RealTimeLine,
            IslandLine,
            AreaLine,
            PlayersLine,
            VisitingLine,
            ProfileLine,
            PurseLine,
            BankLine,
            BitsLine,
            GemsLine,
            SoulflowLine,
            MotesLine,
            CopperLine,
            SowdustLine,
            KernelsLine,
            HeatLine,
            ColdLine,
            NorthStarsLine,
            MoneyChunkLine,
            ObjectiveLine,
            SlayerLine,
            SlayerStatsLine,
            PowderLine,
            SbLevelLine,
            QuiverLine,
            PowerLine,
            TuningsLine,
            // Buffs & world
            CookieLine,
            GodPotionLine,
            PetLine,
            MayorLine,
            PartyLine,
            // Events: the "whatever is running" line, then each of the 28 events as its own line (lines/events/)
            EventsLine,
            *EventDefs.all.map(::EventLine).toTypedArray(),
            // From the tab list: one TabWidgetLine per widget, as data in TabWidgetCatalog
            *TabWidgetCatalog.all.toTypedArray(),
            // Soul: lines from Soul's own feature state (lines/soul/)
            IslandWeatherLine,
            PetXpLine,
            ComboLine,
            FishingSessionLine,
            SackGainsLine,
            NucleusRunLine,
            BalLine,
            MineshaftCorpsesLine,
            DragonEyesLine,
            DungeonRoomLine,
            MimicPrinceLine,
            DungeonBlessingsLine,
            DrillFuelLine,
            SkillGainLine,
            FriendsContestLine,
        )
    }

    val values: List<ScoreboardValue> by lazy { CoreValues.all + MoneyProgressValues.all + SoulValues.all }

    private val byId: Map<String, LineType> by lazy { lines.associateBy { it.id } }

    fun type(id: String): LineType? = byId[id]

    /** Sidebar lines some type stands for; the rest are new to Soul ([NewLinesLine]). */
    fun claimed(sources: ScoreboardSnapshot): Set<Int> {
        val out = HashSet<Int>()
        for (t in lines) out += t.claims(sources)
        return out
    }
}
