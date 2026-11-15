package com.soulreturns.features.islandchests

import com.soulreturns.features.inventorybuttons.InventoryButtonIcons.ItemLook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IslandChestSyncTest {
    private val profile = "00000000000040008000000000000002"
    private val account = "00000000000040008000000000000001"
    private fun item(count: Int = 64, lore: List<String> = emptyList()) = StoredChestItem("minecraft:vine", "VINE", "§fVines", lore, count)
    private fun chest(x: Int = 1, revision: Long = 0, count: Int = 64, at: Long = 1_700_000_000_000) = StoredIslandChest(
        "$x,64,2", listOf(ChestPos(x, 64, 2)), "minecraft:chest", List(27) { if (it == 0 && count > 0) item(count) else null }, account, "Observer", at, revision,
    )
    private fun local(c: StoredIslandChest = chest(), base: Long = 0, id: String = "00000000-0000-4000-8000-000000000003") = LocalChestObservation(id, base, c)

    @Test
    fun unrelatedGlobalChangesDoNotRebaseOrConflictWithPendingChest() {
        val pending = local(base = 2)
        val initial = IslandChestBucket(2, listOf(chest(revision = 2))).observe(pending)
        val reconciled = initial.reconcile(30, listOf(chest(revision = 2), chest(20, 30)))
        assertEquals(2L, reconciled.local.single().baseRevision)
        assertFalse(reconciled.local.single().blocked)
        assertEquals(2L, reconciled.local.single().wire()!!.baseRevision)
        assertEquals("pending", reconciled.visible(true).first { it.key == pending.chest.key }.syncState)
    }

    @Test
    fun newerSharedEvidenceWinsEvenWhenLocalClockIsAheadAndConflictNeverRebases() {
        val pending = local(chest(count = 64, at = 2_000_000_000_000), base = 3)
        val reconciled = IslandChestBucket(3).observe(pending).reconcile(4, listOf(chest(revision = 4, count = 7, at = 1_000)))
        assertTrue(reconciled.local.single().blocked)
        assertEquals(pending.chest, reconciled.local.single().chest)
        assertEquals(3L, reconciled.local.single().baseRevision)
        assertEquals(7, reconciled.visible(true).single().slots[0]!!.count)
        assertEquals(1_000L, reconciled.visible(true).single().observedAt)
        val afterAnotherGet = reconciled.reconcile(7, listOf(chest(revision = 7, count = 9)))
        assertEquals(3L, afterAnotherGet.local.single().baseRevision)
        val physicallyReopened = afterAnotherGet.observe(local(chest(count = 9), base = 7, id = "fresh"))
        assertFalse(physicallyReopened.local.single().blocked)
        assertEquals("pending", physicallyReopened.visible(true).single().syncState)
    }

    @Test
    fun staleGetOrDelayedIdempotentAckCannotReplaceNewerContentsOrTopology() {
        val sent = local()
        val otherHalf = ChestPos(2, 64, 2)
        val newerDouble = chest(revision = 8, count = 0).copy(blocks = listOf(ChestPos(1, 64, 2), otherHalf), slots = List(54) { null })
        val current = IslandChestBucket(8, listOf(newerDouble))
        assertSame(current, current.reconcile(3, listOf(chest(revision = 3))))
        val acknowledged = current.acknowledge(sent, 4)
        assertEquals(8L, acknowledged.revision)
        assertEquals(listOf(newerDouble), acknowledged.shared)
        val split = IslandChestBucket(0).observe(local(newerDouble.copy(revision = 0))).reconcile(9, listOf(chest(revision = 9)))
        assertEquals(1, split.visible(true).size)
        assertEquals(1, split.visible(true).single().blocks.size)
        assertEquals(9L, split.visible(true).single().revision)
    }

    @Test
    fun acknowledgementKeepsLaterPhysicalEditsWithoutConflictingWithOwnUpload() {
        val sent = local(chest(at = 1234))
        val successor = local(chest(count = 16, at = 2345), id = "successor")
        val bucket = IslandChestBucket().observe(sent).observe(successor).acknowledge(sent, 1)
        assertEquals(successor.copy(baseRevision = 1), bucket.local.single())
        assertEquals(1234L, bucket.shared.single().observedAt)
        assertEquals(1L, bucket.shared.single().revision)
        assertEquals(16, bucket.visible(true).single().slots[0]!!.count)
        assertEquals(2345L, bucket.visible(true).single().observedAt)
        assertFalse(bucket.reconcile(1, bucket.shared).local.single().blocked)
        val otherMember = chest(revision = 8, count = 7)
        val superseded = IslandChestBucket(8, listOf(otherMember)).observe(successor).acknowledge(sent, 1)
        assertEquals(0L, superseded.local.single().baseRevision)
        assertEquals(7, superseded.visible(true).single().slots[0]!!.count)
    }

    @Test
    fun rejectedSnapshotStaysLocalAndACompleteEmptyObservationReplacesContents() {
        val pending = local()
        val conflict = IslandChestBucket().observe(pending).reject(pending.observationId)
        assertTrue(conflict.local.single().blocked)
        assertEquals("conflict", conflict.visible(true).single().syncState)
        val empty = local(chest(count = 0), id = "reopen")
        val next = conflict.observe(empty)
        assertTrue(next.visible(false).single().slots.all { it == null })
        assertFalse(next.local.single().blocked)
    }

    @Test
    fun reconnectRecognizesAcceptedObservationWhenPostResponseWasLost() {
        val pending = local()
        val recovered = IslandChestBucket().observe(pending).reconcile(1, listOf(pending.chest.copy(revision = 1)))
        assertTrue(recovered.local.isEmpty())
        assertEquals(pending.chest.observedAt, recovered.shared.single().observedAt)
        val differentObserver = pending.chest.copy(observerUuid = profile, revision = 1)
        val notOurs = IslandChestBucket().observe(pending).reconcile(1, listOf(differentObserver))
        assertTrue(notOurs.local.single().blocked)
    }

    @Test
    fun transportHasOnlyAgreedSafeFieldsAndRejectsRatherThanTruncates() {
        val withIcon = chest().copy(slots = List(27) { if (it == 0) item().copy(look = ItemLook("minecraft:vine", "private-profile", "minecraft:paper", true)) else null })
        val body = assertNotNull(local(withIcon).wire())
        val json = chestWireJson.encodeToJsonElement(ChestWireObservation.serializer(), body) as JsonObject
        assertEquals(setOf("schemaVersion", "baseRevision", "observationId", "observedAt", "blockId", "blocks", "slots"), json.keys)
        assertEquals("1", json["schemaVersion"]!!.jsonPrimitive.content)
        val encoded = json.toString()
        assertFalse("private-profile" in encoded)
        assertFalse("observerUuid" in encoded)
        assertFalse("syncState" in encoded)
        assertNull(local(chest().copy(slots = List(27) { item().copy(displayNameRaw = "é".repeat(513)) })).wire())
        assertNull(local(chest().copy(slots = List(27) { item(lore = List(64) { "x".repeat(2048) }) })).wire())
        assertNull(local(chest().copy(blocks = listOf(ChestPos(2, 64, 2), ChestPos(1, 64, 2)), slots = List(54) { null })).wire())
        assertFalse(ChestWireSnapshot(2, profile, 0, false, null).valid(profile))
        assertFalse(ChestWireSnapshot(2, account, 0, true).valid(profile))
        assertFalse(ChestWireSnapshot(1, profile, 0, true).valid(profile))
        assertTrue(ChestWireSnapshot(2, profile, 0, true).valid(profile))
    }

    private fun wireChest(x: Int, revision: Long = 7): ChestWireChest {
        val c = chest(x, revision)
        return ChestWireChest(c.key, c.blocks, c.blockId, local(c).wire()!!.slots, account, "Observer", c.observedAt, c.observedAt + 1, revision)
    }

    @Test
    fun allPagesAreSearchableOnlyAfterCompleteAtomicSnapshotCommit() {
        val local = local(chest(1000), base = 1)
        val initial = IslandChestBucket(1, listOf(chest(-1, 1)), listOf(local), snapshotRevision = 1)
        val download = ChestSnapshotDownload(profile, 1)
        val rows = (1..70).map { wireChest(it) }
        assertTrue(download.accept(ChestWireSnapshot(2, profile, 7, false, rows.take(32), "7:${rows[31].key}")))
        assertFalse(download.complete)
        assertSame(initial, download.reconcile(initial), "A page must never replace the cached full profile.")
        assertTrue(download.accept(ChestWireSnapshot(2, profile, 7, false, rows.subList(32, 64), "7:${rows[63].key}")))
        assertSame(initial, download.reconcile(initial))
        assertTrue(download.accept(ChestWireSnapshot(2, profile, 7, false, rows.drop(64))))
        assertTrue(download.complete)
        val committed = download.reconcile(initial)
        assertEquals(70, committed.shared.size)
        assertEquals(listOf(local), committed.local)
        assertEquals(7L, committed.snapshotRevision)
        val search = IslandChestSearchIndex().search(committed.shared, "vine").single()
        assertEquals(70 * 64L, search.count)
        assertEquals(70, search.matches.size)
    }

    @Test
    fun revisionChangeOrOverlappingPageNeverCommitsPartialOrMixedSnapshot() {
        val initial = IslandChestBucket(1, listOf(chest(-1, 1)))
        val first = wireChest(1)
        val download = ChestSnapshotDownload(profile, 1)
        assertTrue(download.accept(ChestWireSnapshot(2, profile, 7, false, listOf(first), "7:${first.key}")))
        assertFalse(download.accept(ChestWireSnapshot(2, profile, 8, false, listOf(wireChest(2, 8)))))
        assertSame(initial, download.reconcile(initial))
        assertFalse(download.accept(ChestWireSnapshot(2, profile, 7, false, listOf(wireChest(2)))))
        assertSame(initial, download.reconcile(initial))
        val overlapping = ChestSnapshotDownload(profile, null)
        assertTrue(overlapping.accept(ChestWireSnapshot(2, profile, 7, false, listOf(first), "7:${first.key}")))
        assertFalse(overlapping.accept(ChestWireSnapshot(2, profile, 7, false, listOf(first))))
        assertSame(initial, overlapping.reconcile(initial))
    }

    @Test
    fun paginationRequiresVersionBoundsAndMatchingUnchangedRevision() {
        val initial = IslandChestBucket(7, listOf(chest(1, 7)), snapshotRevision = 7)
        val unchanged = ChestSnapshotDownload(profile, 7)
        assertTrue(unchanged.accept(ChestWireSnapshot(2, profile, 7, true)))
        assertTrue(unchanged.complete)
        assertSame(initial, unchanged.reconcile(initial))
        assertFalse(ChestSnapshotDownload(profile, null).accept(ChestWireSnapshot(2, profile, 7, true)))
        assertFalse(ChestSnapshotDownload(profile, 6).accept(ChestWireSnapshot(2, profile, 7, true)))
        assertFalse(ChestSnapshotDownload(profile, 7).accept(ChestWireSnapshot(1, profile, 7, true)))
        assertFalse(ChestSnapshotDownload(profile, 7).accept(ChestWireSnapshot(2, profile, 7, true, listOf(wireChest(1)))))
        assertFalse(ChestSnapshotDownload(profile, 7).accept(ChestWireSnapshot(2, profile, 7, false, (1..33).map { wireChest(it) })))
        assertFalse(ChestSnapshotDownload(profile, 7).accept(ChestWireSnapshot(2, profile, 7, false, emptyList(), "7:1,64,2")))
        assertFalse(ChestSnapshotDownload(profile, 7).accept(ChestWireSnapshot(2, profile, 7, false, listOf(wireChest(1)), "8:1,64,2")))
    }

    @Test
    fun quotaRejectionPreservesContentsAndDoesNotQueueRepeatedUploads() {
        val pending = local()
        val rejected = IslandChestBucket().observe(pending).reject(pending.observationId, "quota")
        val restored = chestWireJson.decodeFromString(IslandChestBucket.serializer(), chestWireJson.encodeToString(IslandChestBucket.serializer(), rejected))
        assertEquals(pending.chest, restored.local.single().chest)
        assertTrue(restored.local.single().blocked)
        assertEquals("quota", restored.visible(true).single().syncState)
        assertEquals(pending.baseRevision, restored.local.single().baseRevision)
        val smaller = local(chest(count = 0), id = "reopened")
        assertFalse(restored.observe(smaller).local.single().blocked)
    }

    @Test
    fun uploadAckIsNotACompleteSnapshotRevision() {
        val sent = local(chest(2), base = 3)
        val previous = IslandChestBucket(3, listOf(chest(1, 3)), listOf(sent), snapshotRevision = 3)
        val acknowledged = previous.acknowledge(sent, 8)
        assertEquals(8L, acknowledged.revision)
        assertEquals(3L, acknowledged.snapshotRevision, "Other co-op writes between GET and POST still require a complete refresh.")
    }

    @Test
    fun searchCountsLogicalChestsWithLongTotalsKeepsVariantsAndCachesQueries() {
        val first = chest().copy(slots = List(27) { item(Int.MAX_VALUE) })
        val second = chest(4).copy(slots = List(27) { if (it == 0) item(Int.MAX_VALUE) else null })
        val variant = chest(8).copy(slots = List(27) { if (it == 0) item(lore = listOf("Different enchantment")) else null })
        val data = listOf(first, second, variant)
        val index = IslandChestSearchIndex()
        val all = index.search(data, "")
        assertEquals(2, all.size)
        val plain = all.first { it.item.loreLines.isEmpty() }
        assertEquals(28L * Int.MAX_VALUE, plain.count)
        assertEquals(2, plain.matches.size)
        assertEquals(27L * Int.MAX_VALUE, plain.matches.first().count)
        val query = index.search(data, "VINE")
        assertEquals(2, query.size)
        assertSame(query, index.search(data, "vine"))
        assertSame(all, index.search(data, ""))
        assertSame(query, index.search(data, "vine"))
        val updated = listOf(first.copy(slots = List(27) { null }))
        assertTrue(index.search(updated, "vine").isEmpty())
    }

    @Test
    fun syncRunsAnywhereOnSkyblockWithResolvedIdentityButObservationStaysOnTheIsland() {
        val identity = ChestObservationContext(account, profile, 3)
        val offIsland = IslandChestGates.readable(identity, fresh = true, enabled = true, onSkyblock = true, stopping = false)
        assertEquals(IslandChestSyncContext(account, profile), IslandChestGates.sync(offIsland, sharing = true, previous = null))
        assertNull(IslandChestGates.observable(offIsland, ownIsland = false), "Observing and arming stay island-only.")
        assertSame(identity, IslandChestGates.observable(offIsland, ownIsland = true))

        fun syncWith(identity: ChestObservationContext? = ChestObservationContext(account, profile, 3), fresh: Boolean = true, enabled: Boolean = true,
                     onSkyblock: Boolean = true, stopping: Boolean = false, sharing: Boolean = true) =
            IslandChestGates.sync(IslandChestGates.readable(identity, fresh, enabled, onSkyblock, stopping), sharing, null)
        assertNull(syncWith(identity = null), "Unresolved identity")
        assertNull(syncWith(fresh = false), "Stale profile/account/level")
        assertNull(syncWith(sharing = false), "Sharing off")
        assertNull(syncWith(enabled = false), "Feature off")
        assertNull(syncWith(onSkyblock = false), "Not on SkyBlock")
        assertNull(syncWith(stopping = true), "Client stopping")
    }

    @Test
    fun islandHopWithSameIdentityKeepsScheduleWhileAnotherProfileStartsOver() {
        val first = IslandChestGates.sync(ChestObservationContext(account, profile, 1), true, null)!!
        // Every server hop bumps the observation generation; the sync identity ignores it.
        assertSame(first, IslandChestGates.sync(ChestObservationContext(account, profile, 2), true, first))
        val schedule = IslandChestSyncSchedule()
        assertTrue(schedule.resolve(first))
        assertTrue(schedule.due(1_000))
        schedule.started()
        schedule.finished(1_000, 30_000, rateLimited = false)
        assertFalse(schedule.due(2_000))
        // Unresolved gap, then the same account + profile on the next server: no fresh download per hop.
        val afterHop = IslandChestGates.sync(ChestObservationContext(account, profile, 5), true, null)!!
        assertFalse(schedule.resolve(afterHop))
        assertFalse(schedule.due(2_000))
        assertTrue(schedule.due(31_000), "The periodic refresh still runs.")
        // An invalidation (or a run interrupted by the hop) refreshes once, inside the read limiter's window.
        schedule.started()
        schedule.finished(31_000, 60_000, rateLimited = true)
        schedule.request()
        assertFalse(schedule.due(40_000))
        assertTrue(schedule.due(91_000))
        val other = IslandChestGates.sync(ChestObservationContext(account, "00000000000040008000000000000009", 6), true, null)!!
        assertTrue(schedule.resolve(other))
        assertTrue(schedule.due(40_000), "Another profile does not inherit this profile's schedule.")
    }

    @Test
    fun interruptedMultiPageDownloadResumesOnlyForTheSameBaseline() {
        val rows = (1..40).map { wireChest(it) }
        val download = ChestSnapshotDownload(profile, 1)
        assertFalse(download.resumable(1), "Nothing staged yet has no cursor to resume from.")
        assertTrue(download.accept(ChestWireSnapshot(2, profile, 7, false, rows.take(32), "7:${rows[31].key}")))
        assertTrue(download.resumable(1))
        assertFalse(download.resumable(2), "A new snapshot baseline starts staging over.")
        assertTrue(download.accept(ChestWireSnapshot(2, profile, 7, false, rows.drop(32))))
        assertFalse(download.resumable(1), "Complete downloads are committed, not resumed.")
        val failed = ChestSnapshotDownload(profile, 1)
        assertTrue(failed.accept(ChestWireSnapshot(2, profile, 7, false, rows.take(32), "7:${rows[31].key}")))
        assertFalse(failed.accept(ChestWireSnapshot(2, profile, 8, false, rows.drop(32))))
        assertFalse(failed.resumable(1))
    }
}
