package com.soulreturns.features.chat

import com.soulreturns.config.cfg
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import com.soulreturns.util.toLegacyText
import net.minecraft.client.GuiMessage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.ActiveTextCollector
import net.minecraft.client.gui.TextAlignment
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix3x2f
import org.joml.Vector2f

/**
 * Right-click in chat → copy to clipboard. Invoked by `ChatScreenRightClickCopyMixin`
 * at the HEAD of `ChatScreen.mouseClicked` whenever the right mouse button (button == 1)
 * fires. Three modes selected via modifier keys at click time:
 *
 *  - **Plain right-click** — full source message under the cursor, plain text (no §-codes).
 *    For multi-line server banners (Hypixel welcome / fire-sale messages that embed `\n`)
 *    this includes every visible row of the same source `GuiMessage`.
 *  - **Shift + right-click** — only the single visible line under the cursor, plain text.
 *  - **Ctrl + right-click** — full source message with §-color codes preserved.
 *
 * **Resolution path** (1.21.11):
 *  1. Walk visible chat lines via [ChatComponent.captureClickableText], feeding a custom
 *     [ActiveTextCollector] that records the `FormattedCharSequence` whose bounding box
 *     contains the click point.
 *  2. Match that FCS against `ChatComponent.trimmedMessages` by **identity** — the per-line
 *     entries hold the same FCS instances the collector receives, so `===` works.
 *  3. The matched `GuiMessage.Line` carries an `addedTime` shared by every line of the same
 *     source message. Match it against `ChatComponent.allMessages` (also by `addedTime`)
 *     to find the original `GuiMessage` whose `content()` is the full pre-wrap `Component`.
 *
 * Mojang's old `getMessageEndIndexAt` / `getMessageLineIndexAt` helpers were removed in
 * 1.21.11 — this path reimplements the same lookup via the new collector API.
 */
object ChatRightClickCopy {
    private val logger = SoulLogger("Soul/ChatCopy")

    /** Font line height — used for line-bbox hit tests in the collector. */
    private const val LINE_HEIGHT_PX = 9

    fun isEnabled(): Boolean = cfg.general.chat.enableRightClickCopy()

    /**
     * Handle a right-click at `(x, y)` (the same coords Mojang's chat-click logic
     * receives — chat-scale-corrected by the existing `ChatScreenMixin` WrapOperation).
     * Returns `true` iff a message was located and copied; the caller consumes the click
     * in that case.
     */
    fun handleRightClick(
        x: Double,
        y: Double,
        shift: Boolean,
        ctrl: Boolean,
    ): Boolean {
        if (!isEnabled()) return false
        val mc = Minecraft.getInstance()
        val chat = mc.gui.chat

        // The two int parameters to captureClickableText are NOT testX/testY (despite the
        // first instinct) — they're guiScaledHeight (used to position lines from the chat
        // anchor) and guiTicks (for time-based fade math). Decompiling Mojang's own call
        // in ChatScreen.mouseClicked: `chat.captureClickableText(collector, screenHeight,
        // gui.getGuiTicks(), true)`. Passing the click coords here makes the chat walker
        // emit lines relative to a phantom screenHeight = testY, which lands the line
        // bboxes way off in the negative-y range. Use the real screen height instead;
        // the test coords live on the LineFinder.
        val screenHeight = mc.window.guiScaledHeight
        val guiTicks = mc.gui.guiTicks
        val finder = LineFinder(mc, x.toFloat(), y.toFloat())
        chat.captureClickableText(finder, screenHeight, guiTicks, true)
        val fcs = finder.hit ?: return false

        val toCopy: String =
            when {
                shift -> fcs.toPlainText()
                ctrl -> resolveFullMessageText(chat, fcs, withCodes = true) ?: fcs.toPlainText()
                else -> resolveFullMessageText(chat, fcs, withCodes = false) ?: fcs.toPlainText()
            }

        if (toCopy.isEmpty()) return false
        mc.keyboardHandler.setClipboard(toCopy)
        val label =
            when {
                shift -> "line"
                ctrl -> "message with color codes"
                else -> "message"
            }
        soulChat("§7Copied $label to clipboard.")
        logger.info("Copied to clipboard ($label, ${toCopy.length} chars)")
        return true
    }

    /**
     * Match the clicked `FormattedCharSequence` back to the **complete server batch** it
     * belongs to and return the concatenated text. Hypixel sends multi-line banners (the
     * `▬▬▬ DRAGON DOWN! …` block, sack reports, fire-sale notices) as one packet of
     * **separate** `GuiMessage`s — each line is its own message, NOT one multi-line
     * Component. The shared signal is `GuiMessage.addedTime()`: every message added in
     * the same client tick gets the same value. So "the full message under the cursor"
     * means "every `GuiMessage` whose `addedTime` equals the clicked line's `addedTime`".
     *
     * Returns null when the FCS isn't found in `trimmedMessages` (shouldn't happen since
     * the collector receives FCS instances FROM that list, but defensively-handled) or
     * when no `allMessages` entries share the same `addedTime` (also unlikely).
     *
     * `allMessages` is most-recent-first, so reverse to natural arrival order before
     * joining with `\n`. [withCodes] picks the per-line formatter — plain `Component.string`
     * for the default copy mode, or `Component.toLegacyText()` for the Ctrl-modifier mode.
     */
    private fun resolveFullMessageText(
        chat: net.minecraft.client.gui.components.ChatComponent,
        fcs: FormattedCharSequence,
        withCodes: Boolean,
    ): String? {
        val line = chat.trimmedMessages.firstOrNull { it.content() === fcs } ?: return null
        val targetTime = line.addedTime()
        val batch = chat.allMessages.filter { it.addedTime() == targetTime }
        if (batch.isEmpty()) return null
        // allMessages is prepended-most-recent-first; reverse for reading order. For
        // plain-text mode (withCodes == false) we must strip `§<char>` sequences from the
        // result: Hypixel often embeds color codes directly in a TextComponent's literal
        // content (rather than encoding color purely through Mojang's Style tree), so
        // `Component.string` for `§9Party §8> §b…` returns the raw text **with** codes.
        // The strip catches all single-char `§.` codes including Hypixel's non-vanilla
        // ones (§y / §u / §x scoreboard keys), matching MessageDetector's behavior.
        return batch.asReversed().joinToString("\n") { msg ->
            if (withCodes) msg.content().toLegacyText() else MessageDetector.stripColorCodes(msg.content().string)
        }
    }

    /**
     * Walk a [FormattedCharSequence] and accumulate just the codepoints — plain text, no
     * style information. Used for the Shift-modifier path (single line) and as a fallback
     * when full-message lookup fails.
     */
    private fun FormattedCharSequence.toPlainText(): String {
        val sb = StringBuilder()
        this.accept { _, _, codePoint ->
            sb.appendCodePoint(codePoint)
            true
        }
        return sb.toString()
    }

    /**
     * Custom [ActiveTextCollector] that records the `FormattedCharSequence` whose bounding
     * box contains the click point. Driven by [net.minecraft.client.gui.components.ChatComponent.captureClickableText],
     * which calls `accept(alignment, x, y, params, fcs)` for each visible chat line — `params.pose()`
     * carries the chat-scale transform so the (x, y) anchor maps to screen coordinates via
     * `pose.transformPosition`.
     *
     * Width = `font.width(fcs)`, height = [LINE_HEIGHT_PX]. Alignment is honored when
     * computing the bbox's left edge (chat is left-aligned, but the API allows
     * `TextAlignment.CENTER` / `RIGHT` too).
     *
     * First-hit-wins: if multiple lines overlap the click (shouldn't, but defensively),
     * the first one keeps the `hit` field.
     */
    private class LineFinder(
        private val mc: Minecraft,
        private val testX: Float,
        private val testY: Float,
    ) : ActiveTextCollector {
        var hit: FormattedCharSequence? = null

        // Default Parameters with an identity Matrix3x2f. Mojang overwrites this via
        // `defaultParameters(...)` before walking lines, so the initial value just needs
        // to be a valid object (not actually used).
        private var defaults = ActiveTextCollector.Parameters(Matrix3x2f())

        override fun defaultParameters(): ActiveTextCollector.Parameters = defaults

        override fun defaultParameters(p: ActiveTextCollector.Parameters) {
            this.defaults = p
        }

        override fun accept(
            alignment: TextAlignment,
            x: Int,
            y: Int,
            params: ActiveTextCollector.Parameters,
            text: FormattedCharSequence,
        ) {
            if (hit != null) return
            val pos = Vector2f(x.toFloat(), y.toFloat())
            params.pose().transformPosition(pos)
            val width = mc.font.width(text).toFloat()
            val left =
                when (alignment) {
                    TextAlignment.LEFT -> pos.x
                    TextAlignment.CENTER -> pos.x - width / 2f
                    TextAlignment.RIGHT -> pos.x - width
                }
            val top = pos.y
            val bottom = top + LINE_HEIGHT_PX
            if (testX in left..(left + width) && testY in top..bottom) {
                hit = text
            }
        }

        override fun acceptScrolling(
            content: Component,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            scrollWidth: Int,
            params: ActiveTextCollector.Parameters,
        ) {
            // Chat doesn't use the scrolling-text overload; left as a no-op.
        }
    }
}
