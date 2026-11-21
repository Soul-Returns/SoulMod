package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.ActionResult
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import net.minecraft.world.item.ItemStack
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every page in every fixture (each role and connection state, `ContestsFixtures`) built and painted on a
 * recording canvas at the wide (969) and narrow (629) main widths, like the Profile Viewer's
 * `SectionBuildTest`. Asserts on the text each page paints.
 */
class ContestsPagesBuildTest {
    private class Recorder(override val state: ViewState) : KitCanvas {
        val texts = ArrayList<String>()
        override val viewTop = 0
        override val viewBottom = Int.MAX_VALUE
        override val nowMs = System.currentTimeMillis()
        override fun width(text: String, size: Float, bold: Boolean) = text.length * if (bold) 6 else 5
        override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean) = text.length * 6
        override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {}
        override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
            texts += text
        }
        override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
            texts += text
        }
        override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {}
        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {}
        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {}
        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int) = false
        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) {}
        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {}
        override fun itemTooltip(stack: ItemStack) {}
        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) {
            texts += placeholder
        }
        override fun openSection(id: String) {}

        fun all(): String = texts.joinToString(" ").replace(Regex("\\s+"), " ")
    }

    private class Host(override val src: ContestsSource, override val nav: ContestsNavState, override val mainW: Int) : ContestsHost {
        override val state = ViewState()
        override val nowMs: Long = System.currentTimeMillis()
        override fun go(page: String, contestId: Long?, player: String?, fromHistory: Boolean) {}
        override fun pop(id: String) {}
        override fun anchor(id: String, x0: Int, y0: Int, x1: Int, y1: Int) {}
        override fun popOpen(id: String) = false
        override fun confirm(title: String, message: String, label: String, action: () -> Unit) {}
        override fun prompt(title: String, message: String, label: String, initial: String, action: (String) -> Unit) {}
        override fun act(call: (Done) -> Unit, then: (ActionResult) -> Unit) {}
        override fun openProfile(name: String) {}
        override fun openChat(text: String) {}
    }

    private fun paint(name: String, mainW: Int): String {
        val (src, nav) = ContestsFixtures.build(name) ?: fail("no fixture $name")
        val host = Host(src, nav, mainW)
        val page = ContestsPages.build(host)
        val r = Recorder(host.state)
        r.texts += page.crumbs.map { it.label }
        for (n in page.right) n.paint(r, 0, 0, 100, n.height(r, 100))
        val nodes = page.fixed + page.body + listOfNotNull(page.bar)
        val root = StackNode(nodes, ContestsLayout.GAP)
        val h = root.height(r, mainW)
        assertTrue(h > 0, "$name at $mainW measured $h")
        root.paint(r, 0, 0, mainW, h)
        return r.all()
    }

    private val expect = mapOf(
        "home" to listOf("RUNNING", "Invitations", "Upcoming", "PAST CONTESTS", "Sunday Sprint"),
        "home-empty" to listOf("No contest running."),
        "friends" to listOf("ONLINE · ", "OFFLINE · ", "Requests", "Blocked", "Friends can see where I am"),
        "friends-empty" to listOf("No Soul friends yet"),
        "friends-down" to listOf("Can't reach Soul's server."),
        "friends-added" to listOf("Request sent to Shale_Sam"),
        "create" to listOf("What are you competing in?", "Goal and time", "Scoring", "Who's in", "Frozen at the start", "Create and invite", "EXAMPLE · LATER"),
        "lobby-owner" to listOf("People", "Rules", "Start now", "Cancel contest", "Declined"),
        "lobby-manager" to listOf("Only the owner can make managers."),
        "lobby-participant" to listOf("Waiting for"),
        "lobby-spectator" to listOf("You're a spectator"),
        "running-owner" to listOf("Leaderboard", "Live feed", "Projected finish", "PROJECTION", "asks to join"),
        "running-manager" to listOf("Leaderboard"),
        "running-participant" to listOf("Leaderboard", "updates live"),
        "running-spectator" to listOf("You're watching.", "Ask to play"),
        "running-reconnecting" to listOf("Live updates paused.", "as of"),
        "running-offline" to listOf("You're offline.", "will be sent when Soul reconnects"),
        "running-back" to listOf("Back online."),
        "player" to listOf("Remove from contest", "without a price", "Median time", "Projected"),
        "player-me-offline" to listOf("Not sent yet", "not sent yet"),
        "finished" to listOf("It's over", "Per player", "Final standings", "Play again with the same people", "DISQUALIFIED"),
        "finished-tie" to listOf("Tie on the total, decided by the best run."),
        "finished-shared" to listOf("Shared place."),
        "finished-updated" to listOf("Standings updated at"),
        "canceled" to listOf("This contest was canceled.", "Hypixel went down for maintenance."),
        "past" to listOf("From your history"),
        "history" to listOf("Warm-up Ten", "Saturday Nucleus"),
        "checks" to listOf("Your game reports the run", "The server prices it", "Being honest about the limits."),
    )

    @Test
    fun everyFixtureBuildsAtBothWidths() {
        val missing = ArrayList<String>()
        for (name in ContestsFixtures.NAMES) {
            for (w in listOf(969, 629)) {
                val text = try {
                    paint(name, w)
                } catch (e: Throwable) {
                    throw AssertionError("$name at $w threw: $e", e)
                }
                for (s in expect[name].orEmpty()) if (s !in text) missing += "$name@$w: \"$s\""
            }
        }
        assertTrue(missing.isEmpty(), "missing texts:\n" + missing.joinToString("\n"))
    }

    @Test
    fun everyFixtureHasExpectations() {
        assertTrue(ContestsFixtures.NAMES.all { it in expect }, (ContestsFixtures.NAMES - expect.keys).toString())
    }

    /** The screens render the contest type from its descriptor: no Nucleus wording outside the fixtures. */
    @Test
    fun noTypeWordingInScreens() {
        val dir = File("src/main/kotlin/com/soulreturns/features/contests/gui")
        val hits = dir.listFiles()!!.filter { it.name != "ContestsFixtures.kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> l.contains("ucleus") }.map { "${f.name}:${it.index + 1}" }
        }
        assertTrue(hits.isEmpty(), hits.joinToString())
    }
}
