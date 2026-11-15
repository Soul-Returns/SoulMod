package com.soulreturns.features.dungeons.leap

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.Shapes
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.map.DungeonMapHud
import com.soulreturns.features.dungeons.roles.ArcherCue
import com.soulreturns.features.dungeons.roles.ArcherRoleDisplay
import com.soulreturns.features.dungeons.roles.ArcherRoleLayout
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Soul's leap screen (spec `mocks/leap-menu/SPEC.md`, layout A): four tiles around the screen centre in
 * [LeapSort] order, shown instead of [vanilla] while the real menu stays open underneath. Drawn from the first
 * frame, without any animation: a teammate whose head has not arrived yet stands faded in their corner (from the
 * party roster) and is not clickable.
 *
 * Input: a press of any mouse button anywhere in a screen quadrant, or keys 1-4, picks that quadrant's teammate
 * and hands it to [LeapSession.leap] (at most one container click, on that head, now; nothing, with an amber cue,
 * while the head has not arrived). Esc and the inventory key close the menu as the vanilla screen does; everything
 * else does nothing. When the session stops being certain, the vanilla screen comes back. The hovered teammate is
 * highlighted on [DungeonMapHud], and the dim leaves the map out.
 */
class LeapScreen(private val vanilla: ContainerScreen, private val session: LeapSession) : SoulScreenBase(vanilla.title) {
    companion object {
        // Geometry, Soul px (SPEC §3).
        const val TILE_W = 256
        const val TILE_H = 92
        const val GAP = 14
        const val FACE = 64
        const val FACE_X = 14
        const val TEXT_X = 90
        const val NAME_SIZE = 14f
        const val NAME_TOP = 16
        const val CLASS_SIZE = 9f
        const val CLASS_TOP = 37
        const val LOC_SIZE = 12f
        const val LOC_TOP = 59
        const val KEY_BOX = 14
        const val KEY_SIZE = 8f
        const val GROW = 1.04f
        const val GROW_MS = 120f
        const val FLASH_MS = 180L

        val DIM = 0x1A000000
        val FILL = 0xDB101114.toInt()
        val FILL_HOT = 0xF01E2026.toInt()
        val EDGE = 0x12FFFFFF
        val NAME = 0xFFF2F2F2.toInt()
        val DEAD = 0xFFFF5555.toInt()
        val SEEN = 0xFF7DFF9A.toInt()
        val MUTED = 0xFFC9CFDB.toInt()
        val AGE = 0xFF8D93A0.toInt()
        val UNKNOWN = 0xFFFFD84D.toInt()
        val KEY_EDGE = 0x2EFFFFFF
        val KEY_TEXT = 0x8CFFFFFF.toInt()
        val FLASH_FILL = 0x29FF3C3C
        val FLASH_EDGE = 0xE6FF5555.toInt()
        val WAIT_FILL = 0x29FFB347
        val WAIT_EDGE = 0xE6FFB347.toInt()
        val ROUTE_EDGE = 0xFF90C7AE.toInt()
        val ROUTE_INSET = 0x2090C7AE
        val ROUTE_TEXT = 0xFFA1DBBE.toInt()
        val ROUTE_WAIT = 0xFFE4CB8C.toInt()
        val ROUTE_FOOTER = 0xFFC0C5CD.toInt()

        /** A tile whose head has not arrived yet. */
        const val PENDING_ALPHA = 0.5f

        fun ageText(ms: Long): String {
            val s = ms / 1000
            return if (s < 60) "${s}s" else "${s / 60}m"
        }

        private fun alpha(c: Int, a: Float): Int = (((c ushr 24) * a).roundToInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
    }

    private var handedBack = false
    private var hovered = -1

    /** Per place: hover progress 0..1 (eased in the draw) and when it last changed. */
    private val grow = FloatArray(4)
    private var lastFrame = System.currentTimeMillis()
    private var flashPlace = -1
    private var flashAt = 0L
    private var flashWait = false

    override fun isPauseScreen(): Boolean = false

    override fun isInGameUi(): Boolean = true

    override fun tick() {
        session.poll()
        if (session.state == LeapGate.FAILED) handBack()
    }

    private fun handBack() {
        if (handedBack) return
        handedBack = true
        val mc = minecraft ?: return
        mc.execute { if (mc.gui.screen() === this) mc.gui.setScreen(vanilla) }
    }

    override fun onClose() {
        val player = minecraft?.player
        if (player != null && player.containerMenu === session.menu) player.closeContainer()
        if (minecraft?.gui?.screen() === this) super.onClose()
    }

    override fun removed() {
        super.removed()
        DungeonMapHud.leapHighlight = null
        session.logTiming(if (session.state == LeapGate.SENT) "sent" else "closed")
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || minecraft?.options?.keyInventory?.matches(event) == true) {
            onClose()
            return true
        }
        val k = event.key() - GLFW.GLFW_KEY_1
        if (cfg.dungeons.leap.numberKeys && k in 0..3) pick(k, "key ${k + 1}")
        return true
    }

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        pick(LeapSort.quadrant(click.x(), click.y(), width.toDouble(), height.toDouble()), "click (button ${click.button()})")
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean = true

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean = true

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = true

    /** One physical press chose place [i]: at most one container click, through [LeapSession.leap]. */
    private fun pick(i: Int, how: String) {
        if (session.state == LeapGate.FAILED) return
        val tile = LeapMenu.places(session).getOrNull(i) ?: return
        val out = session.leap(tile.name, cfg.dungeons.leap.blockDead, how)
        if (out == LeapSession.Outcome.BLOCKED_DEAD || out == LeapSession.Outcome.NOT_YET) {
            flashPlace = i
            flashAt = System.currentTimeMillis()
            flashWait = out == LeapSession.Outcome.NOT_YET
        }
    }

    // ---------------------------------------------------------------- render

    /** The dim, with a hole where the dungeon map is so it stays as bright as without the menu. */
    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val r = DungeonMapHud.liveRect()
        if (r == null) {
            g.fill(0, 0, width, height, DIM)
            return
        }
        val sc = SoulUi.factor()
        val x0 = floor(r[0] * sc).toInt().coerceIn(0, width)
        val y0 = floor(r[1] * sc).toInt().coerceIn(0, height)
        val x1 = ceil(r[2] * sc).toInt().coerceIn(x0, width)
        val y1 = ceil(r[3] * sc).toInt().coerceIn(y0, height)
        g.fill(0, 0, width, y0, DIM)
        g.fill(0, y1, width, height, DIM)
        g.fill(0, y0, x0, y1, DIM)
        g.fill(x1, y0, width, y1, DIM)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        session.poll()
        if (session.state == LeapGate.FAILED) {
            handBack()
            return
        }
        val now = System.currentTimeMillis()
        val dt = (now - lastFrame).coerceIn(0, 100)
        lastFrame = now
        val tiles = LeapMenu.places(session, now)
        val roleCue = if (cfg.dungeons.roles.highlightLeap) ArcherRoleDisplay.currentCue() else null
        val routePlace = roleCue?.let { ArcherRoleLayout.targetIndex(it, tiles) } ?: -1
        val q = LeapSort.quadrant(mouseX.toDouble(), mouseY.toDouble(), width.toDouble(), height.toDouble())
        hovered = if (tiles[q] != null) q else -1
        session.frame(now, tiles.count { it != null }, tiles.count { it?.ready == true }, tiles.count { it?.skin != null })
        // The hovered teammate's marker on the dungeon map (drawn by the HUD, under this screen, next frame).
        DungeonMapHud.leapHighlight = tiles.getOrNull(hovered)?.let { DungeonMapHud.Highlight(it.name, it.cls.color) }

        val sc = SoulUi.factor()
        val uiW = width / sc
        val uiH = height / sc
        // Fit (SPEC §3): the cluster only ever shrinks, about the centre.
        val halfHeight = if (roleCue != null) maxOf(TILE_H + GAP, ArcherRoleLayout.FOOTER_Y + 13) else TILE_H + GAP
        val fit = min(1f, min((uiW - 16f) / (2 * TILE_W + 2 * GAP), (uiH - 16f) / (2 * halfHeight)))
        g.pose().pushMatrix()
        g.pose().scale(sc, sc)
        g.pose().translate(uiW / 2f, uiH / 2f)
        g.pose().scale(fit, fit)
        val semi = MsdfFonts.menuSemibold
        for (i in 0 until 4) {
            val t = tiles[i] ?: continue
            val target = if (i == hovered) 1f else 0f
            val step = dt / GROW_MS
            grow[i] = if (grow[i] < target) min(target, grow[i] + step) else maxOf(target, grow[i] - step)
            val left = i % 2 == 0
            val top = i < 2
            val x0 = if (left) -GAP - TILE_W else GAP
            val y0 = if (top) -GAP - TILE_H else GAP
            // Grow about the corner nearest the screen centre (ease-out).
            val e = 1f - (1f - grow[i]) * (1f - grow[i])
            val s = 1f + (GROW - 1f) * e
            val ox = if (left) x0 + TILE_W else x0
            val oy = if (top) y0 + TILE_H else y0
            g.pose().pushMatrix()
            g.pose().translate(ox.toFloat(), oy.toFloat())
            g.pose().scale(s, s)
            g.pose().translate(-ox.toFloat(), -oy.toFloat())
            drawTile(g, t, i, x0, y0, i == hovered, now, semi, roleCue.takeIf { i == routePlace })
            g.pose().popMatrix()
        }
        if (roleCue != null) {
            SoulText.drawCentered(g, "${roleCue.text} · Manual selection only", 0, ArcherRoleLayout.FOOTER_Y, 11f, ROUTE_FOOTER, MsdfFonts.menuRegular, forceCustom = true)
        }
        g.pose().popMatrix()
    }

    private fun drawTile(g: GuiGraphicsExtractor, t: LeapMenu.Tile, i: Int, x0: Int, y0: Int, hot: Boolean, now: Long, semi: MsdfFont, route: ArcherCue?) {
        // Head not arrived yet: drawn from the roster, faded, not clickable (a press gets the amber cue).
        val a = if (!t.ready) PENDING_ALPHA else if (t.dead) 0.62f else 1f
        val x1 = x0 + TILE_W
        val y1 = y0 + TILE_H
        val clsColor = t.cls.color
        // Edge first, the fill over it: 1 px, or 1.5 px in the class color when hovered (red when dead).
        if (route != null) {
            edge(g, x0, y0, 1f, alpha(ROUTE_EDGE, a))
            roundRect(g, x0, y0, x1, y1, alpha(if (hot && t.ready) FILL_HOT else FILL, a), SoulRenderPipelines.ROUND_LARGE)
            routeInset(g, x0, y0, alpha(ROUTE_INSET, a))
        } else if (hot && t.ready) {
            edge(g, x0, y0, 1.5f, alpha(if (t.dead) DEAD else clsColor, a))
            roundRect(g, x0, y0, x1, y1, alpha(FILL_HOT, a), SoulRenderPipelines.ROUND_LARGE)
        } else {
            edge(g, x0, y0, 1f, alpha(EDGE, a))
            roundRect(g, x0, y0, x1, y1, alpha(FILL, a), SoulRenderPipelines.ROUND_LARGE)
        }

        // Face: the tab list's skin (loaded long before the menu), else the head's; dead = darkened.
        val fy = y0 + (TILE_H - FACE) / 2
        val fx = x0 + FACE_X
        g.fill(fx - 1, fy - 1, fx + FACE + 1, fy + FACE + 1, alpha(0x80000000.toInt(), a))
        val skin = t.skin ?: t.profile?.let { Minecraft.getInstance().playerSkinRenderCache().getOrDefault(it).playerSkin() }
        if (skin != null) {
            val tint = if (t.dead || !t.ready) alpha(0xFF8C8C8C.toInt(), a) else -1
            PlayerFaceExtractor.extractRenderState(g, skin, fx, fy, FACE, tint)
        } else {
            g.fill(fx, fy, fx + FACE, fy + FACE, alpha(0xFF2A2A2A.toInt(), a))
        }

        val tx = x0 + TEXT_X
        SoulText.draw(g, t.name, tx, y0 + NAME_TOP, NAME_SIZE, alpha(NAME, a), semi, forceCustom = true, shadow = false)
        if (t.dead) {
            tracked(g, "DEAD", tx, y0 + CLASS_TOP, CLASS_SIZE, alpha(DEAD, a), semi)
        } else {
            tracked(g, t.cls.displayName.uppercase(), tx, y0 + CLASS_TOP, CLASS_SIZE, alpha(clsColor, a), semi)
            if (cfg.dungeons.leap.showLocations || route != null) drawLocation(g, t.location, t.mapRoom, tx, y0 + LOC_TOP, semi)
        }

        if (cfg.dungeons.leap.numberKeys && cfg.dungeons.leap.keyHints) {
            val kx = x1 - KEY_BOX - 8
            val ky = y0 + 8
            roundRect(g, kx, ky, kx + KEY_BOX, ky + KEY_BOX, alpha(KEY_EDGE, a), SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, kx + 1, ky + 1, kx + KEY_BOX - 1, ky + KEY_BOX - 1, alpha(FILL, a), SoulRenderPipelines.ROUND_SMALL)
            SoulText.drawCenteredOn(g, "${i + 1}", kx + KEY_BOX / 2, ky + KEY_BOX / 2, KEY_SIZE, alpha(KEY_TEXT, a), semi, forceCustom = true, shadow = false)
        }
        if (route != null) {
            val badge = if (route.waiting) "WAIT" else "ROUTE"
            val badgeWidth = semi.widthF(badge, 8f) + badge.length - 1
            SoulText.drawWithCopies(g, badge, x1 - 10f - badgeWidth, y1 - 18f, 8f, alpha(if (route.waiting) ROUTE_WAIT else ROUTE_TEXT, a), semi, 1f, emptyList())
        }

        // A press that sent nothing: red over a dead teammate, amber while their head has not arrived; fades over FLASH_MS.
        if (i == flashPlace) {
            val age = now - flashAt
            if (age in 0 until FLASH_MS) {
                val f = 1f - age.toFloat() / FLASH_MS
                g.nextStratum()
                edge(g, x0, y0, 1.5f, alpha(if (flashWait) WAIT_EDGE else FLASH_EDGE, f))
                roundRect(g, x0, y0, x1, y1, alpha(if (flashWait) WAIT_FILL else FLASH_FILL, f), SoulRenderPipelines.ROUND_LARGE)
            }
        }
    }

    private fun routeInset(g: GuiGraphicsExtractor, x: Int, y: Int, color: Int) {
        val left = x + 0.5f
        val right = x + TILE_W - 0.5f
        val top = y + 0.5f
        val bottom = y + TILE_H - 0.5f
        val r = 9.5f
        val shape = Shapes(1f / SoulUi.scale)
        shape.line(left + r, top, right - r, top, 1f, color)
        shape.line(right, top + r, right, bottom - r, 1f, color)
        shape.line(left + r, bottom, right - r, bottom, 1f, color)
        shape.line(left, top + r, left, bottom - r, 1f, color)
        shape.arc(right - r, top + r, r, 1f, 0.0, PI / 2, color)
        shape.arc(right - r, bottom - r, r, 1f, PI / 2, PI / 2, color)
        shape.arc(left + r, bottom - r, r, 1f, PI, PI / 2, color)
        shape.arc(left + r, top + r, r, 1f, 3 * PI / 2, PI / 2, color)
        shape.submit(g)
    }

    /** A tile-sized round rect grown by [w] Soul px on every side (fractional through the pose), drawn under the fill as its edge. */
    private fun edge(g: GuiGraphicsExtractor, x0: Int, y0: Int, w: Float, color: Int) {
        g.pose().pushMatrix()
        g.pose().translate(x0 - w, y0 - w)
        g.pose().scale((TILE_W + 2 * w) / TILE_W, (TILE_H + 2 * w) / TILE_H)
        roundRect(g, 0, 0, TILE_W, TILE_H, color, SoulRenderPipelines.ROUND_LARGE)
        g.pose().popMatrix()
    }

    /** Upper-case label with 0.08 em tracking, left-aligned at [x] with its capitals from [y]. */
    private fun tracked(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, size: Float, color: Int, font: MsdfFont) {
        val tracking = size * 0.08f * 1.2723f
        var pen = x.toFloat()
        for (ch in text) {
            val s = ch.toString()
            g.pose().pushMatrix()
            g.pose().translate(pen - x, 0f)
            SoulText.draw(g, s, x, y, size, color, font, forceCustom = true, shadow = false)
            g.pose().popMatrix()
            pen += font.widthF(s, size) + tracking
        }
    }

    /** Icon + label + age (SPEC §4). */
    private fun drawLocation(g: GuiGraphicsExtractor, loc: TeammateLocations.Location, room: Boolean, x: Int, y: Int, font: MsdfFont) {
        if (loc.source == TeammateLocations.Source.NONE) {
            SoulText.draw(g, "?", x, y, LOC_SIZE, UNKNOWN, font, forceCustom = true, shadow = false)
            return
        }
        val a = if (loc.stale) 0.55f else 1f
        val em = LOC_SIZE * 1.2723f
        val icon = (em * 0.9f).roundToInt()
        val cap = (LOC_SIZE * 7f / 8f).roundToInt()
        val iy = y + cap / 2 - icon / 2
        val (name, tint) = if (loc.source == TeammateLocations.Source.SEEN) (if (room) "loc_map" else "loc_seen") to SEEN
        else if (loc.source == TeammateLocations.Source.LAST_SEEN) "loc_last" to MUTED
        else "loc_chat" to MUTED
        drawIcon(g, name, x, iy, icon, alpha(tint, a))
        var pen = x + icon + (em * 0.32f).roundToInt()
        SoulText.draw(g, loc.label, pen, y, LOC_SIZE, alpha(NAME, a), font, forceCustom = true, shadow = false)
        if (loc.source != TeammateLocations.Source.SEEN) {
            pen += SoulText.width(loc.label, LOC_SIZE, font, forceCustom = true)
            val age = " · ${ageText(loc.ageMs)}"
            SoulText.draw(g, age, pen, y, LOC_SIZE, alpha(AGE, a), MsdfFonts.menuRegular, forceCustom = true, shadow = false)
        }
    }
}
