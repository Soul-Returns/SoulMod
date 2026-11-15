package com.soulreturns.features.updater

import com.soulreturns.platform.updater.ReleaseNote
import com.soulreturns.ui.screen.RichText

/**
 * The release notes' tiny markdown subset (`release-notes/<version>.md`, uploaded by `make publish`):
 * `#`/`##` headings, `- ` / `* ` bullets and paragraphs, with [RichText] inline (`**bold**`,
 * `` `code` ``). Anything else stays literal text. Pure, so the parsing is testable.
 */
object ReleaseNotesFormat {
    sealed interface Block {
        /** One release: "v0.2.0" + BETA chip + date. */
        data class Version(val version: String, val beta: Boolean, val date: String?) : Block

        data class Heading(val text: String) : Block

        data class Bullet(val spans: List<RichText.Span>) : Block

        data class Paragraph(val spans: List<RichText.Span>) : Block
    }

    /** Every note, newest first as given, each as a [Block.Version] followed by its body. */
    fun blocks(notes: List<ReleaseNote>): List<Block> {
        val out = ArrayList<Block>()
        for (n in notes) {
            out += Block.Version("v${n.version}", n.channel == "beta" || '-' in n.version, n.publishedAt?.take(10))
            out += body(n.changelog.orEmpty())
        }
        return out
    }

    fun body(markdown: String): List<Block> {
        val out = ArrayList<Block>()
        for (raw in markdown.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            out += when {
                line.startsWith("#") -> Block.Heading(line.trimStart('#').trim().replace("**", ""))
                line.startsWith("- ") || line.startsWith("* ") -> Block.Bullet(RichText.parse(line.substring(2)))
                else -> Block.Paragraph(RichText.parse(line))
            }
        }
        return out
    }
}
