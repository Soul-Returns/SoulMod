package com.soulreturns.features.search

import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Expected values are the mock's element rects (`mocks/search-everywhere/`, 1280×720 Soul px, panel at 190, 55). */
class SearchEverywhereLayoutTest {
    private val frame = SearchEverywhereLayout.frame(1280f, 720f)

    @Test
    fun panelHeaderScopeBodyAndFootMatchTheMock() {
        assertFalse(frame.compact)
        assertEquals(Rect(190, 55, 1090, 665), frame.outer)
        assertEquals(Rect(191, 56, 1089, 133), frame.header) // .head 1..78
        assertEquals(Rect(191, 133, 1089, 168), frame.scope) // .scope 78..113
        assertEquals(Rect(191, 168, 1089, 636), frame.body) // .body 113..581
        assertEquals(Rect(191, 636, 1089, 664), frame.foot) // .foot 581..609
        // .col-search 455.6 wide incl. its border-right, .col-detail from 456.6.
        assertEquals(646, frame.search.right)
        assertEquals(647, frame.detail.x)
    }

    @Test
    fun searchColumnFieldChipsListAndFootMatchTheMock() {
        assertEquals(Rect(211, 184, 632, 219), SearchEverywhereLayout.field(frame.search)) // .field (21,129) 420.6×35
        val chips = SearchEverywhereLayout.chips(frame.search, listOf(43, 106, 82))
        assertEquals(listOf(211, 260, 372), chips.map { it.x }) // .chip x 21, 70.5, 182.5
        assertEquals(229, chips[0].y) // .sources y 174
        val list = SearchEverywhereLayout.list(frame.search, chips.last().bottom)
        assertEquals(Rect(211, 288, 632, 597), list) // .list (21,233) h 309
        assertEquals(Rect(211, 604, 632, 636), SearchEverywhereLayout.listFoot(frame.search)) // .list-foot y 549 h 32
        assertEquals(Rect(211, 345, 628, 399), SearchEverywhereLayout.row(list, 1, 0)) // rows 54 high, 3 apart, 416.6 wide
    }

    @Test
    fun chipsWrapOntoASecondLineWhenTheyRunOutOfRoom() {
        val chips = SearchEverywhereLayout.chips(frame.search, listOf(200, 200, 100))
        assertEquals(listOf(229, 229, 259), chips.map { it.y })
        assertEquals(211, chips[2].x)
    }

    @Test
    fun detailPlacesAndSectionsMatchTheMock() {
        val inner = SearchEverywhereLayout.inner(frame.detail)
        assertEquals(667, inner.x) // .d-head x 476.6
        val places = SearchEverywhereLayout.places(frame.detail, 1)
        assertEquals(316, places.y) // .places y 261
        assertEquals(593, places.bottom) // .note (one line) at 548, 10 below the places
        // Island expanded with 3 cards, storage with 2: headers at 0 and 246, storage cards from 290.
        val boxes = SearchEverywhereLayout.sections(listOf(0, 0), listOf(3, 2))
        assertEquals(listOf(0, 246), boxes.map { it.top })
        assertEquals(listOf(44, 290), boxes.map { it.cardsTop })
        assertEquals(234, boxes[0].bottom)
        // Collapsed (the default): headers only, 12 apart.
        assertEquals(listOf(0, 48), SearchEverywhereLayout.sections(listOf(0, 0), listOf(0, 0)).map { it.top })
        // An off-island note hugs its header (margin -3 0 2).
        val noted = SearchEverywhereLayout.sections(listOf(1, 0), listOf(0, 0))
        assertEquals(41, noted[0].noteTop)
        assertEquals(58, noted[0].bottom)
        assertEquals(70, noted[1].top)
    }

    @Test
    fun narrowWindowsStackTheColumns() {
        val compact = SearchEverywhereLayout.frame(700f, 720f)
        assertTrue(compact.compact) // panel 652 < 660
        assertEquals(compact.body.y + SearchEverywhereLayout.COMPACT_SEARCH, compact.detail.y)
        assertEquals(SearchEverywhereLayout.COMPACT_DETAIL, compact.detail.height)
    }
}
