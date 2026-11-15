package com.soulreturns.sound.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.sound.RecentSound
import com.soulreturns.sound.RecentSounds
import com.soulreturns.sound.ReplaceRule
import com.soulreturns.sound.SoundNames
import com.soulreturns.sound.SoundRef
import com.soulreturns.sound.SoundReplacer
import com.soulreturns.ui.SoulUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundSource
import kotlin.math.roundToInt

/**
 * The sound replacer ("Rules + live panel", the user's pick): rules on the left (switch, original
 * sound, arrow, "Muted" or the replacement with a play button, edit and delete), and on the right
 * the sounds the game played just now ([RecentSounds]), so a Hypixel sound is found by ear: ＋ on a
 * row turns it into a Mute or Replace… rule. The heard list holds still while the pointer is over
 * it, so a row cannot move away under a click. The category filter stays in a rule's editor.
 */
class SoundReplacerScreen(
    private val parent: Screen?,
) : SoundScreenBase(Component.literal("Sound replacer")) {
    companion object {
        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(SoundReplacerScreen(parent)) }
        }

        private const val TOP_H = 34
        private const val FOOT_H = 32
        private const val RULE_H = 30
        private const val EDIT_H = 28
        private const val CATEGORY_W = 104
        private val MUTED_BG: Int = 0xFF3A1D1D.toInt()
        private val MUTED_TEXT: Int = 0xFFF08A8A.toInt()
    }

    private var editing: Int? = null
    private var rulesScroll = 0f
    private var rulesContentH = 0
    private var rulesRect = intArrayOf(0, 0, 0, 0)
    private var heardScroll = 0f
    private var heardContentH = 0
    private var heardRect = intArrayOf(0, 0, 0, 0)
    private var heardFrozen: List<RecentSound>? = null

    private val categoryNames: List<String> = SoundSource.entries.map { it.getName() }

    override fun typeToSearch(): Boolean = false

    // ---------------------------------------------------------------- render

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        regions.clear()
        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()
        g.pose().pushMatrix()
        g.pose().scale(s, s)

        val cardW = (uiW * 0.92f).toInt().coerceAtMost(1040).coerceAtMost(uiW - 8)
        val cardH = (uiH * 0.9f).toInt().coerceAtMost(680).coerceAtMost(uiH - 8)
        val x0 = (uiW - cardW) / 2
        val y0 = (uiH - cardH) / 2
        val x1 = x0 + cardW
        val y1 = y0 + cardH
        val bodyY0 = y0 + TOP_H
        val bodyY1 = y1 - FOOT_H
        val heardW = (cardW * 0.4f).toInt().coerceIn(190, 360)

        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)

        drawTop(g, x0, y0, x1)
        drawRules(g, mx, my, x0, bodyY0, x1 - heardW, bodyY1)
        drawHeard(g, mx, my, x1 - heardW, bodyY0, x1, bodyY1)
        drawFooter(g, mx, my, x0, bodyY1, x1, y1)

        drawOpenDropdown(g, uiW, uiH, mx, my)
        drawAlertModal(g, uiW, uiH, mx, my)
        g.pose().popMatrix()
    }

    private fun drawTop(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
    ) {
        val y1 = y0 + TOP_H
        roundRect(g, x0, y0, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fillGradient(x0, y1, x1, y1 + 5, 0x50000000, 0x00000000)
        val on = SoundReplacer.enabled
        val label = if (on) "Replacer on" else "Replacer off"
        val sw = 30
        val swX = x1 - 14 - sw
        drawSwitch(g, swX, (y0 + y1) / 2 - 8, sw, 16, on, null) { SoundReplacer.enabled = !on }
        val lw = textWidth(label, fBody)
        drawTextV(g, label, swX - 8 - lw, y0, y1, if (on) Theme.TEXT else Theme.TEXT_DIM, fBody)
        drawCrumb(g, "General", "Sound replacer", x0 + 14, y0, y1, swX - 8 - lw - 12 - (x0 + 14))
    }

    private fun drawRules(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val rules = SoundReplacer.rules()
        var top = y0 + 8
        if (!SoundReplacer.enabled && rules.isNotEmpty()) {
            val bh = 24
            roundRect(g, x0 + 10, top, x1 - 10, top + bh, 0xFF2A2416.toInt(), SoulRenderPipelines.ROUND_SMALL)
            val bw = buttonW("Turn on")
            drawButton(g, mx, my, x1 - 14 - bw, top + 4, x1 - 14, top + bh - 4, "Turn on") { SoundReplacer.enabled = true }
            drawTextV(g, ellipsize("The replacer is off: these rules do nothing.", x1 - 14 - bw - 8 - (x0 + 18), fBody), x0 + 18, top, top + bh, 0xFFFFCF6B.toInt(), fBody)
            top += bh + 6
        }
        rulesRect = intArrayOf(x0, top, x1, y1)
        if (rules.isEmpty()) {
            val lines =
                wrapText(
                    "No rules yet. Press + next to a sound you just heard to mute it or play another sound in its place.",
                    x1 - x0 - 48,
                    fBody,
                )
            var ty = top + 30
            for (line in lines) {
                drawTextCentered(g, line, (x0 + x1) / 2, ty, Theme.TEXT_FAINT, fBody)
                ty += 13
            }
            rulesContentH = 0
            return
        }
        val heights = rules.indices.map { RULE_H + 4 + if (editing == it) editorH(x1 - x0 - 20) + 2 else 0 }
        rulesContentH = heights.sum() + 4
        rulesScroll = clampScroll(rulesScroll, rulesContentH, y1 - top)
        beginClip(g, x0, top, x1, y1)
        var y = top - rulesScroll.roundToInt()
        for ((i, rule) in rules.withIndex()) {
            val h = heights[i]
            if (y + h > top && y < y1) drawRule(g, mx, my, x0 + 10, y, x1 - 10, i, rule)
            y += h
        }
        endClip(g)
    }

    private fun drawRule(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y: Int,
        x1: Int,
        index: Int,
        rule: ReplaceRule,
    ) {
        val vp = rulesRect
        val open = editing == index
        val bottom = y + RULE_H + if (open) editorH(x1 - x0) + 2 else 0
        val rowHover = mx in x0..x1 && my in y until y + RULE_H && within(vp, mx, my)
        val bg = if (open) 0xFF222733.toInt() else if (rowHover) Theme.CONTROL else Theme.SECTION
        roundRect(g, x0, y, x1, bottom, bg, SoulRenderPipelines.ROUND_SMALL)
        // The whole row opens the editor; the switch, play and delete buttons drawn after it win.
        val toggleEditor = {
            closeDropdown()
            editing = if (open) null else index
        }
        clippedRegion(x0, y, x1, y + RULE_H - 1, vp, toggleEditor)
        val cy = y + RULE_H / 2
        drawSwitch(g, x0 + 8, cy - 7, 26, 14, rule.enabled, vp) { SoundReplacer.updateRule(index, rule.copy(enabled = !rule.enabled)) }

        // Right side: delete, then the expand caret.
        var right = x1 - 6
        val delW = 16
        val delHover = mx in (right - delW)..right && my in (cy - 8)..(cy + 8) && within(vp, mx, my)
        if (delHover) roundRect(g, right - delW, cy - 8, right, cy + 8, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, "×", right - delW + (delW - textWidth("×", fBody)) / 2, cy - 7, cy + 7, if (delHover) 0xFFE05252.toInt() else Theme.TEXT_DIM, fBody)
        clippedRegion(right - delW, cy - 8, right, cy + 8, vp) {
            showConfirm("Delete this rule?", "${ruleTitle(rule)} will sound like normal again.", "Delete") {
                SoundReplacer.removeRule(index)
                if (editing == index) editing = null
            }
        }
        right -= delW + 2
        val caretW = 14
        val ccx = right - caretW / 2f
        val caretColor = if (open || rowHover) Theme.TEXT else Theme.TEXT_DIM
        if (open) {
            fillPolygon(g, floatArrayOf(ccx - 3.5f, cy + 2f, ccx + 3.5f, cy + 2f, ccx, cy - 2f), caretColor)
        } else {
            fillPolygon(g, floatArrayOf(ccx - 3.5f, cy - 2f, ccx + 3.5f, cy - 2f, ccx, cy + 2f), caretColor)
        }
        right -= caretW + 6

        // Middle: original → result.
        val textX0 = x0 + 8 + 26 + 10
        val arrowW = textWidth("→", fBody) + 12
        val avail = right - textX0 - arrowW
        val replacement = rule.replacement
        val replacing = rule.action == ReplaceRule.REPLACE && replacement != null
        // Split by what each side's name needs, so a short "Muted" leaves its room to the original.
        val fromNeed = textWidth(ruleTitle(rule), fBody) + 8
        val toNeed = if (replacement != null && replacing) textWidth(SoundNames.title(replacement), fBody) + 26 else textWidth("Muted", fCaption) + 14
        val fromW =
            if (fromNeed + toNeed <= avail) {
                avail - toNeed
            } else {
                (avail * fromNeed.toFloat() / (fromNeed + toNeed)).toInt().coerceIn((avail * 0.4f).toInt(), (avail * 0.75f).toInt())
            }
        val dim = !rule.enabled
        val category = rule.sourceFilter?.let { " · $it only" } ?: ""
        drawTitles(g, ruleTitle(rule), rule.match + category, textX0, textX0 + fromW - 4, y, y + RULE_H, if (dim) Theme.TEXT_DIM else Theme.TEXT)
        val ax = textX0 + fromW
        drawTextV(g, "→", ax + 6, cy - 7, cy + 7, Theme.TEXT_FAINT, fBody)
        val tx0 = ax + arrowW
        if (replacing && replacement != null) {
            drawPlayButton(g, mx, my, tx0 + 8, cy, 7, isPlaying(replacement), vp) { togglePreview(replacement) }
            val vol = if (replacement.volume != 1f || replacement.pitch != 1f) " · ${volumeText(replacement.volume)} · ${pitchText(replacement.pitch)}" else ""
            drawTitles(g, SoundNames.title(replacement), SoundNames.idLine(replacement) + vol, tx0 + 20, right, y, y + RULE_H, if (dim) Theme.TEXT_DIM else Theme.TEXT)
        } else {
            drawTag(g, "Muted", tx0 + textWidth("Muted", fCaption) + 8, cy, MUTED_TEXT, MUTED_BG)
        }
        if (dim) roundRect(g, x0, y, x1, y + RULE_H, 0x60161616, SoulRenderPipelines.ROUND_SMALL)
        if (open) drawEditor(g, mx, my, x0, y + RULE_H, x1, index, rule)
    }

    /** The rule's editor row: action (Mute / Replace…) and the optional category filter. */
    private fun drawEditor(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y: Int,
        x1: Int,
        index: Int,
        rule: ReplaceRule,
    ) {
        val vp = rulesRect
        val twoLines = editorH(x1 - x0) > EDIT_H
        var by0 = y + 4
        var by1 = y + EDIT_H - 6
        var x = x0 + 44
        drawTextV(g, "Do", x, by0, by1, Theme.TEXT_DIM, fBody)
        x += textWidth("Do", fBody) + 8
        val muteW = buttonW("Mute")
        val mute = rule.action == ReplaceRule.MUTE
        clippedButton(g, mx, my, x, by0, x + muteW, by1, "Mute", vp, accent = mute) {
            SoundReplacer.updateRule(index, rule.copy(action = ReplaceRule.MUTE))
        }
        x += muteW + 4
        val repW = buttonW("Replace…")
        clippedButton(g, mx, my, x, by0, x + repW, by1, "Replace…", vp, accent = !mute) { pickReplacement(index, rule) }
        x += repW + 14
        if (twoLines) {
            x = x0 + 44
            by0 += EDIT_H - 4
            by1 += EDIT_H - 4
        }
        drawTextV(g, "In", x, by0, by1, Theme.TEXT_DIM, fBody)
        // Line the dropdown up with the buttons above it when it wraps.
        val dx0 = if (twoLines) x0 + 44 + textWidth("Do", fBody) + 8 else x + textWidth("In", fBody) + 8
        val dx1 = dx0 + CATEGORY_W
        val dy0 = by0
        val dy1 = by1
        val token = "category:$index"
        val labels = listOf("Any category") + categoryNames.map { SoundNames.groupTitle(it) }
        val current = rule.sourceFilter?.let { categoryNames.indexOf(it) + 1 } ?: 0
        drawDropdownButton(g, dx0, dy0, dx1, dy1, labels[current.coerceAtLeast(0)], isDropdownOpen(token))
        clippedRegion(dx0, dy0, dx1, dy1, vp) {
            if (isDropdownOpen(token)) {
                closeDropdown()
            } else {
                openDropdownAt(token, dx0, dy0, dx1, dy1, labels, current) { picked ->
                    val filter = if (picked == 0) null else categoryNames[picked - 1]
                    SoundReplacer.updateRule(index, rule.copy(sourceFilter = filter))
                }
            }
        }
    }

    /** Editor height for a rule card [w] wide: one line, or two when the category filter does not fit beside the action. */
    private fun editorH(w: Int): Int {
        val oneLine =
            44 + textWidth("Do", fBody) + 8 + buttonW("Mute") + 4 + buttonW("Replace…") + 14 + textWidth("In", fBody) + 8 + CATEGORY_W
        return if (oneLine <= w - 8) EDIT_H else EDIT_H * 2 - 4
    }

    private fun drawHeard(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        g.fill(x0, y0, x1, y1, Theme.SECTION)
        g.fill(x0, y0, x0 + 1, y1, Theme.SEPARATOR)
        val headH = 26
        val inside = mx in x0..x1 && my in y0..y1 && !alertModalOpen && !dropdownOpen
        // Hold the list still under the pointer; new sounds land once it leaves.
        val sounds =
            if (inside || dropdownOpen) {
                heardFrozen ?: RecentSounds.snapshot().also { heardFrozen = it }
            } else {
                heardFrozen = null
                RecentSounds.snapshot()
            }
        val semi = MsdfFonts.menuSemibold
        liveDot(g, x0 + 16, y0 + headH / 2 + 1)
        val title = "Heard just now"
        drawTextV(g, title, x0 + 26, y0 + 2, y0 + headH, Theme.TEXT, fBody, semi)
        val countText = if (inside && heardFrozen != null) "${sounds.size} · paused" else sounds.size.toString()
        drawTextV(g, countText, x0 + 26 + textWidth(title, fBody, semi) + 6, y0 + 2, y0 + headH, Theme.TEXT_FAINT, fCaption)
        if (sounds.isNotEmpty()) {
            val cw = buttonW("Clear") - 6
            drawGhostButton(g, mx, my, x1 - 8 - cw, y0 + 6, x1 - 8, y0 + headH - 2, "Clear") {
                RecentSounds.clear()
                heardFrozen = null
            }
        }
        val ly0 = y0 + headH + 2
        heardRect = intArrayOf(x0, ly0, x1, y1)
        if (sounds.isEmpty()) {
            val lines = wrapText("Nothing yet. Play on: every sound the game makes shows up here, newest first.", x1 - x0 - 28, fBody)
            var ty = ly0 + 20
            for (line in lines) {
                drawTextCentered(g, line, (x0 + x1) / 2, ty, Theme.TEXT_FAINT, fBody)
                ty += 13
            }
            heardContentH = 0
            return
        }
        heardContentH = sounds.size * ROW_H + 4
        heardScroll = clampScroll(heardScroll, heardContentH, y1 - ly0)
        beginClip(g, x0, ly0, x1, y1)
        val first = (heardScroll / ROW_H).toInt().coerceAtLeast(0)
        val last = ((heardScroll + (y1 - ly0)) / ROW_H).toInt().coerceAtMost(sounds.size - 1)
        for (i in first..last) {
            val ry0 = ly0 + i * ROW_H - heardScroll.roundToInt()
            drawHeardRow(g, mx, my, x0 + 6, ry0, x1 - 6, ry0 + ROW_H, i, sounds[i])
        }
        endClip(g)
    }

    private fun drawHeardRow(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        index: Int,
        sound: RecentSound,
    ) {
        val vp = heardRect
        drawRowBg(g, mx, my, x0, y0, x1, y1, index, false, vp)
        val cy = (y0 + y1) / 2
        val ref = SoundRef(SoundRef.VANILLA, sound.id)
        drawPlayButton(g, mx, my, x0 + 12, cy, 7, isPlaying(ref), vp) { togglePreview(ref) }
        var right = x1 - 4
        val ruleIndex =
            SoundReplacer.rules().indexOfFirst {
                SoundRef.normalizeVanillaId(it.match) == sound.id && (it.sourceFilter.isNullOrBlank() || it.sourceFilter.equals(sound.category, ignoreCase = true))
            }
        if (ruleIndex >= 0) {
            // One chip for "a rule covers this": what it did to the latest play, click to edit the rule.
            val muted = sound.outcome == RecentSound.MUTED
            val label = if (sound.outcome == RecentSound.PLAYED) "Rule" else sound.outcome
            val w = textWidth(label, fCaption) + 10
            val hover = mx in (right - w)..right && my in (cy - 7)..(cy + 7) && within(vp, mx, my)
            val bg = if (muted) MUTED_BG else if (hover) Theme.ACCENT else Theme.ACCENT_DIM
            roundRect(g, right - w, cy - 7, right, cy + 7, bg, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, label, right - w + 5, cy - 7, cy + 7, if (muted) MUTED_TEXT else Theme.TEXT, fCaption)
            clippedRegion(right - w, cy - 7, right, cy + 7, vp) { editing = ruleIndex }
            right -= w + 6
        } else {
            val w = 18
            val token = "add:${sound.id}:${sound.category}"
            val hover = mx in (right - w)..right && my in (cy - 8)..(cy + 8) && within(vp, mx, my)
            roundRect(g, right - w, cy - 8, right, cy + 8, if (hover || isDropdownOpen(token)) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, "+", right - w + (w - textWidth("+", fBody)) / 2, cy - 7, cy + 7, Theme.TEXT, fBody)
            val bx1 = right
            clippedRegion(right - w, cy - 8, right, cy + 8, vp) {
                if (isDropdownOpen(token)) {
                    closeDropdown()
                } else {
                    openDropdownAt(token, bx1 - 92, cy - 8, bx1, cy + 8, listOf("Mute", "Replace…"), -1) { picked ->
                        if (picked == 0) addRule(ReplaceRule(match = sound.id, action = ReplaceRule.MUTE)) else pickNewReplacement(sound.id)
                    }
                }
            }
            right -= w + 6
        }
        val c = "${sound.count}×"
        right -= textWidth(c, fCaption)
        drawTextV(g, c, right, cy - 6, cy + 6, Theme.TEXT_DIM, fCaption)
        right -= 6
        drawTitles(g, SoundNames.vanillaTitle(sound.id), "${sound.id} · ${sound.category}", x0 + 24, right, y0, y1)
    }

    private fun drawFooter(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        roundRect(g, x0, y0, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_BOTTOM)
        g.fill(x0, y0, x1, y0 + 1, Theme.SEPARATOR)
        val by0 = y0 + 6
        val by1 = y1 - 6
        val dw = buttonW("Done").coerceAtLeast(56)
        drawButton(g, mx, my, x1 - 8 - dw, by0, x1 - 8, by1, "Done") { onClose() }
        val lib = "Sound library"
        val lw = buttonW(lib)
        drawButton(g, mx, my, x1 - 8 - dw - 6 - lw, by0, x1 - 8 - dw - 6, by1, lib) {
            minecraft?.setScreenAndShow(SoundLibraryScreen(this))
        }
        val add = "+ Add rule"
        drawGhostButton(g, mx, my, x0 + 6, by0, x0 + 6 + buttonW(add), by1, add) { pickMatch() }
    }

    // ---------------------------------------------------------------- actions

    private fun ruleTitle(rule: ReplaceRule): String = SoundNames.vanillaTitle(SoundRef.normalizeVanillaId(rule.match))

    /** A rule only works while the replacer is on, so adding one here switches it on. */
    private fun addRule(rule: ReplaceRule) {
        SoundReplacer.addRule(rule)
        if (!SoundReplacer.enabled) SoundReplacer.enabled = true
        editing = SoundReplacer.rules().size - 1
        rulesScroll = Float.MAX_VALUE
    }

    private fun pickMatch() {
        val pick =
            SoundPick("Sound replacer", "Sound to change", null, allowNone = false, minecraftOnly = true, onPick = { ref ->
                if (ref != null) addRule(ReplaceRule(match = ref.id, action = ReplaceRule.MUTE))
            })
        minecraft?.setScreenAndShow(SoundPickerScreen(this, pick))
    }

    private fun pickNewReplacement(id: String) {
        val pick =
            SoundPick(SoundNames.vanillaTitle(id), "Replace with", null, allowNone = false, onPick = { ref ->
                if (ref != null) addRule(ReplaceRule(match = id, action = ReplaceRule.REPLACE, replacement = ref))
            })
        minecraft?.setScreenAndShow(SoundPickerScreen(this, pick))
    }

    private fun pickReplacement(
        index: Int,
        rule: ReplaceRule,
    ) {
        val pick =
            SoundPick(ruleTitle(rule), "Replace with", rule.replacement, allowNone = false, onPick = { ref ->
                if (ref != null) SoundReplacer.updateRule(index, rule.copy(action = ReplaceRule.REPLACE, replacement = ref))
            })
        minecraft?.setScreenAndShow(SoundPickerScreen(this, pick))
    }

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            return
        }
        if (dropdownOpen) {
            closeDropdown()
            return
        }
        returnTo(parent)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        if (alertModalOpen) return true
        closeDropdown()
        val s = SoulUi.factor()
        val mx = (mouseX / s).roundToInt()
        val my = (mouseY / s).roundToInt()
        if (within(heardRect, mx, my)) {
            heardScroll = clampScroll(heardScroll - scrollY.toFloat() * SCROLL_STEP, heardContentH, heardRect[3] - heardRect[1])
        } else {
            rulesScroll = clampScroll(rulesScroll - scrollY.toFloat() * SCROLL_STEP, rulesContentH, rulesRect[3] - rulesRect[1])
        }
        return true
    }
}
