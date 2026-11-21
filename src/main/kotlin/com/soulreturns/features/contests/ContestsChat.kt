package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.ContestSummary
import com.soulreturns.features.contests.model.FriendUpdate
import com.soulreturns.features.contests.model.PlayerRef
import com.soulreturns.features.contests.model.Roles
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Screen entry points, set by the UI at startup (`Soul.kt`), so commands and chat buttons do not depend
 * on the screens being compiled in.
 */
object ContestsNav {
    @Volatile var openHome: () -> Unit = {}
    @Volatile var openFriends: () -> Unit = {}
    @Volatile var openHistory: () -> Unit = {}
    @Volatile var openContest: (Long) -> Unit = {}
    @Volatile var openResults: (Long) -> Unit = {}
}

/** The clickable chat lines (mock "In game": invite, start, finish, standings updated, friend requests). */
object ContestsChat {
    private const val PREFIX = "§9§lSoul §8» "

    internal var sink: (Component) -> Unit = { line ->
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(line) }
    }

    fun invited(s: ContestSummary) {
        val by = s.invitedBy?.name ?: s.owner.name
        val goal = s.goal.label.ifEmpty { "" }
        val type = s.typeTitle.ifEmpty { s.type }
        line("$PREFIX§f$by §7invited you to §b${s.name} §8(§7$type${if (goal.isNotEmpty()) " · $goal" else ""}§8)")
        val row = Component.literal("§8» ")
            .append(button("Join", "/soul contests join ${s.id}", "Join and play with your current SkyBlock profile", ChatFormatting.GREEN, bold = true))
            .append(" ")
            .append(button("Watch", "/soul contests watch ${s.id}", "Join as a spectator", ChatFormatting.AQUA))
            .append(" ")
            .append(button("Decline", "/soul contests decline ${s.id}", "Decline the invitation", ChatFormatting.RED))
            .append(" ")
            .append(button("Details", "/soul contests open ${s.id}", "Open the contest", ChatFormatting.GRAY))
        sink(row)
    }

    fun started(snap: ContestSnapshot) {
        val goal = snap.contest.goal.label
        val frozen = snap.contest.pricesFrozenAt?.let { " · prices frozen at ${clock(it)}" } ?: ""
        line(
            Component.literal("$PREFIX§b${snap.contest.name} §fstarted! §7$goal$frozen ")
                .append(button("Open", "/soul contests open ${snap.contest.id}", "Open the contest", ChatFormatting.AQUA)),
        )
    }

    fun ended(snap: ContestSnapshot) {
        val name = snap.contest.name
        if (snap.contest.state == ContestStates.CANCELED) {
            val reason = snap.contest.canceledReason?.takeIf { it.isNotBlank() }?.let { " §8($it)" } ?: ""
            line(Component.literal("$PREFIX§b$name §7was canceled.$reason"))
            return
        }
        val winners = snap.standings.filter { it.rank == 1 }
        val me = ContestsStore.selfUuid()
        val mine = snap.standings.firstOrNull { it.uuid == me }?.rank
        val who = when {
            winners.isEmpty() -> "§7No one placed"
            winners.size == 1 -> "§6${winners[0].name} §7wins with §6${coins(winners[0].total)}§7."
            else -> "§6${winners.joinToString(" & ") { it.name }} §7share 1st with §6${coins(winners[0].total)}§7."
        }
        val you = if (mine != null) " You placed §e${ordinal(mine)}§7." else ""
        line(
            Component.literal("$PREFIX§b$name §fis over! $who$you ")
                .append(button("Results", "/soul contests results ${snap.contest.id}", "Open the results", ChatFormatting.AQUA)),
        )
    }

    fun standingsUpdated(snap: ContestSnapshot, oldRank: Int?, newRank: Int?, runner: PlayerRef?) {
        val me = ContestsStore.selfUuid()
        val whose = when {
            runner == null -> "a late run counted"
            runner.uuid == me -> "your late run counted"
            else -> "${runner.name}'s late run counted"
        }
        val place = when {
            newRank == null -> ""
            oldRank != null && oldRank != newRank -> " You moved from §e${ordinal(oldRank)}§7 to §e${ordinal(newRank)}§7."
            else -> " You're §e${ordinal(newRank)}§7."
        }
        line(
            Component.literal("$PREFIX§eStandings updated §8(§b${snap.contest.name}§8)§7: $whose.$place ")
                .append(button("Results", "/soul contests results ${snap.contest.id}", "Open the results", ChatFormatting.AQUA)),
        )
    }

    fun friendRequest(u: FriendUpdate) {
        line("$PREFIX§f${u.name} §7sent you a Soul friend request.")
        sink(
            Component.literal("§8» ")
                .append(button("Accept", "/soul friends accept ${u.name}", "Become Soul friends", ChatFormatting.GREEN, bold = true))
                .append(" ")
                .append(button("Decline", "/soul friends decline ${u.name}", "They are not told", ChatFormatting.RED))
                .append(" ")
                .append(button("Friends", "/soul friends", "Open Friends", ChatFormatting.GRAY)),
        )
    }

    fun friendAccepted(u: FriendUpdate) {
        line("$PREFIX§f${u.name} §7accepted your Soul friend request.")
    }

    /** A plain status line from a command (`/soul friends add`, `/soul contests join`). */
    fun info(text: String) = line("$PREFIX§7$text")

    fun role(role: String): String = if (role == Roles.SPECTATOR) "spectator" else role

    private fun line(text: String) = sink(Component.literal(text))
    private fun line(c: Component) = sink(c)

    private fun button(label: String, command: String, hover: String, color: ChatFormatting, bold: Boolean = false): MutableComponent =
        Component.literal("[$label]").withStyle(
            Style.EMPTY.withColor(color).withBold(bold)
                .withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hover))),
        )

    fun coins(v: Long): String = NumberFormat.getIntegerInstance(Locale.ROOT).format(v)

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    private fun clock(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
