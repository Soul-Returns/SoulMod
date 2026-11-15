package com.soulreturns.features.updater

import com.soulreturns.config.gui.menu.Releases
import com.soulreturns.features.updater.ReleaseNotesFormat.Block
import com.soulreturns.platform.updater.ReleaseNote
import com.soulreturns.ui.screen.RichText
import com.soulreturns.ui.screen.RichText.Span
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesFormatTest {
    @Test
    fun `inline bold and code, unclosed markers stay literal`() {
        assertEquals(
            listOf(Span("Run ", RichText.PLAIN), Span("/soul update", RichText.CODE), Span(" for ", RichText.PLAIN), Span("faster", RichText.BOLD), Span(" maps", RichText.PLAIN)),
            RichText.parse("Run `/soul update` for **faster** maps"),
        )
        assertEquals(listOf(Span("2 ** 3 and `x", RichText.PLAIN)), RichText.parse("2 ** 3 and `x"))
    }

    @Test
    fun `notes become version headers followed by their bodies`() {
        val blocks = ReleaseNotesFormat.blocks(
            listOf(
                ReleaseNote("0.3.0-beta.1", "beta", "2026-11-22T09:00:00Z", "## New\n- **Map** redraws\n\nPlain line"),
                ReleaseNote("0.2.0", "stable", null, null),
            ),
        )
        assertEquals(
            listOf(
                Block.Version("v0.3.0-beta.1", true, "2026-11-22"),
                Block.Heading("New"),
                Block.Bullet(listOf(Span("Map", RichText.BOLD), Span(" redraws", RichText.PLAIN))),
                Block.Paragraph(listOf(Span("Plain line", RichText.PLAIN))),
                Block.Version("v0.2.0", false, null),
            ),
            blocks,
        )
    }

    /** The format documented in skill `release-notes`: only what the update window draws. */
    @Test
    fun `release-notes files only use markup the update window draws`() {
        val files = File("release-notes").listFiles { f -> f.name.endsWith(".md") }.orEmpty()
        for (f in files) {
            val version = f.name.removeSuffix(".md")
            assertTrue(Releases.notes.containsKey(version), "${f.name} has no Releases.notes entry")
            var bullets = 0
            for ((i, line) in f.readLines().withIndex()) {
                val where = "${f.name}:${i + 1}"
                if (line.isBlank()) continue
                if (line.startsWith("## ")) {
                    assertTrue(line.removePrefix("## ") in setOf("New", "Improved", "Fixed", "Removed"), "$where: unknown section label")
                } else {
                    assertTrue(line.startsWith("- ") && line.length > 2, "$where: expected a '## ' section label or a one-line '- ' bullet")
                    assertTrue(line.length <= 200, "$where: bullet is too long for one short line")
                    bullets++
                }
            }
            assertTrue(bullets > 0, "${f.name} has no bullets")
        }
    }
}
