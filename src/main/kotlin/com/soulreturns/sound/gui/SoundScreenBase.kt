package com.soulreturns.sound.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.sound.CustomSound
import com.soulreturns.sound.SoulSounds
import com.soulreturns.sound.SoundLibrary
import com.soulreturns.sound.SoundRef
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared widgets of the sound screens ([SoundLibraryScreen], [SoundPickerScreen],
 * [SoundReplacerScreen]): sound rows (play button, subtitle + id lines, tag, favorite star), a
 * drag slider, the search field with type-to-search, file-drop import and the preview state.
 * Icons the MSDF atlas lacks (play triangle, star) are scanline-filled polygons in physical pixels.
 */
abstract class SoundScreenBase(title: Component) : SoulScreenBase(title) {
    protected companion object {
        const val ROW_H = 26
        const val SCROLL_STEP = 20f
        val STAR_ON: Int = 0xFFFFCF3B.toInt()
        val MINE_TEXT: Int = 0xFFFFCF6B.toInt()
        val MINE_BG: Int = 0xFF3A2F17.toInt()
        val LIVE_RED: Int = 0xFFEF4444.toInt()
        val SELECTED_ROW: Int = 0xFF242A36.toInt()
        val ZEBRA: Int = 0x06FFFFFF
        val WAVE: Int = 0xAA3B82F6.toInt()
        const val DOUBLE_CLICK_MS = 350L
    }

    protected val search = SoulTextField()

    /** The ref the current preview plays, so its row shows a stop button while it lasts. */
    private var previewRef: SoundRef? = null

    private class Drag(val x0: Int, val x1: Int, val onDrag: (Float) -> Unit, val onRelease: () -> Unit)

    private var drag: Drag? = null

    private var lastClickKey: String? = null
    private var lastClickAt = 0L

    // ---------------------------------------------------------------- preview

    protected fun isPlaying(ref: SoundRef): Boolean {
        val playing = previewRef ?: return false
        if (!playing.sameSound(ref)) return false
        if (SoulSounds.isPreviewing()) return true
        previewRef = null
        return false
    }

    protected fun togglePreview(ref: SoundRef) {
        if (isPlaying(ref)) {
            SoulSounds.stopPreview()
            previewRef = null
        } else {
            previewRef = if (SoulSounds.preview(ref) != null) ref else null
        }
    }

    protected fun preview(ref: SoundRef) {
        previewRef = if (SoulSounds.preview(ref) != null) ref else null
    }

    override fun removed() {
        SoulSounds.stopPreview()
        previewRef = null
        super.removed()
    }

    /** True when this click on [key] follows the previous one quickly enough to count as a double click. */
    protected fun doubleClicked(key: String): Boolean {
        val now = System.currentTimeMillis()
        val double = key == lastClickKey && now - lastClickAt <= DOUBLE_CLICK_MS
        lastClickKey = if (double) null else key
        lastClickAt = now
        return double
    }

    /** Back to [parent], or out of the menus when the screen was opened from a command. */
    protected fun returnTo(parent: net.minecraft.client.gui.screens.Screen?) {
        if (parent != null) minecraft?.setScreenAndShow(parent) else super.onClose()
    }

    // ---------------------------------------------------------------- input

    override fun mouseDragged(
        click: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val d = drag ?: return super.mouseDragged(click, dragX, dragY)
        val s = SoulUi.factor()
        lastMouseX = (click.x() / s).roundToInt()
        lastMouseY = (click.y() / s).roundToInt()
        d.onDrag(frac(lastMouseX, d.x0, d.x1))
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val d = drag
        if (d != null) {
            drag = null
            d.onRelease()
            return true
        }
        return super.mouseReleased(click)
    }

    override fun onEmptyClick() {
        search.focused = false
    }

    /** Keys for the focused search field (and any field a subclass adds via [focusedField]); the rest go to [handleKey]. */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) return super.keyPressed(event)
        val field = focusedField()
        if (field != null) {
            val key = event.key()
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                field.focused = false
                return true
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
                if (handleKey(event)) return true
                if (key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN) field.focused = false
                return true
            }
            field.keyPressed(event, minecraft?.keyboardHandler)
            return true
        }
        if (handleKey(event)) return true
        return super.keyPressed(event)
    }

    /** Typing anywhere starts a search, so finding a sound never needs a click first. */
    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        if (!event.isAllowedChatCharacter()) return super.charTyped(event)
        val field = focusedField()
        if (field != null) {
            field.charTyped(event)
            return true
        }
        if (!typeToSearch()) return super.charTyped(event)
        search.focused = true
        search.charTyped(event)
        return true
    }

    protected open fun focusedField(): SoulTextField? = search.takeIf { it.focused }

    /** Whether a typed character outside any field starts a search. */
    protected open fun typeToSearch(): Boolean = true

    /** Screen keys (arrows, Enter) when no field consumed them. Return true if handled. */
    protected open fun handleKey(event: KeyEvent): Boolean = false

    // ---------------------------------------------------------------- import

    /** Imports every dropped file; [onDone] gets the entries that made it, on the client thread. */
    protected fun importFiles(
        paths: List<Path>,
        onDone: (List<CustomSound>) -> Unit,
    ) {
        if (paths.isEmpty()) return
        showAlert("Importing…", if (paths.size == 1) paths[0].fileName.toString() else "${paths.size} files", busy = true)
        val futures = paths.map { SoundLibrary.importAsync(it) }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            Minecraft.getInstance().execute {
                val results = paths.zip(futures.map { it.join() })
                val ok = results.mapNotNull { it.second.getOrNull() }
                val failed = results.filter { it.second.isFailure }
                if (minecraft?.gui?.screen() !== this) return@execute
                val message =
                    buildString {
                        if (ok.isNotEmpty()) append(ok.joinToString(", ") { it.name })
                        for ((path, result) in failed) {
                            if (isNotEmpty()) append('\n')
                            append(path.fileName.toString()).append(": ").append(result.exceptionOrNull()?.message ?: "could not be read")
                        }
                    }
                val title =
                    when {
                        failed.isEmpty() -> if (ok.size == 1) "Sound imported" else "${ok.size} sounds imported"
                        ok.isEmpty() -> "Import failed"
                        else -> "${ok.size} of ${paths.size} imported"
                    }
                showAlert(title, message, success = failed.isEmpty())
                onDone(ok)
            }
        }
    }

    /** The footer's import button: explains the drop and offers the library folder. */
    protected fun showImportHelp() {
        showConfirm(
            "Import your own sounds",
            "Drag .ogg, .wav or .mp3 files from your file manager onto this window. Up to 60 seconds and 16 MB each; " +
                "they are copied into the library, so the originals can go.",
            "Open sounds folder",
        ) {
            val dir = SoundLibrary.dir
            runCatching { java.nio.file.Files.createDirectories(dir) }
            net.minecraft.util.Util.getPlatform().openPath(dir)
        }
    }

    // ---------------------------------------------------------------- shapes

    protected fun star(
        g: GuiGraphicsExtractor,
        cx: Float,
        cy: Float,
        r: Float,
        color: Int,
    ) {
        val pts = FloatArray(20)
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.45f
            val a = -PI / 2 + i * PI / 5
            pts[i * 2] = cx + (cos(a) * radius).toFloat()
            pts[i * 2 + 1] = cy + (sin(a) * radius).toFloat() + r * 0.08f
        }
        fillPolygon(g, pts, color)
    }

    /**
     * One sound row: background, play button, subtitle + id lines, then from the right the favorite
     * star, a custom sound's duration, the folder tag (when [showTag]) and a heard count. A click on
     * the row runs [onSelect] with whether it was a double click.
     */
    protected fun drawItemRow(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        item: SoundItem,
        index: Int,
        selected: Boolean,
        viewport: IntArray,
        showTag: Boolean,
        onSelect: (Boolean) -> Unit,
    ) {
        drawRowBg(g, mx, my, x0, y0, x1, y1, index, selected, viewport)
        clippedRegion(x0, y0, x1, y1 - 1, viewport) { onSelect(doubleClicked(item.ref.key)) }
        val cy = (y0 + y1) / 2
        drawPlayButton(g, mx, my, x0 + 13, cy, 8, isPlaying(item.ref), viewport) { togglePreview(item.ref) }
        var right = x1 - 6
        right -= drawStarToggle(g, mx, my, right, cy, SoundLibrary.isFavorite(item.ref), viewport) { SoundLibrary.toggleFavorite(item.ref) } + 6
        item.custom?.let {
            val d = durationText(it.durationMs)
            right -= textWidth(d, fCaption)
            drawTextV(g, d, right, cy - 6, cy + 6, Theme.TEXT_FAINT, fCaption)
            right -= 6
        }
        if (showTag || item.custom != null) {
            val mine = item.custom != null
            right -= drawTag(g, item.tag, right, cy, if (mine) MINE_TEXT else Theme.TEXT_DIM, if (mine) MINE_BG else Theme.CONTROL) + 6
        }
        if (item.heardCount > 0) {
            val c = "${item.heardCount}×"
            right -= textWidth(c, fCaption)
            drawTextV(g, c, right, cy - 6, cy + 6, Theme.TEXT_DIM, fCaption)
            right -= 6
        }
        drawTitles(g, item.title, item.idLine, x0 + 27, right - 2, y0, y1)
    }

    private val peaks = HashMap<String, FloatArray>()

    /**
     * Level strip for the inspector: a custom clip's real peaks (from its decoded PCM, once the clip
     * is decoded), or a plain low bar for Minecraft sounds, whose files the client never decodes up front.
     */
    protected fun drawWave(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        item: SoundItem,
    ) {
        roundRect(g, x0, y0, x1, y1, Theme.SIDEBAR, SoulRenderPipelines.ROUND_SMALL)
        val bars = ((x1 - x0 - 8) / 3).coerceAtLeast(1)
        val levels = item.custom?.let { clipPeaks(it.id, bars) }
        val mid = (y0 + y1) / 2
        val half = (y1 - y0) / 2 - 3
        for (i in 0 until bars) {
            val bx = x0 + 4 + i * 3
            val h = if (levels != null) (levels[i] * half).roundToInt().coerceAtLeast(1) else (half * 0.18f).roundToInt().coerceAtLeast(1)
            g.fill(bx, mid - h, bx + 2, mid + h, if (levels != null) WAVE else Theme.CONTROL_HOVER)
        }
    }

    private fun clipPeaks(
        id: String,
        bars: Int,
    ): FloatArray? {
        peaks["$id/$bars"]?.let { return it }
        val future = com.soulreturns.sound.CustomClips.get(id) ?: return null
        if (!future.isDone || future.isCompletedExceptionally) return null
        val clip = future.join()
        val out = FloatArray(bars)
        val frames = clip.frames
        if (frames == 0) return out
        var max = 1f
        for (b in 0 until bars) {
            val start = (b.toLong() * frames / bars).toInt()
            val end = ((b + 1).toLong() * frames / bars).toInt().coerceAtLeast(start + 1)
            var peak = 0
            var f = start
            // Sampling a few hundred points per bar is plenty for a picture and keeps a 60 s clip cheap.
            val step = ((end - start) / 256).coerceAtLeast(1)
            while (f < end) {
                val s = kotlin.math.abs(clip.pcm[f * clip.channels].toInt())
                if (s > peak) peak = s
                f += step
            }
            out[b] = peak.toFloat()
            if (peak > max) max = peak.toFloat()
        }
        for (b in 0 until bars) out[b] = (out[b] / max).coerceIn(0.04f, 1f)
        peaks["$id/$bars"] = out
        return out
    }

    /** Pill on/off switch at ([x], [y]), [w]×[h]. */
    protected fun drawSwitch(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        on: Boolean,
        viewport: IntArray?,
        onClick: () -> Unit,
    ) {
        roundRect(g, x, y, x + w, y + h, if (on) Theme.ACCENT else Theme.CONTROL_HOVER, SoulRenderPipelines.ROUND_PILL)
        val d = h - 4
        val kx = if (on) x + w - 2 - d else x + 2
        roundRect(g, kx, y + 2, kx + d, y + 2 + d, Theme.TEXT, SoulRenderPipelines.ROUND_PILL)
        if (viewport == null) region(x, y, x + w, y + h, onClick) else clippedRegion(x, y, x + w, y + h, viewport, onClick)
    }

    /** Round play button; a stop square while [playing]. */
    protected fun drawPlayButton(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        cx: Int,
        cy: Int,
        r: Int,
        playing: Boolean,
        viewport: IntArray?,
        onClick: () -> Unit,
    ) {
        val hovered = mx in (cx - r)..(cx + r) && my in (cy - r)..(cy + r) && (viewport == null || within(viewport, mx, my))
        val bg = if (playing) Theme.ACCENT else if (hovered) Theme.CONTROL_HOVER else Theme.CONTROL
        roundRect(g, cx - r, cy - r, cx + r, cy + r, bg, SoulRenderPipelines.ROUND_PILL)
        if (playing) {
            val h = r * 0.42f
            roundRect(g, (cx - h).roundToInt(), (cy - h).roundToInt(), (cx + h).roundToInt(), (cy + h).roundToInt(), Theme.TEXT, SoulRenderPipelines.ROUND_SMALL)
        } else {
            playTriangle(g, cx.toFloat(), cy.toFloat(), r.toFloat(), 0xFFDDDDDD.toInt())
        }
        if (viewport == null) region(cx - r, cy - r, cx + r, cy + r, onClick) else clippedRegion(cx - r, cy - r, cx + r, cy + r, viewport, onClick)
    }

    /** Favorite star toggle; returns its width. */
    protected fun drawStarToggle(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x1: Int,
        cy: Int,
        on: Boolean,
        viewport: IntArray?,
        onClick: () -> Unit,
    ): Int {
        val r = 5
        val cx = x1 - r - 2
        val hovered = mx in (cx - r - 2)..(cx + r + 2) && my in (cy - r - 2)..(cy + r + 2)
        star(g, cx.toFloat(), cy.toFloat(), r + 0.5f, if (on) STAR_ON else if (hovered) Theme.TEXT_DIM else Theme.TEXT_FAINT)
        val x0 = cx - r - 3
        if (viewport == null) region(x0, cy - r - 3, x1, cy + r + 3, onClick) else clippedRegion(x0, cy - r - 3, x1, cy + r + 3, viewport, onClick)
        return x1 - x0
    }

    /** Small pill label ending at [x1]; returns its width. */
    protected fun drawTag(
        g: GuiGraphicsExtractor,
        text: String,
        x1: Int,
        cy: Int,
        fg: Int = Theme.TEXT_DIM,
        bg: Int = Theme.CONTROL,
    ): Int {
        val w = textWidth(text, fCaption) + 8
        roundRect(g, x1 - w, cy - 6, x1, cy + 6, bg, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, text, x1 - w + 4, cy - 6, cy + 6, fg, fCaption)
        return w
    }

    /** Main line + faint id line, both ellipsized to [x0]..[x1]. */
    protected fun drawTitles(
        g: GuiGraphicsExtractor,
        title: String,
        sub: String,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int,
        titleColor: Int = Theme.TEXT,
    ) {
        val w = (x1 - x0).coerceAtLeast(10)
        val mid = (y0 + y1) / 2
        drawTextV(g, ellipsize(title, w, fBody), x0, mid - 11, mid + 1, titleColor, fBody)
        drawTextV(g, ellipsize(sub, w, fCaption), x0, mid + 1, mid + 11, Theme.TEXT_FAINT, fCaption)
    }

    /** A row's background: selected tint, hover, or the faint zebra stripe. */
    protected fun drawRowBg(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        index: Int,
        selected: Boolean,
        viewport: IntArray,
    ) {
        val hovered = mx in x0..x1 && my in y0 until y1 && within(viewport, mx, my)
        if (selected) {
            panel(g, x0, y0, x1, y1, SELECTED_ROW, 0x553B82F6, SoulPanel.SMALL)
        } else if (hovered) {
            roundRect(g, x0, y0, x1, y1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        } else if (index % 2 == 1) {
            roundRect(g, x0, y0, x1, y1, ZEBRA, SoulRenderPipelines.ROUND_SMALL)
        }
    }

    /** A horizontal slider from [x0] to [x1] at [cy] showing [frac]; drag calls [onDrag] with the new fraction. */
    protected fun drawFracSlider(
        g: GuiGraphicsExtractor,
        x0: Int,
        cy: Int,
        x1: Int,
        frac: Float,
        onDrag: (Float) -> Unit,
        onRelease: () -> Unit,
    ) {
        val f = frac.coerceIn(0f, 1f)
        roundRect(g, x0, cy - 2, x1, cy + 2, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
        val fillX = x0 + (f * (x1 - x0)).roundToInt()
        if (fillX > x0 + 1) roundRect(g, x0, cy - 2, fillX, cy + 2, Theme.ACCENT, SoulRenderPipelines.ROUND_PILL)
        roundRect(g, fillX - 5, cy - 5, fillX + 5, cy + 5, Theme.TEXT, SoulRenderPipelines.ROUND_PILL)
        region(x0 - 4, cy - 7, x1 + 4, cy + 7) {
            drag = Drag(x0, x1, onDrag, onRelease)
            onDrag(frac(lastMouseX, x0, x1))
        }
    }

    /** Label, slider and value text in one line between [x0] and [x1]. */
    protected fun drawLabeledSlider(
        g: GuiGraphicsExtractor,
        label: String,
        value: String,
        labelW: Int,
        x0: Int,
        cy: Int,
        x1: Int,
        frac: Float,
        onDrag: (Float) -> Unit,
        onRelease: () -> Unit,
    ) {
        drawTextV(g, label, x0, cy - 6, cy + 6, Theme.TEXT_DIM, fBody)
        val valueW = textWidth("100%", fBody) + 4
        drawTextV(g, value, x1 - textWidth(value, fBody), cy - 6, cy + 6, Theme.TEXT, fBody)
        drawFracSlider(g, x0 + labelW + 6, cy, x1 - valueW - 6, frac, onDrag, onRelease)
    }

    protected fun volumeText(volume: Float): String = "${(volume * 100).roundToInt()}%"

    protected fun pitchText(pitch: Float): String = String.format(Locale.ROOT, "%.2f", pitch)

    protected fun durationText(ms: Long): String = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

    /** Pitch slider maps 0..1 to [PITCH_MIN]..[PITCH_MAX] in 0.05 steps (Minecraft clamps pitch to that range). */
    protected fun pitchFromFrac(f: Float): Float = ((PITCH_MIN + f * (PITCH_MAX - PITCH_MIN)) * 20f).roundToInt() / 20f

    protected fun pitchFrac(p: Float): Float = (p - PITCH_MIN) / (PITCH_MAX - PITCH_MIN)

    protected fun volumeFromFrac(f: Float): Float = (f * 20f).roundToInt() / 20f

    /** Red "live" dot with a soft glow. */
    protected fun liveDot(
        g: GuiGraphicsExtractor,
        cx: Int,
        cy: Int,
    ) {
        roundRect(g, cx - 5, cy - 5, cx + 5, cy + 5, 0x40EF4444, SoulRenderPipelines.ROUND_PILL)
        roundRect(g, cx - 3, cy - 3, cx + 3, cy + 3, LIVE_RED, SoulRenderPipelines.ROUND_PILL)
    }

    /** A flat text button without fill until hovered (footer "ghost" buttons). */
    protected fun drawGhostButton(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        label: String,
        onClick: () -> Unit,
    ) {
        val hovered = mx in x0..x1 && my in y0..y1
        if (hovered) roundRect(g, x0, y0, x1, y1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, label, x0 + (x1 - x0 - textWidth(label, fBody)) / 2, y0, y1, if (hovered) Theme.TEXT else Theme.TEXT_DIM, fBody)
        region(x0, y0, x1, y1, onClick)
    }

    protected fun buttonW(label: String): Int = textWidth(label, fBody) + 20

    /**
     * Card title in the top bar: a dim [prefix] joined by [separator], then the current page in full
     * color. When both do not fit [maxW], only the current page shows (ellipsized).
     */
    protected fun drawCrumb(
        g: GuiGraphicsExtractor,
        prefix: String?,
        current: String,
        x0: Int,
        y0: Int,
        y1: Int,
        maxW: Int,
        separator: String = " › ",
    ) {
        val semi = MsdfFonts.menuSemibold
        val pre = prefix?.let { it + separator }
        if (pre != null && textWidth(pre + current, fHeading, semi) <= maxW) {
            drawTextV(g, pre, x0, y0, y1, Theme.TEXT_DIM, fHeading, semi)
            drawTextV(g, current, x0 + textWidth(pre, fHeading, semi), y0, y1, Theme.TEXT, fHeading, semi)
        } else {
            drawTextV(g, ellipsize(current, maxW, fHeading, semi), x0, y0, y1, Theme.TEXT, fHeading, semi)
        }
    }

    private fun frac(
        v: Int,
        a: Int,
        b: Int,
    ): Float = ((v - a).toFloat() / (b - a).coerceAtLeast(1)).coerceIn(0f, 1f)
}

const val PITCH_MIN = 0.5f
const val PITCH_MAX = 2f
