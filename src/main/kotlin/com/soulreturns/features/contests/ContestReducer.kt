package com.soulreturns.features.contests

import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestUpdate
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.StandingRow
import com.soulreturns.features.contests.model.UpdateKinds

/**
 * Applies one `contest-update` to the held snapshot (contract 6). Pure, so every rule is unit-tested:
 * - `rev <= held` is a duplicate or stale event: nothing changes.
 * - `rev != held + 1` is a gap (an event was lost, Mercure has no replay): refetch.
 * - `member|state|settings` and unknown kinds: refetch (the event carries no state to apply).
 * - `run|void`: the runner's row, everyone's new ranks and the new feed entries are applied.
 * - `check`: the row is replaced; an open run list of that player is refetched by the store.
 */
object ContestReducer {
    class Outcome(
        /** The new snapshot, or null when nothing could be applied. */
        val snapshot: ContestSnapshot?,
        val refetch: Boolean,
        /** True when the event is older than or equal to what is held. */
        val stale: Boolean = false,
    )

    fun apply(held: ContestSnapshot, u: ContestUpdate): Outcome {
        if (u.rev <= held.rev) return Outcome(null, refetch = false, stale = true)
        if (u.rev != held.rev + 1) return Outcome(null, refetch = true)
        val kind = u.kind
        if (kind != UpdateKinds.RUN && kind != UpdateKinds.VOID && kind != UpdateKinds.CHECK) {
            return Outcome(null, refetch = true)
        }
        var standings = held.standings
        u.standing?.let { standings = replaceRow(standings, it) }
        if (kind != UpdateKinds.CHECK && u.ranks.isNotEmpty()) {
            val byUuid = u.ranks.associateBy { it.uuid }
            standings = standings.map { row ->
                val r = byUuid[row.uuid] ?: return@map row
                row.copy(rank = r.rank, shared = r.shared)
            }
        }
        val contest = u.state?.let { held.contest.copy(state = it) } ?: held.contest
        return Outcome(
            held.copy(
                rev = u.rev,
                contest = contest,
                standings = sortStandings(standings),
                feed = mergeFeed(held.feed, u.feed),
            ),
            refetch = false,
        )
    }

    private fun replaceRow(rows: List<StandingRow>, row: StandingRow): List<StandingRow> {
        val i = rows.indexOfFirst { it.uuid == row.uuid }
        if (i < 0) return rows + row
        return rows.toMutableList().also { it[i] = row }
    }

    /** Ranked rows by rank (stable), then unranked (left/disqualified) rows in their old order. */
    fun sortStandings(rows: List<StandingRow>): List<StandingRow> {
        val ranked = rows.filter { it.rank != null }.sortedBy { it.rank }
        return ranked + rows.filter { it.rank == null }
    }

    /** Newest first, one entry per `seq`. */
    fun mergeFeed(held: List<FeedEntry>, incoming: List<FeedEntry>): List<FeedEntry> {
        if (incoming.isEmpty()) return held
        val seen = HashSet<Long>()
        return (incoming + held).sortedByDescending { it.seq }.filter { seen.add(it.seq) }
    }
}
