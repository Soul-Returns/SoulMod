package com.soulreturns.features.storage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageApiPrefillTest {
    private val account = UUID.fromString("00000000-0000-4000-8000-000000000001")
    private val profile = UUID.fromString("00000000-0000-4000-8000-000000000002")
    private val other = UUID.fromString("00000000-0000-4000-8000-000000000003")

    private fun blob(data: String) = JsonObject(mapOf("data" to JsonPrimitive(data)))
    private fun member(vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>) =
        JsonObject(mapOf("inventory" to JsonObject(fields.toMap())))
    private fun slots(size: Int): List<String?> = List(size) { if (it % 7 == 0) null else "{slot:$it}" }
    private fun api(items: List<String?>?) = items?.let(::ApiItems)

    @Test
    fun scopesNormalizeApiUuidsButRequireExactActiveAccountAndProfile() {
        val scope = assertNotNull(StorageApiScope.from(account.toString().replace("-", ""), profile.toString().uppercase()))
        assertEquals(StorageApiScope(account, profile), scope)
        assertTrue(scope.matches(account, profile.toString()))
        assertTrue(scope.matches(account, profile.toString().uppercase()))
        assertFalse(scope.matches(other, profile.toString()))
        assertFalse(scope.matches(account, other.toString()))
        assertFalse(scope.matches(account, "Strawberry"))
        assertFalse(scope.matches(account, null))
        assertNull(StorageApiScope.from(null, profile.toString()))
        assertNull(StorageApiScope.from(account.toString(), "Strawberry"))
    }

    @Test
    fun enderPagesSplitAt45WithoutDroppingEmptySlotsOrPartialLastRows() {
        val original = slots(99)
        val pages = storageApiPages(member("ender_chest_contents" to blob("ender")), { api(original) })
        assertEquals(listOf(1, 2, 3), pages.map { it.id.index })
        assertTrue(pages.all { it.id.kind == "ender" && it.identity == null })
        assertEquals(listOf(5, 5, 1), pages.map { it.rows })
        assertEquals(original, pages.flatMap { it.items!! })
        assertEquals("{slot:45}", pages[1].items!![0])
        assertEquals("Ender Chest 3", pages[2].label)
    }

    @Test
    fun allNineEnderPagesAreSupportedButMalformedSizesAreNotKnownEmpty() {
        val raw = member("ender_chest_contents" to blob("ender"))
        val pages = storageApiPages(raw, { api(slots(405)) })
        assertEquals(9, pages.size)
        assertEquals(StoragePageId("ender", 9), pages.last().id)
        for (size in listOf(0, 1, 8, 44, 46, 406, 414)) {
            assertTrue(storageApiPages(raw, { api(slots(size)) }).isEmpty(), "size=$size")
        }
        assertTrue(storageApiPages(raw, { null }).isEmpty())
    }

    @Test
    fun backpackKeysAreZeroBasedAndSparseSlotsAreNeverCompactedOrRebased() {
        val raw = member("backpack_contents" to JsonObject(mapOf(
            "17" to blob("last"),
            "1" to blob("second"),
            "0" to blob("first"),
            "01" to blob("alias"),
            "18" to blob("outside"),
            "-1" to blob("negative"),
            "name" to blob("invalid"),
        )))
        val decoded = mutableListOf<String>()
        val pages = storageApiPages(raw, { decoded.add(it); api(slots(9)) })
        assertEquals(listOf("first", "second", "last"), decoded)
        assertEquals(listOf(1, 2, 18), pages.map { it.id.index })
        assertTrue(pages.all { it.id.kind == "backpack" })
        val withoutZero = member("backpack_contents" to JsonObject(mapOf("4" to blob("fifth"))))
        assertEquals(StoragePageId("backpack", 5), storageApiPages(withoutZero, { api(slots(9)) }).single().id)
    }

    @Test
    fun backpackRowsComeFromFullBlobSizeAndEmptySlotsAreStillKnownContents() {
        for (rows in 1..5) {
            val raw = member("backpack_contents" to JsonObject(mapOf("0" to blob("bag"))))
            val page = storageApiPages(raw, { api(List(rows * 9) { null }) }).single()
            assertEquals(rows, page.rows)
            assertEquals(List(rows * 9) { null }, page.items)
        }
        for (size in listOf(0, 8, 10, 44, 46, 54)) {
            val raw = member("backpack_contents" to JsonObject(mapOf("0" to blob("bag"))))
            assertTrue(storageApiPages(raw, { api(slots(size)) }).isEmpty(), "size=$size")
        }
    }

    @Test
    fun iconsJoinByExactApiKeyAndNeverMoveContentsToAnIconOnlySlot() {
        val raw = member(
            "backpack_contents" to JsonObject(mapOf("0" to blob("first"), "5" to blob("sixth"))),
            "backpack_icons" to JsonObject(mapOf("0" to blob("first-icon"), "1" to blob("icon-only"), "5" to blob("sixth-icon"))),
        )
        val seen = mutableListOf<String>()
        val pages = storageApiPages(raw, { api(slots(9)) }, { seen.add(it); if (it == "first-icon") "bag-uuid" else null })
        assertEquals(listOf("first-icon", "sixth-icon"), seen)
        assertEquals(listOf(1, 6), pages.map { it.id.index })
        assertEquals("bag-uuid", pages[0].identity)
        assertNull(pages[1].identity)
    }

    @Test
    fun missingAccessMalformedFieldsAndDecodeFailuresLeaveUnknownNotEmpty() {
        val malformed = listOf(
            "{}",
            """{"inventory":null}""",
            """{"inventory":{"ender_chest_contents":{"data":42},"backpack_contents":[]}}""",
            """{"inventory":{"ender_chest_contents":{"data":" "},"backpack_contents":{"0":{"data":null},"1":"not-an-envelope"}}}""",
            """{"ender_chest_contents":{"data":"wrong-level"},"backpack_contents":{"0":{"data":"wrong-level"}}}""",
        )
        for (json in malformed) {
            val raw = Json.parseToJsonElement(json) as JsonObject
            assertTrue(storageApiPages(raw, { error("Malformed/absent data must not reach the decoder") }).isEmpty())
        }
        val raw = member(
            "ender_chest_contents" to blob("bad"),
            "backpack_contents" to JsonObject(mapOf("0" to blob("bad"), "2" to blob("valid"))),
        )
        val pages = storageApiPages(raw, { if (it == "bad") null else api(slots(9)) })
        assertEquals(listOf(StoragePageId("backpack", 3)), pages.map { it.id })
    }

    // Live report (beta.4/5): a fresh install opened on a page showed backpack previews but no Ender Chest
    // page at all. All nine pages share one API blob, and one slot that did not convert dropped every page.
    @Test
    fun oneUnconvertibleEnderSlotCostsOnlyItsPagesPreviewNotThePage() {
        val raw = member(
            "ender_chest_contents" to blob("ender"),
            "backpack_contents" to JsonObject(mapOf("0" to blob("bad-bag"), "1" to blob("bag"))),
        )
        val pages = storageApiPages(raw, { when (it) {
            "ender" -> ApiItems(slots(405), failed = setOf(3 * 45 + 10))
            "bad-bag" -> ApiItems(slots(9), failed = setOf(4))
            else -> api(slots(9))
        } })
        val ender = pages.filter { it.id.kind == "ender" }
        assertEquals((1..9).toList(), ender.map { it.id.index })
        assertNull(ender[3].items)
        assertTrue(ender.filter { it.id.index != 4 }.all { it.items?.size == 45 })
        assertEquals(listOf(StoragePageId("backpack", 2)), pages.filter { it.id.kind == "backpack" }.map { it.id })
        val cards = storageApiPreviews(StorageBucket(), pages, emptySet())
        assertEquals((1..9).map { StoragePageId("ender", it) } + StoragePageId("backpack", 2), cards.keys.toList())
        assertNull(cards.getValue(StoragePageId("ender", 4)).items)
    }
}
