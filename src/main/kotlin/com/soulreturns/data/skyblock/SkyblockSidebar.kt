package com.soulreturns.data.skyblock

/**
 * The sidebar as Hypixel draws it, fed by [SkyblockStateReader]'s one sidebar read (every ¼ s).
 * [title] and [lines] are legacy `§` strings with every color kept: each line is team prefix + score
 * holder + suffix (the holder is an invisible color-code key, so lines carry junk codes such as `§q`
 * mid-word; strip or normalize before matching). [lines] are in display order, top line first.
 *
 * Empty while there is no sidebar; cleared on server change and disconnect.
 */
data class SidebarSnapshot(val title: String, val lines: List<String>) {
    companion object {
        val EMPTY = SidebarSnapshot("", emptyList())
    }
}

object SkyblockSidebar {
    /** Written on the client thread; immutable. Compare by identity to notice a change. */
    @Volatile
    var snapshot: SidebarSnapshot = SidebarSnapshot.EMPTY
        private set

    internal fun update(title: String, lines: List<String>) {
        val old = snapshot
        if (old.title == title && old.lines == lines) return
        snapshot = SidebarSnapshot(title, lines)
    }

    internal fun clear() {
        if (snapshot !== SidebarSnapshot.EMPTY) snapshot = SidebarSnapshot.EMPTY
    }
}
