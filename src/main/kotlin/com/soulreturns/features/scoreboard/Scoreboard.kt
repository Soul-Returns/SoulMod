package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.cfg
import com.soulreturns.features.scoreboard.lines.HeaderLine
import com.soulreturns.features.scoreboard.lines.NewLinesLine
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The Scoreboard feature (rule://scoreboard): keeps [ScoreboardSources] fresh, hides vanilla's sidebar
 * while Soul draws it, builds the lines [ScoreboardHud] draws and posts the "new line" chat note.
 */
object Scoreboard {
    /** Lines are rebuilt at most this often (and whenever sources or config change). */
    private const val REBUILD_MS = 250L

    /** After a sidebar vanishes (server switch), "Keep lines while switching islands" holds the last lines this long. */
    private const val KEEP_MS = 10_000L

    /** At most one "new line" chat note per minute; each line shape once per session. */
    private const val NOTE_GAP_MS = 60_000L

    private var cacheKey: Any? = null
    private var cacheConfig: ScoreboardConfig? = null
    private var cacheAt = 0L
    private var cached: List<BuiltLine> = emptyList()
    private var lastShown: List<BuiltLine> = emptyList()
    private var lastShownAt = 0L

    private val noted = HashSet<String>()
    private var lastNoteAt = 0L
    private var notedVersion = -1

    fun register() {
        ScoreboardSources.register()
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD) { vanilla ->
            HudElement { g, tick -> if (!hidesVanilla()) vanilla.extractRenderState(g, tick) }
        }
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { noteNewLines() })
    }

    /** Whether Soul's scoreboard replaces vanilla's sidebar right now. */
    fun hidesVanilla(): Boolean {
        val c = cfg.scoreboard
        return c.enabled && !c.keepVanilla && (ScoreboardSources.snapshot.onSkyblock || c.look.outsideSkyblock)
    }

    /** The lines to draw now (cached; empty = draw nothing). Client thread. */
    fun lines(nowMs: Long = System.currentTimeMillis()): List<BuiltLine> {
        val c = cfg.scoreboard
        val version = ScoreboardSources.version
        if (cacheKey == version && cacheConfig === c && nowMs - cacheAt < REBUILD_MS) return cached
        cacheKey = version
        cacheConfig = c
        cacheAt = nowMs
        cached = build(c, ScoreboardSources.snapshot, nowMs)
        if (cached.isNotEmpty()) {
            lastShown = cached
            lastShownAt = nowMs
        } else if (c.look.keepWhileSwitching && ScoreboardSources.snapshot.sidebar.isEmpty() && nowMs - lastShownAt < KEEP_MS) {
            cached = lastShown
        }
        return cached
    }

    /** Pure: what [lines] shows for [s]. */
    fun build(c: ScoreboardConfig, s: ScoreboardSnapshot, nowMs: Long): List<BuiltLine> {
        if (s.sidebar.isEmpty()) return emptyList()
        if (!s.onSkyblock) {
            if (!c.look.outsideSkyblock) return emptyList()
            return outside(c, s, nowMs)
        }
        if (c.look.hypixelLinesOnly) return ScoreboardBuilder.raw(s, c.look)
        val activity = ScoreboardActivities.active(c, s)
        val setup = activity?.let { c.activities[it.id] } ?: c.main
        return ScoreboardBuilder.build(setup.entries, s, c.look, nowMs, activity?.id)
    }

    /** Outside SkyBlock: the server's own sidebar in the Look, under Soul's header when "Also outside SkyBlock" is on. */
    private fun outside(c: ScoreboardConfig, s: ScoreboardSnapshot, nowMs: Long): List<BuiltLine> {
        val raw = ScoreboardBuilder.raw(s, c.look)
        val header = c.main.entries.firstOrNull { it.type == HeaderLine.id && !it.hidden } ?: return raw
        if ((header.options[HeaderLine.OUTSIDE] as? JsonPrimitive)?.booleanOrNull != true) return raw
        val built = ScoreboardBuilder.build(listOf(header), s, c.look, nowMs)
        return ScoreboardBuilder.fold(built + raw.drop(if (s.title != null) 1 else 0))
    }

    private fun noteNewLines() {
        val version = ScoreboardSources.version
        if (version == notedVersion) return
        notedVersion = version
        val c = cfg.scoreboard
        val s = ScoreboardSources.snapshot
        if (!c.enabled || !s.onSkyblock || c.look.hypixelLinesOnly) return
        val setup = ScoreboardActivities.setup(c, s)
        val entry = setup.entries.firstOrNull { it.type == NewLinesLine.id && !it.hidden } ?: return
        if ((entry.options[NewLinesLine.NOTE] as? JsonPrimitive)?.booleanOrNull == false) return
        val now = System.currentTimeMillis()
        if (now - lastNoteAt < NOTE_GAP_MS) return
        for (line in NewLinesLine.unknown(s)) {
            val shape = line.text.trim().replace(Regex("\\d+"), "#")
            if (!noted.add(shape)) continue
            lastNoteAt = now
            Minecraft.getInstance().player?.sendSystemMessage(
                Component.literal("§8[§6Soul§8] §7The scoreboard has a line Soul does not know yet, shown at the bottom: §r${line.legacy}"),
            )
            return
        }
    }
}
