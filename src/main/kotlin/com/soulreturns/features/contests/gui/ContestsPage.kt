package com.soulreturns.features.contests.gui

import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.ViewState

/** Where the screen is: the rail page plus the contest / player it shows. */
class ContestsNavState {
    var page: String = HOME
    var contestId: Long? = null
    var player: String? = null

    /** The player page came from History (a past contest) instead of the live one. */
    var fromHistory: Boolean = false

    var feedFilter: String = "all"
    var lbSort: String = "total"

    /** Result of the last friend request: kind (sent, accepted, not_a_user, …) and the name typed. */
    var addResult: String? = null
    var addName: String = ""
    var busy: Boolean = false

    /** Create form. */
    var createType: String? = null
    var createUnits: Int? = null
    var createStartAt: Boolean = false
    var createDeadline: Boolean = false
    var createLateJoin: Boolean = false
    val createInvites = LinkedHashMap<String, String>()

    fun resetCreate() {
        createType = null
        createUnits = null
        createStartAt = false
        createDeadline = false
        createLateJoin = false
        createInvites.clear()
    }

    companion object {
        const val HOME = "home"
        const val FRIENDS = "friends"
        const val HISTORY = "history"
        const val CHECKS = "checks"
        const val CREATE = "create"
        const val CONTEST = "contest"
        const val PLAYER = "player"
        const val PAST = "past"

        /** Which rail entry a page highlights (`railOf`). */
        fun railOf(page: String, fromHistory: Boolean): String = when (page) {
            CONTEST, CREATE -> HOME
            PLAYER -> if (fromHistory) HISTORY else HOME
            PAST -> HISTORY
            else -> page
        }
    }
}

/** One crumb of the section head; the last one has no action. */
class Crumb(val label: String, val onClick: (() -> Unit)?)

/**
 * A built page: crumbs and right-hand nodes for the section head, banners pinned above the scroll
 * ([fixed]), the scrolled [body], an optional action bar pinned at the bottom ([bar], `.abar`), and
 * the key its scroll offset is kept under.
 */
class Page(
    val crumbs: List<Crumb>,
    val right: List<Node> = emptyList(),
    val fixed: List<Node> = emptyList(),
    val body: List<Node>,
    val bar: Node? = null,
    val key: String,
)

/** What page code may ask of the screen (navigation, popovers, modals, running actions). */
interface ContestsHost {
    val src: ContestsSource
    val nav: ContestsNavState
    val state: ViewState
    val nowMs: Long
    val mainW: Int

    fun go(page: String, contestId: Long? = nav.contestId, player: String? = null, fromHistory: Boolean = false)

    /** Opens (or closes) popover [id]; [anchorKey] names the rect recorded with [anchor]. */
    fun pop(id: String)
    fun anchor(id: String, x0: Int, y0: Int, x1: Int, y1: Int)
    fun popOpen(id: String): Boolean

    fun confirm(title: String, message: String, label: String, action: () -> Unit)
    fun prompt(title: String, message: String, label: String, initial: String, action: (String) -> Unit)

    /** Runs a store action; an error shows as a modal with its message, [then] runs on success. */
    fun act(call: (Done) -> Unit, then: (com.soulreturns.features.contests.ActionResult) -> Unit = {})

    fun openProfile(name: String)
    fun openChat(text: String)
}
