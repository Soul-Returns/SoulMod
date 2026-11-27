package com.soulreturns.features.scoreboard

import com.soulreturns.api.LocationApi
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.data.skyblock.SidebarSnapshot
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSidebar
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.data.skyblock.TabSnapshot
import com.soulreturns.data.skyblock.TabWidgetBlock
import com.soulreturns.data.skyblock.TabWidgetParser
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * One line of a source. [legacy] is normalized legacy text ([LegacyText.normalize]: colors kept, junk
 * keys gone, one code per style change), [text] the same without codes.
 */
class SourceLine(val legacy: String, val text: String) {
    override fun toString(): String = legacy

    companion object {
        fun of(raw: String): SourceLine {
            val legacy = LegacyText.normalize(raw)
            return SourceLine(legacy, LegacyText.strip(legacy))
        }
    }
}

/** A tab list widget: its header name and lines ([lines] includes the header line), as Hypixel draws them. */
class TabWidget(val name: String, val lines: List<SourceLine>)

/**
 * Everything a line type can read, immutable. Built by [ScoreboardSources] on change; tests build it
 * directly. Values Soul's state readers already parse come from them ([tab], [area], …); the raw
 * sources are here for everything else.
 */
class ScoreboardSnapshot(
    val onSkyblock: Boolean = true,
    /** Sidebar title as Hypixel draws it (animated in events), or null without a sidebar. */
    val title: SourceLine? = null,
    /** Sidebar lines top first; empty without a sidebar. */
    val sidebar: List<SourceLine> = emptyList(),
    /** Tab list widgets in display order. */
    val tabWidgets: List<TabWidget> = emptyList(),
    val tabHeader: List<SourceLine> = emptyList(),
    val tabFooter: List<SourceLine> = emptyList(),
    /** The action bar while it shows, else null. */
    val actionBar: SourceLine? = null,
    /** Boss bar names, top first. */
    val bossBars: List<SourceLine> = emptyList(),
    /** Island (`Area:` in the tab list, location packet `map` in dungeons). */
    val area: String? = null,
    /** Sublocation from the sidebar's location line. */
    val subLocation: String? = null,
    /** Location API `mode` (`dungeon`, `mining_3`, `kuudra`, …). */
    val mode: String? = null,
    val serverId: String? = null,
    val profile: String? = null,
    val profileType: ProfileType = ProfileType.UNKNOWN,
    /** Parsed tab widgets ([TabWidgets]). */
    val tab: TabSnapshot = TabSnapshot.EMPTY,
) {
    /** The fixed block of the sidebar, parsed once per snapshot. */
    val fields: SidebarFields by lazy { SidebarParser.parse(sidebar) }

    /** The first widget named [name] (case-insensitive), or null. */
    fun widget(name: String): TabWidget? = tabWidgets.firstOrNull { it.name.equals(name, ignoreCase = true) }

    companion object {
        val EMPTY = ScoreboardSnapshot(onSkyblock = false)

        /** Test/preview helper: a snapshot from raw sidebar legacy lines. */
        fun ofSidebar(title: String, lines: List<String>, tabLines: List<String> = emptyList()): ScoreboardSnapshot =
            ScoreboardSnapshot(
                title = SourceLine.of(title),
                sidebar = lines.map(SourceLine::of),
                tabWidgets = TabWidgetParser.widgets(tabLines).map { w -> TabWidget(w.name, w.lines.map(SourceLine::of)) },
            )
    }
}

/**
 * The live [ScoreboardSnapshot]. Rebuilt on the client tick, and only the parts whose source changed
 * (by identity): the sidebar ([SkyblockSidebar], ¼ s polls of the one state reader), tab widgets
 * ([TabWidgets.lastLines], same walk), tab header/footer, action bar and boss bars (read from vanilla).
 * No second poller of the sidebar or tab list.
 */
object ScoreboardSources {
    @Volatile
    var snapshot: ScoreboardSnapshot = ScoreboardSnapshot.EMPTY
        private set

    /** Bumped whenever [snapshot] changes; caches key on it. */
    @Volatile
    var version: Int = 0
        private set

    private var sidebarRef: SidebarSnapshot? = null
    private var tabRef: List<String>? = null
    private var headerRef: Component? = null
    private var footerRef: Component? = null
    private var actionRef: Component? = null
    private var bossRef: List<Component> = emptyList()

    private var title: SourceLine? = null
    private var sidebar: List<SourceLine> = emptyList()
    private var widgets: List<TabWidget> = emptyList()
    private var header: List<SourceLine> = emptyList()
    private var footer: List<SourceLine> = emptyList()
    private var action: SourceLine? = null
    private var boss: List<SourceLine> = emptyList()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { mc -> update(mc) })
    }

    private fun update(mc: Minecraft) {
        var changed = false
        val sb = SkyblockSidebar.snapshot
        if (sb !== sidebarRef) {
            sidebarRef = sb
            title = sb.title.takeIf { sb.lines.isNotEmpty() || it.isNotEmpty() }?.let(SourceLine::of)
            sidebar = sb.lines.map(SourceLine::of)
            changed = true
        }
        val tab = TabWidgets.lastLines
        // A new list every poll; only a different one is work.
        if (tab !== tabRef && tab != tabRef) {
            tabRef = tab
            widgets = TabWidgetParser.widgets(tab).map(::widget)
            changed = true
        }
        val hud = mc.gui.hud
        val tabList = hud.tabList
        if (tabList.header !== headerRef) {
            headerRef = tabList.header
            header = lines(headerRef)
            changed = true
        }
        if (tabList.footer !== footerRef) {
            footerRef = tabList.footer
            footer = lines(footerRef)
            changed = true
        }
        val overlay = hud.overlayMessageString?.takeIf { hud.overlayMessageTime > 0 }
        if (overlay !== actionRef) {
            actionRef = overlay
            action = overlay?.let { SourceLine.of(it.toLegacyText()) }
            changed = true
        }
        val bossNames = hud.bossOverlay.events.values.map { it.name }
        if (bossNames.size != bossRef.size || bossNames.indices.any { bossNames[it] !== bossRef[it] }) {
            bossRef = bossNames
            boss = bossNames.map { SourceLine.of(it.toLegacyText()) }
            changed = true
        }
        val old = snapshot
        val area = SkyblockLocation.area
        val sub = SkyblockLocation.subLocation
        val on = SkyblockState.onSkyblock
        val profile = SkyblockProfileState.current
        val type = SkyblockState.profileType
        val tabSnap = TabWidgets.snapshot
        if (!changed && old.area == area && old.subLocation == sub && old.onSkyblock == on && old.mode == LocationApi.mode &&
            old.serverId == LocationApi.serverId && old.profile == profile && old.profileType == type && old.tab === tabSnap
        ) {
            return
        }
        snapshot =
            ScoreboardSnapshot(
                onSkyblock = on,
                title = title,
                sidebar = sidebar,
                tabWidgets = widgets,
                tabHeader = header,
                tabFooter = footer,
                actionBar = action,
                bossBars = boss,
                area = area,
                subLocation = sub,
                mode = LocationApi.mode,
                serverId = LocationApi.serverId,
                profile = profile,
                profileType = type,
                tab = tabSnap,
            )
        version++
    }

    private fun widget(block: TabWidgetBlock) = TabWidget(block.name, block.lines.map(SourceLine::of))

    /** A multi-line component (tab header/footer) as lines, each starting in the style the previous one ended in. */
    private fun lines(c: Component?): List<SourceLine> {
        val parts = c?.toLegacyText()?.split('\n') ?: return emptyList()
        var carry = ""
        return parts.map { part ->
            val raw = carry + part
            carry = LegacyText.activeCodes(raw)
            SourceLine.of(raw)
        }
    }
}
