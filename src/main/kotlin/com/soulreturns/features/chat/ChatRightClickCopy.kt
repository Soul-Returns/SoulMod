package com.soulreturns.features.chat

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
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
import net.minecraft.network.chat.HoverEvent
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix3x2f
import org.joml.Vector2f

/**
 * Right-click in chat → copy to clipboard. Invoked by `ChatScreenRightClickCopyMixin`
 * at the HEAD of `ChatScreen.mouseClicked` whenever the right mouse button (button == 1)
 * fires. Four modes selected via modifier keys at click time:
 *
 *  - **Plain right-click** — full source message under the cursor, plain text (no §-codes).
 *    For multi-line server banners (Hypixel welcome / fire-sale messages that embed `\n`)
 *    this includes every visible row of the same source `GuiMessage`.
 *  - **Shift + right-click** — only the single visible line under the cursor, plain text.
 *  - **Ctrl + right-click** — full source message with §-color codes preserved.
 *  - **Alt + right-click** — JSON envelope: `{message, messagePlain, lines: [{text, tooltip?}]}`
 *    capturing the §-coded message AND any hover-tooltip components attached to its
 *    line(s). Used to inspect multi-part Hypixel chat (e.g. `[Sacks] +30 items` whose
 *    per-item breakdown lives in the line's hover tooltip).
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
    private val gson = GsonBuilder().setPrettyPrinting().create()

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
        alt: Boolean,
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

        // Alt takes priority over Shift/Ctrl combinations — it's the diagnostic dump mode
        // and shouldn't be silently downgraded if the user happens to be holding Shift.
        val toCopy: String =
            when {
                alt -> buildTooltipEnvelope(chat, fcs) ?: fcs.toPlainText()
                shift -> fcs.toPlainText()
                ctrl -> resolveFullMessageText(chat, fcs, withCodes = true) ?: fcs.toPlainText()
                else -> resolveFullMessageText(chat, fcs, withCodes = false) ?: fcs.toPlainText()
            }

        if (toCopy.isEmpty()) return false
        mc.keyboardHandler.setClipboard(toCopy)
        val label =
            when {
                alt -> "message with tooltips (JSON)"
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
        val batch = resolveBatch(chat, fcs) ?: return null
        // For plain-text mode (withCodes == false) we must strip `§<char>` sequences from
        // the result: Hypixel often embeds color codes directly in a TextComponent's
        // literal content (rather than encoding color purely through Mojang's Style tree),
        // so `Component.string` for `§9Party §8> §b…` returns the raw text **with** codes.
        // The strip catches all single-char `§.` codes including Hypixel's non-vanilla
        // ones (§y / §u / §x scoreboard keys), matching MessageDetector's behavior.
        return batch.joinToString("\n") { msg ->
            if (withCodes) msg.content().toLegacyText() else MessageDetector.stripColorCodes(msg.content().string)
        }
    }

    /**
     * Locate the same-`addedTime` batch as the clicked line and return it in natural
     * arrival order (oldest first). `allMessages` is prepended-most-recent-first, so the
     * filter has to be reversed before consumers see it.
     */
    private fun resolveBatch(
        chat: net.minecraft.client.gui.components.ChatComponent,
        fcs: FormattedCharSequence,
    ): List<GuiMessage>? {
        val line = chat.trimmedMessages.firstOrNull { it.content() === fcs } ?: return null
        val targetTime = line.addedTime()
        val batch = chat.allMessages.filter { it.addedTime() == targetTime }
        if (batch.isEmpty()) return null
        return batch.asReversed()
    }

    /**
     * Build the Alt-RC JSON envelope: top-level joins of the full batch (with and without
     * codes) plus a per-line breakdown carrying any hover-tooltip the line's root
     * component declared. Used as the diagnostic protocol surface for the future sack
     * chat reader — Hypixel's `[Sacks] +N items` lines carry the per-item breakdown as
     * a `HoverEvent.ShowText` value on the root Component, and this dump is how we
     * empirically capture its shape before writing the parser.
     *
     * Tooltip strategy is **first non-null per line**: multiple hover-bearing siblings on
     * a single chat line are rare (only clickable usernames in the middle of a sentence
     * carry their own hover), and we prefer the root-level tooltip Hypixel uses for
     * structured batch reports. Lines with no hover omit the `tooltip` field entirely
     * (terse JSON for grepping).
     */
    private fun buildTooltipEnvelope(
        chat: net.minecraft.client.gui.components.ChatComponent,
        fcs: FormattedCharSequence,
    ): String? {
        val batch = resolveBatch(chat, fcs) ?: return null
        val linesArr = JsonArray()
        val codedParts = ArrayList<String>(batch.size)
        val plainParts = ArrayList<String>(batch.size)
        for (msg in batch) {
            val content = msg.content()
            val coded = content.toLegacyText()
            val plain = MessageDetector.stripColorCodes(content.string)
            codedParts += coded
            plainParts += plain
            val lineObj =
                JsonObject().apply {
                    addProperty("text", coded)
                }
            extractFirstHoverText(content)?.let { hover ->
                lineObj.addProperty("tooltip", hover.toLegacyText())
            }
            linesArr.add(lineObj)
        }
        val obj =
            JsonObject().apply {
                addProperty("message", codedParts.joinToString("\n"))
                addProperty("messagePlain", plainParts.joinToString("\n"))
                add("lines", linesArr)
            }
        return gson.toJson(obj)
    }

    /**
     * Walk a [Component] tree depth-first and return the first `HoverEvent.ShowText`
     * value encountered, or null if no such hover exists. Recursion mirrors the natural
     * `siblings` traversal order so root-level hovers (Hypixel's normal placement) are
     * found before per-segment hovers (clickable usernames mid-line).
     *
     * Pattern-matches on `HoverEvent.ShowText` via `is` rather than a `when (hover)` on
     * the sealed interface — the latter emits a `$WhenMappings` synthetic that Fabric's
     * KnotClassLoader can fail to resolve at runtime (see CLAUDE.md note on enum/sealed
     * `when` subjects). `HoverEvent.ShowItem` and `HoverEvent.ShowEntity` are intentionally
     * ignored for v1; chat tooltip data we care about lives in ShowText.
     */
    private fun extractFirstHoverText(component: Component): Component? {
        val hover = component.style.hoverEvent
        if (hover is HoverEvent.ShowText) return hover.value()
        for (sibling in component.siblings) {
            val found = extractFirstHoverText(sibling)
            if (found != null) return found
        }
        return null
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
