package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.util.SoulLogger

/** One drawn line. [entry] is the setup index it came from (-1 = Hypixel's own lines); blank text = a gap. */
class BuiltLine(val text: String, val align: String, val entry: Int = -1, val typeId: String? = null) {
    val gap: Boolean get() = LegacyText.strip(text).isBlank()

    override fun toString(): String = text
}

/**
 * Setup + sources -> the lines to draw. Pure (no Minecraft), shared by the HUD and the setup screen's
 * preview:
 *  - Header (pinned top) first and Footer (pinned bottom) last, whatever the stored order says;
 *  - hidden entries and entries whose type this version does not know are skipped (and kept in config);
 *  - each type's lines get the entry's picked colors ([LineColors.apply]) and the Look's chroma parts;
 *  - gaps fold: two in a row become one, gaps at the top or bottom are dropped.
 */
object ScoreboardBuilder {
    private val logger = SoulLogger("Soul/Scoreboard")
    private val failed = HashSet<String>()

    fun build(
        entries: List<ScoreboardEntry>,
        sources: ScoreboardSnapshot,
        look: ScoreboardLook,
        nowMs: Long,
        activity: String? = null,
        types: (String) -> LineType? = ScoreboardCatalog::type,
    ): List<BuiltLine> {
        val indexed = entries.withIndex().mapNotNull { (i, e) -> types(e.type)?.let { Triple(i, e, it) } }
        val ordered =
            indexed.filter { it.third.pinned == LineType.PIN_TOP } +
                indexed.filter { it.third.pinned == LineType.PIN_NONE } +
                indexed.filter { it.third.pinned == LineType.PIN_BOTTOM }
        val out = ArrayList<BuiltLine>()
        for ((index, entry, type) in ordered) {
            if (entry.hidden) continue
            val options = OptionValues(entry.options, type.options)
            if (type.isOff(options)) continue
            val ctx = LineContext(sources, entry, options, look, nowMs, activity)
            val lines =
                try {
                    type.lines(ctx).ifEmpty { if (look.hideEmpty) emptyList() else type.emptyLines(ctx) }
                } catch (t: Throwable) {
                    if (failed.add(type.id)) logger.error("Scoreboard line '${type.id}' threw; skipped", t)
                    emptyList()
                }
            if (lines.isEmpty()) continue
            val align = if (type.options.any { it.key == LineOptions.ALIGN }) options.string(LineOptions.ALIGN).ifEmpty { look.align } else look.align
            val code = colorCodes(type, entry, options, look)
            for ((i, line) in lines.withIndex()) out += BuiltLine(type.colors.apply(line, i, code), align, index, type.id)
        }
        return fold(out)
    }

    /** "Hypixel's lines only": the sidebar's title and lines as they are, in the Look. */
    fun raw(sources: ScoreboardSnapshot, look: ScoreboardLook): List<BuiltLine> {
        val out = ArrayList<BuiltLine>()
        sources.title?.let { out += BuiltLine(it.legacy, "center") }
        for (line in sources.sidebar) out += BuiltLine(line.legacy, look.align)
        return fold(out)
    }

    /** Fold double gaps into one and drop gaps at either end. */
    fun fold(lines: List<BuiltLine>): List<BuiltLine> {
        val out = ArrayList<BuiltLine>(lines.size)
        for (line in lines) {
            if (line.gap && (out.isEmpty() || out.last().gap)) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().gap) out.removeAt(out.lastIndex)
        return out
    }

    private fun colorCodes(type: LineType, entry: ScoreboardEntry, options: OptionValues, look: ScoreboardLook): (String) -> String? =
        { key ->
            val part = type.colors.parts.firstOrNull { it.key == key }
            if (part == null) {
                null
            } else if (part.chromaTarget != null && part.chromaTarget in look.chromaOn) {
                LegacyText.CHROMA
            } else if (!part.visible(options)) {
                null
            } else {
                entry.colors[key]?.let(LegacyText::code)
            }
        }
}
