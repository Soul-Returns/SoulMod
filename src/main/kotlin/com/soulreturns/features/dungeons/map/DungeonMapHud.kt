package com.soulreturns.features.dungeons.map

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.config.gui.render.TexturedQuadRenderState
import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonPlayer
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Soul's dungeon map HUD. **Fixed outer size** (a [BOX]×[BOX] card, times `mapScale`) — the room
 * grid scales to fit, so the footprint is stable across F1–F7/M1–M7 (item: fixed map size). Rooms
 * are colored by type + checkmark, named + secret-counted once identified, with player markers that
 * are either arrows or rotating player heads (config), smoothly lerped like devonian's map.
 */
object DungeonMapHud : MovableHud("dungeon_map", "Dungeon Map", "dungeons") {
    private const val BOX = 128
    private const val PAD = 6
    private const val GAP_RATIO = 0.22f

    private val ARROW_TEX = Identifier.fromNamespaceAndPath("soul", "textures/gui/map_arrow.png")

    // Room/door/marker colors are all read live from cfg.dungeons.colors (see roomColor/doorColor/
    // classColor) so the config color pickers apply immediately.

    // Per-frame layout, set in extract() from the floor + fixed box.
    private var cell = 18
    private var gap = 4
    private var originX = PAD
    private var originY = PAD
    private val unitPx: Double get() = (cell + gap) / 2.0

    /**
     * A renderable snapshot of a map: the room/door grid, the (alive) player markers, the floor
     * (for layout) and who "self" is. The live HUD builds one from [DungeonGrid]/[DungeonRunState];
     * the config live-preview builds a synthetic one — both go through [render].
     */
    class MapScene(
        val rooms: Array<DungeonRoom?>,
        val doors: Array<DungeonDoor?>,
        val players: List<DungeonPlayer>,
        val floor: DungeonFloor,
        val selfName: String?,
        val preview: Boolean = false,
    )

    /** The map's outer footprint at scale 1 (before [DungeonsConfig.mapScale]). */
    const val SIZE = BOX

    // Show whenever in a Catacombs instance (incl. before the run starts, to see the entrance).
    // "Hide in boss room" uses the same test the scanner does: the boss arena lies outside the
    // -200..-10 room grid.
    override fun isEnabled(): Boolean {
        if (!cfg.dungeons.mapEnabled || !LocationApi.inDungeon) return false
        if (!cfg.dungeons.mapHideInBoss) return true
        val player = net.minecraft.client.Minecraft.getInstance().player ?: return true
        return ComponentPos.fromWorld(player.x, player.z).isValid
    }

    override fun configuredOn(): Boolean = cfg.dungeons.mapEnabled

    override fun readTransform(): HudTransform = cfg.dungeons.mapHud

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(dungeons = dungeons.copy(mapHud = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().dungeons.mapHud

    override fun contentSize(preview: Boolean): Pair<Int, Int> = SIZE to SIZE

    /** The map owns its font + background/colors in its own settings screen — no Move-GUI font override. */
    override fun supportsFontOverride(): Boolean = false

    /** Map labels render without a drop shadow (own text path) — no Move-GUI text-shadow override. */
    override fun supportsTextShadow(): Boolean = false

    override fun settingsScreen(parent: Screen?): Screen = DungeonMapConfigScreen(parent)

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val scene =
            if (preview) {
                DungeonMapPreview.build(Minecraft.getInstance().player?.gameProfile?.name)
            } else {
                liveDrawnAt = System.currentTimeMillis()
                MapScene(
                    DungeonGrid.rooms,
                    DungeonGrid.doors,
                    DungeonRunState.players.filterNotNull().filter { !it.isDead },
                    DungeonRunState.floor,
                    Minecraft.getInstance().player?.gameProfile?.name,
                )
            }
        render(g, scene)
    }

    /** A teammate the leap menu points at: a pulsing ring in [color] around their marker and their name. */
    class Highlight(val name: String, val color: Int)

    /** Set by the leap menu every frame while a tile is hovered (`LeapScreen`), null otherwise. Live HUD only. */
    @Volatile
    var leapHighlight: Highlight? = null

    private var liveDrawnAt = 0L

    /**
     * Where the live map was drawn this frame, border included, in Soul px (x0, y0, x1, y1); null when it is not
     * on screen. The HUD draws before any screen, so a screen asking during its own frame gets this frame's box.
     */
    fun liveRect(): FloatArray? {
        if (System.currentTimeMillis() - liveDrawnAt > 100 || !isEnabled()) return null
        val s = readTransform().scale
        return floatArrayOf(drawnX - BORDER * s, drawnY - BORDER * s, drawnX + (BOX + BORDER) * s, drawnY + (BOX + BORDER) * s)
    }

    /** Render [scene] at ([x],[y]) with [scale] — used by the config screen's live preview. */
    fun renderPreview(g: GuiGraphicsExtractor, scene: MapScene, x: Float, y: Float, scale: Float) {
        g.pose().pushMatrix()
        g.pose().translate(x, y)
        g.pose().scale(scale, scale)
        render(g, scene)
        g.pose().popMatrix()
    }

    /** Border outline thickness, in composable px. */
    private const val BORDER = 2

    private fun render(g: GuiGraphicsExtractor, scene: MapScene) {
        val conf = cfg.dungeons
        computeLayout(scene.floor)
        val rounded = conf.mapRoundedCorners
        val bg = conf.mapBackgroundColor.effective()
        val bd = conf.mapBorderColor.effective()
        if (conf.mapBorder && conf.mapBackground) {
            // Frame + fill: outer border rect, background inset on top → a clean border ring.
            panelRect(g, -BORDER, -BORDER, BOX + BORDER, BOX + BORDER, bd, rounded)
            panelRect(g, 0, 0, BOX, BOX, bg, rounded)
        } else if (conf.mapBorder) {
            // Border only: draw a hollow frame (four margin strips) so the interior stays transparent.
            g.fill(-BORDER, -BORDER, BOX + BORDER, 0, bd)
            g.fill(-BORDER, BOX, BOX + BORDER, BOX + BORDER, bd)
            g.fill(-BORDER, 0, 0, BOX, bd)
            g.fill(BOX, 0, BOX + BORDER, BOX, bd)
        } else if (conf.mapBackground) {
            panelRect(g, 0, 0, BOX, BOX, bg, rounded)
        }
        drawRooms(g, scene)
        drawDoors(g, scene)
        // Layering (bottom → top): markers that stay below text, then names, then secret counts on
        // top of names, then markers the config puts above text.
        drawPlayers(g, scene, aboveText = false)
        drawRoomNames(g, scene)
        drawRoomSecretsPass(g, scene)
        drawPlayers(g, scene, aboveText = true)
        drawLeapLabel(g, scene)
    }

    /** A background/border rect: rounded (SDF) or square ([GuiGraphicsExtractor.fill]) per the toggle. */
    private fun panelRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, rounded: Boolean) {
        if (rounded) roundRect(g, x0, y0, x1, y1, color, SoulRenderPipelines.ROUND_MEDIUM) else g.fill(x0, y0, x1, y1, color)
    }

    /** Fit the room grid to the fixed box: uniform square cells sized by the larger axis, centered. */
    private fun computeLayout(floor: DungeonFloor) {
        val rw = if (floor == DungeonFloor.NONE) 6 else floor.roomsW
        val rh = if (floor == DungeonFloor.NONE) 6 else floor.roomsH
        val maxDim = max(max(rw, rh), 1)
        val inner = BOX - 2 * PAD
        val cf = inner / (maxDim + (maxDim - 1) * GAP_RATIO)
        cell = cf.toInt().coerceAtLeast(6)
        gap = (cell * GAP_RATIO).roundToInt().coerceAtLeast(1)
        val gridW = rw * cell + (rw - 1) * gap
        val gridH = rh * cell + (rh - 1) * gap
        originX = PAD + (inner - gridW) / 2
        originY = PAD + (inner - gridH) / 2
    }

    // ---------------------------------------------------------------- rooms

    private fun drawRooms(g: GuiGraphicsExtractor, scene: MapScene) {
        val visited = HashSet<DungeonRoom>()

        for (room in scene.rooms) {
            if (room == null || !visited.add(room)) continue
            val color = roomColor(room)

            for (comp in room.comps) {
                val x = cellX(comp)
                val y = cellY(comp)
                g.fill(x, y, x + cell, y + cell, color)
            }
            // Joins: fill the gap between adjacent cells of the same room.
            for (a in room.comps) {
                for (b in room.comps) {
                    if (a.x == b.x && a.z == b.z + 2) {
                        g.fill(cellX(a), cellY(b) + cell, cellX(a) + cell, cellY(a), color)
                    }
                    if (a.z == b.z && a.x == b.x + 2) {
                        g.fill(cellX(b) + cell, cellY(a), cellX(a), cellY(a) + cell, color)
                    }
                    if (a.x == b.x + 2 && a.z == b.z + 2 && isSquare(room)) {
                        g.fill(cellX(b) + cell, cellY(b) + cell, cellX(a), cellY(a), color)
                    }
                }
            }

        }
    }

    /** Secret counts / checkmark dots, on top of names (a room's most actionable number). */
    private fun drawRoomSecretsPass(g: GuiGraphicsExtractor, scene: MapScene) {
        if (cfg.dungeons.mapCenterPair) return // secrets are drawn within the centered pair instead
        val visited = HashSet<DungeonRoom>()
        for (room in scene.rooms) {
            if (room == null || !visited.add(room)) continue
            drawRoomSecrets(g, room)
        }
    }

    // Base size clamped for legibility, then scaled by the user's manual text-scale multiplier. The
    // multiplier is inert while auto-scale is on (names refit to the room anyway; its slider is
    // disabled in config), so map text then renders exactly as before.
    private fun textSize(ratio: Float): Float {
        val scale = if (cfg.dungeons.mapAutoScaleNames) 1f else cfg.dungeons.mapTextScale
        return (cell * ratio).coerceIn(4.5f, 7f) * scale
    }

    /** Tight line-to-line advance for stacked labels (the font's lineHeight metric is too loose). */
    private fun lineAdvance(size: Float): Float = size * 1.05f

    /** The map lays out in legacy em units; SoulText takes vanilla-relative sizes. */
    private fun em(size: Float): Float = size * MsdfFont.LEGACY_EM

    /** The map's font choice (0 = follow the global setting; else 1..4 → Baloo2/Nunito/Inter/Minecraft). */
    private fun mapChoice(): Int {
        val c = cfg.dungeons.mapFontChoice
        return if (c == 0) cfg.general.fontChoice else c - 1
    }

    /** The MSDF weight for the map's font (used for MSDF choices; Minecraft renders via the MC font). */
    private fun mapFont(): MsdfFont = MsdfFonts.semiboldFor(mapChoice())

    /** Secret count (or checkmark dot) for a room — drawn in the rooms pass. */
    private fun drawRoomSecrets(g: GuiGraphicsExtractor, room: DungeonRoom) {
        val secretLabel = secretLabelFor(room)
        if (secretLabel == null) {
            val hasName = cfg.dungeons.mapShowRoomNames && room.roomName != null
            if (!hasName) drawCheckmarkDot(g, room)
            return
        }
        val band = labelBand(room)
        val cx = (band[0] + band[2]) / 2
        val bandH = band[3] - band[1]
        val frac = if (room.shape == RoomShape.L) 0.28f else labelFrac(cfg.dungeons.mapSecretsPosition)
        val size = textSize(0.36f)
        val cy = (band[1] + frac * bandH).roundToInt()
        SoulText.drawCenteredOn(g, secretLabel, cx, cy, em(size), stateColor(room), mapFont(), fontChoice = mapChoice(), tight = true)
    }

    /** The label pass, drawn last so labels render on top of rooms, doors and player markers. */
    private fun drawRoomNames(g: GuiGraphicsExtractor, scene: MapScene) {
        val paired = cfg.dungeons.mapCenterPair
        val visited = HashSet<DungeonRoom>()
        for (room in scene.rooms) {
            if (room == null || !visited.add(room)) continue
            if (paired) drawRoomPair(g, room) else drawRoomName(g, room)
        }
    }

    /** A single room name — wrapped + (optionally) auto-scaled to fit its label band. */
    private fun drawRoomName(g: GuiGraphicsExtractor, room: DungeonRoom) {
        val conf = cfg.dungeons
        val name = (if (conf.mapShowRoomNames) room.roomName else null) ?: return
        val band = labelBand(room)
        val cx = (band[0] + band[2]) / 2
        val bandH = band[3] - band[1]
        val avail = (band[2] - band[0] - 4).coerceAtLeast(8)
        val hasSecret = secretLabelFor(room) != null
        // Reserve the secret-count line's height so the name doesn't overlap it.
        val availH = (bandH - (if (hasSecret) textSize(0.36f) + 3f else 0f) - 2f).coerceAtLeast(6f)

        val (size, lines) = fitName(name, avail, availH)
        // Integer step so every inter-line gap is identical (float steps rounded per line drift).
        val step = lineAdvance(size).roundToInt().coerceAtLeast(1)
        val frac = when {
            room.shape == RoomShape.L -> if (hasSecret) 0.68f else 0.5f
            !hasSecret && conf.mapCenterNamelessRooms -> 0.5f
            else -> labelFrac(conf.mapNamePosition)
        }
        val anchorY = band[1] + frac * bandH
        val firstCy = (anchorY - (lines.size - 1) * step / 2f).roundToInt()
        SoulText.drawBlockCentered(g, lines, cx, firstCy, step, em(size), stateColor(room), mapFont(), fontChoice = mapChoice(), tight = true)
    }

    /** Name + secret count as one stacked block, centered in the room (the "center pair" option). */
    private fun drawRoomPair(g: GuiGraphicsExtractor, room: DungeonRoom) {
        val conf = cfg.dungeons
        val secretLabel = secretLabelFor(room)
        val name = if (conf.mapShowRoomNames) room.roomName else null
        if (secretLabel == null && name == null) {
            drawCheckmarkDot(g, room)
            return
        }
        val band = labelBand(room)
        val cx = (band[0] + band[2]) / 2
        val bandH = band[3] - band[1]
        val avail = (band[2] - band[0] - 4).coerceAtLeast(8)
        val color = stateColor(room)

        val secretSize = textSize(0.36f)
        val secretStep = if (secretLabel != null) lineAdvance(secretSize).roundToInt() else 0
        val availH = (bandH - secretStep - 2f).coerceAtLeast(6f)
        val (nameSize, lines) = if (name != null) fitName(name, avail, availH) else 0f to emptyList()
        val nameStep = if (name != null) lineAdvance(nameSize).roundToInt().coerceAtLeast(1) else 0

        // Integer steps → identical gaps across the stack.
        val total = secretStep + lines.size * nameStep
        var y = (band[1] + 0.5f * bandH) - total / 2f // block top, centered in the band

        fun drawSecret() {
            if (secretLabel == null) return
            SoulText.drawCenteredOn(g, secretLabel, cx, (y + secretStep / 2f).roundToInt(), em(secretSize), color, mapFont(), fontChoice = mapChoice(), tight = true)
            y += secretStep
        }
        fun drawName() {
            if (lines.isEmpty()) return
            val firstCy = (y + nameStep / 2f).roundToInt()
            SoulText.drawBlockCentered(g, lines, cx, firstCy, nameStep, em(nameSize), color, mapFont(), fontChoice = mapChoice(), tight = true)
            y += lines.size * nameStep
        }
        if (conf.mapSecretsAboveName) {
            drawSecret(); drawName()
        } else {
            drawName(); drawSecret()
        }
    }

    /** Wrap + (optionally) auto-scale [name] to [avail] width and [availH] height → (size, lines). */
    private fun fitName(name: String, avail: Int, availH: Float): Pair<Float, List<String>> {
        var size = textSize(0.30f)
        if (cfg.dungeons.mapAutoScaleNames) {
            while (size > 3f) {
                val lines = wrapToWidth(name, avail, size)
                val fits = lines.maxOf { SoulText.width(it, em(size), mapFont(), fontChoice = mapChoice()) } <= avail &&
                    lines.size * lineAdvance(size) <= availH
                if (fits) return size to lines
                size -= 0.5f
            }
            return size to wrapToWidth(name, avail, size)
        }
        val widest = name.split(' ').maxOf { SoulText.width(it, em(size), mapFont(), fontChoice = mapChoice()) }
        if (widest > avail) size = (size * avail / widest).coerceAtLeast(3.2f)
        return size to wrapToWidth(name, avail, size)
    }

    private fun secretLabelFor(room: DungeonRoom): String? {
        val conf = cfg.dungeons
        if (!conf.mapShowSecrets || !shouldShowSecrets(room) || room.secretsTotal <= 0) return null
        return if (room.checkmark == Checkmark.GREEN) {
            "${room.secretsTotal}/${room.secretsTotal}"
        } else {
            "${room.secretsFound.coerceAtLeast(0)}/${room.secretsTotal}"
        }
    }

    /**
     * The rectangle a room's labels sit in: the whole room for rectangular shapes, or the wider 2×1
     * arm (the corner's row) for an L — so an L's name/secrets are centered in its solid part rather
     * than floating over the empty corner. Returns [x0, y0, x1, y1].
     */
    private fun labelBand(room: DungeonRoom): IntArray {
        if (room.shape == RoomShape.L) {
            val row = room.comps.groupBy { it.z }.values.maxByOrNull { it.size }!!
            val x0 = row.minOf { cellX(it) }
            val x1 = row.maxOf { cellX(it) + cell }
            val y0 = cellY(row.first())
            return intArrayOf(x0, y0, x1, y0 + cell)
        }
        val x0 = room.comps.minOf { cellX(it) }
        val x1 = room.comps.maxOf { cellX(it) + cell }
        val y0 = room.comps.minOf { cellY(it) }
        val y1 = room.comps.maxOf { cellY(it) + cell }
        return intArrayOf(x0, y0, x1, y1)
    }

    /** Vertical placement fraction within a room: 0 = top, 1 = center, 2 = bottom. */
    private fun labelFrac(pos: Int): Float = when (pos) {
        0 -> 0.26f
        2 -> 0.74f
        else -> 0.5f
    }

    /** Greedy word-wrap of [text] to [maxW] px at [size] (a lone over-wide word gets its own line). */
    private fun wrapToWidth(text: String, maxW: Int, size: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = ""
        for (word in text.split(' ')) {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (cur.isEmpty() || SoulText.width(candidate, em(size), mapFont(), fontChoice = mapChoice()) <= maxW) {
                cur = candidate
            } else {
                lines.add(cur)
                cur = word
            }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    private fun isSquare(room: DungeonRoom): Boolean = room.shape == RoomShape.S2X2

    private fun shouldShowSecrets(room: DungeonRoom): Boolean =
        room.type == RoomType.NORMAL || room.type == RoomType.RARE || room.type == RoomType.TRAP

    private fun roomColor(room: DungeonRoom): Int {
        val c = cfg.dungeons.colors
        if (!room.explored) return c.unopened.effective()
        val type = room.type
        return if (type == RoomType.ENTRANCE) {
            c.entrance.effective()
        } else if (type == RoomType.PUZZLE) {
            c.puzzle.effective()
        } else if (type == RoomType.TRAP) {
            c.trap.effective()
        } else if (type == RoomType.MINIBOSS) {
            c.miniboss.effective()
        } else if (type == RoomType.FAIRY) {
            c.fairy.effective()
        } else if (type == RoomType.BLOOD) {
            c.blood.effective()
        } else if (type == RoomType.RARE) {
            c.rare.effective()
        } else if (type == RoomType.UNKNOWN) {
            c.unopened.effective()
        } else {
            c.normal.effective()
        }
    }

    /** Marker/border color for a dungeon class, read live from config. */
    private fun classColor(dungeonClass: DungeonClass): Int {
        val c = cfg.dungeons.colors
        return if (dungeonClass == DungeonClass.ARCHER) {
            c.classArcher.effective()
        } else if (dungeonClass == DungeonClass.BERSERK) {
            c.classBerserk.effective()
        } else if (dungeonClass == DungeonClass.MAGE) {
            c.classMage.effective()
        } else if (dungeonClass == DungeonClass.HEALER) {
            c.classHealer.effective()
        } else if (dungeonClass == DungeonClass.TANK) {
            c.classTank.effective()
        } else {
            DungeonClass.UNKNOWN.color
        }
    }

    /**
     * Text color conveying clear state: cleared rooms are bright (white/green), a failed room is
     * red, and an UNCLEARED (explored, no checkmark) room is a distinct medium gray — the whole
     * point is that gray reads clearly as "not done yet" vs. white "cleared".
     */
    private fun stateColor(room: DungeonRoom): Int = when {
        // Entrance always reads as cleared (it has no checkmark but the run always "clears" it);
        // white sits legibly on its green fill.
        room.type == RoomType.ENTRANCE -> 0xFFFFFFFF.toInt()
        // Fairy rooms are always cleared (no objective) → green (legible on the pink fill).
        room.type == RoomType.FAIRY -> 0xFF44E04A.toInt()
        room.checkmark == Checkmark.GREEN -> 0xFF44E04A.toInt()
        room.checkmark == Checkmark.FAILED -> 0xFFFF4444.toInt()
        room.checkmark == Checkmark.WHITE -> 0xFFFFFFFF.toInt()
        else -> 0xFF8B8B93.toInt() // explored but uncleared (or not-yet-explored) → gray
    }

    private fun roomCenter(room: DungeonRoom): Pair<Int, Int> {
        val minX = room.comps.minOf { cellX(it) }
        val maxX = room.comps.maxOf { cellX(it) + cell }
        val minY = room.comps.minOf { cellY(it) }
        val maxY = room.comps.maxOf { cellY(it) + cell }
        return (minX + maxX) / 2 to (minY + maxY) / 2
    }

    private fun drawCheckmarkDot(g: GuiGraphicsExtractor, room: DungeonRoom) {
        val check = room.checkmark
        val color = if (check == Checkmark.GREEN) {
            0xFF2BD62B.toInt()
        } else if (check == Checkmark.WHITE) {
            0xFFF5F5F5.toInt()
        } else if (check == Checkmark.FAILED) {
            0xFFFF3030.toInt()
        } else {
            return
        }
        val (cx, cy) = roomCenter(room)
        val r = (cell * 0.16f).toInt().coerceAtLeast(2)
        roundRect(g, cx - r, cy - r, cx + r, cy + r, color, SoulRenderPipelines.ROUND_PILL)
    }

    // ---------------------------------------------------------------- doors

    private fun drawDoors(g: GuiGraphicsExtractor, scene: MapScene) {
        val half = (cell * 0.22f).toInt().coerceAtLeast(2)
        for (door in scene.doors) {
            if (door == null) continue
            val comp = door.comp
            val color = doorColor(door) ?: continue

            val horizontalGap = (comp.x and 1) == 1
            if (horizontalGap) {
                val x = cellX(ComponentPos(comp.x - 1, comp.z)) + cell
                val cy = cellY(ComponentPos(comp.x - 1, comp.z)) + cell / 2
                g.fill(x, cy - half, x + gap, cy + half, color)
            } else {
                val y = cellY(ComponentPos(comp.x, comp.z - 1)) + cell
                val cx = cellX(ComponentPos(comp.x, comp.z - 1)) + cell / 2
                g.fill(cx - half, y, cx + half, y + gap, color)
            }
        }
    }

    private fun doorColor(door: DungeonDoor): Int? {
        val c = cfg.dungeons.colors
        val type = door.type
        return if (type == DoorType.WITHER) {
            if (door.opened) c.normal.effective() else c.doorWither.effective()
        } else if (type == DoorType.BLOOD) {
            c.doorBlood.effective()
        } else if (type == DoorType.ENTRANCE) {
            c.doorEntrance.effective()
        } else {
            c.normal.effective()
        }
    }

    // ---------------------------------------------------------------- players

    private fun drawPlayers(g: GuiGraphicsExtractor, scene: MapScene, aboveText: Boolean) {
        val conf = cfg.dungeons
        val selfName = scene.selfName
        // Names: either always (mapShowNames) or only while holding a leap item (overrides). The
        // leap check reads the live main hand, so it only applies to the live HUD, not the preview.
        val showNames = if (!scene.preview && conf.mapNamesOnlyWithLeap) holdingLeap() else conf.mapShowNames
        // Self last (drawn on top).
        for (player in scene.players.sortedBy { it.name == selfName }) {
            val isSelf = player.name == selfName
            // This pass only draws markers whose above-text setting matches (self/others configurable).
            val above = if (isSelf) conf.mapSelfAboveText else conf.mapOthersAboveText
            if (above != aboveText) continue
            val pos = player.lerpedPosition() ?: continue
            // Outside the room grid (boss arena) the position has no place on this map.
            if (pos.x < 0 || pos.z < 0 || pos.x >= GRID_UNITS || pos.z >= GRID_UNITS) continue
            val px = (originX + pos.x * unitPx).toFloat()
            val py = (originY + pos.z * unitPx).toFloat()
            val rot = pos.rot.toFloat()

            val wantHead = if (isSelf) conf.mapPlayerHeadSelf else conf.mapPlayerHeadsOthers
            val borderColor = if (!isSelf && conf.mapClassColorBorders) classColor(player.dungeonClass) else null

            g.pose().pushMatrix()
            g.pose().translate(px, py)

            val lit = !isSelf && isLeapTarget(scene, player)
            if (lit) drawLeapRing(g, leapHighlight?.color ?: classColor(player.dungeonClass))
            val markerColor = if (isSelf) conf.colors.markerSelf.effective() else classColor(player.dungeonClass)
            val drewHead = wantHead && drawHead(g, player, borderColor, rot, isSelf, scene.preview)
            if (!drewHead) drawArrow(g, rot, isSelf, markerColor)

            g.pose().popMatrix()

            if (showNames && !isSelf && !lit) {
                // Translate the pose (floats) rather than passing int coords, so the label moves
                // sub-pixel-smoothly instead of snapping to the logical grid (visible at high scale).
                g.pose().pushMatrix()
                g.pose().translate(px, py + (cell * 0.3f) + 2f)
                SoulText.drawCentered(g, player.name, 0, 0, em(textSize(0.32f)), 0xFFE8E8E8.toInt(), mapFont(), fontChoice = mapChoice())
                g.pose().popMatrix()
            }
        }
    }

    // ---------------------------------------------------------------- leap menu highlight

    /** One pulse of the leap highlight's halo. */
    private const val PULSE_MS = 900L

    private fun isLeapTarget(scene: MapScene, player: DungeonPlayer): Boolean {
        if (scene.preview) return false
        val h = leapHighlight ?: return false
        return player.name.equals(h.name, ignoreCase = true)
    }

    /** Radius of the ring around a highlighted marker: just outside the head marker's corners. */
    private fun leapRingRadius(): Float = (cell * 0.42f * cfg.dungeons.mapPlayerHeadScale).coerceIn(3f, 14f) * 1.45f + 1.5f

    /** Under the marker (pose at its centre): a halo pulsing outward and a solid disc in [color] that rims the marker. */
    private fun drawLeapRing(g: GuiGraphicsExtractor, color: Int) {
        val r = leapRingRadius()
        val t = (System.currentTimeMillis() % PULSE_MS) / PULSE_MS.toFloat()
        disc(g, r + 7f * t, withAlpha(color, 0.6f * (1f - t)))
        disc(g, r + 1.5f, 0xE0000000.toInt())
        disc(g, r, color)
    }

    /** The highlighted teammate's name above their marker, over everything else on the map. */
    private fun drawLeapLabel(g: GuiGraphicsExtractor, scene: MapScene) {
        val player = scene.players.firstOrNull { it.name != scene.selfName && isLeapTarget(scene, it) } ?: return
        val pos = player.lerpedPosition() ?: return
        if (pos.x < 0 || pos.z < 0 || pos.x >= GRID_UNITS || pos.z >= GRID_UNITS) return
        val px = (originX + pos.x * unitPx).toFloat()
        val py = (originY + pos.z * unitPx).toFloat()
        val size = em(textSize(0.36f))
        val font = mapFont()
        val w = SoulText.width(player.name, size, font, fontChoice = mapChoice())
        val h = size.roundToInt() + 2
        val top = (py - leapRingRadius() - 4f - h).coerceAtLeast(1f)
        g.pose().pushMatrix()
        g.pose().translate(px, top)
        roundRect(g, -w / 2 - 3, -1, w / 2 + 3, h, 0xD0101114.toInt(), SoulRenderPipelines.ROUND_SMALL)
        SoulText.drawCentered(g, player.name, 0, 1, size, leapHighlight?.color ?: 0xFFE8E8E8.toInt(), font, shadow = false, fontChoice = mapChoice())
        g.pose().popMatrix()
    }

    /** A filled circle of radius [r] at the pose origin (SDF pill on a square, scaled for sub-pixel radii). */
    private fun disc(g: GuiGraphicsExtractor, r: Float, color: Int) {
        if (r <= 0f || (color ushr 24) == 0) return
        g.pose().pushMatrix()
        g.pose().scale(r / 16f, r / 16f)
        roundRect(g, -16, -16, 16, 16, color, SoulRenderPipelines.ROUND_PILL)
        g.pose().popMatrix()
    }

    private fun withAlpha(c: Int, a: Float): Int = (((c ushr 24) * a).roundToInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    /** Room grid extent in [MapPlayerPos] units (6 rooms × 32 blocks / 16 blocks per unit). */
    private const val GRID_UNITS =
        (DungeonGeometry.ROOM_GRID * DungeonGeometry.COMBINED_SIZE / DungeonGeometry.HALF_COMBINED).toDouble()

    /** Navigation-arrow marker (a tinted texture) pointing in the facing direction (+x at rot 0). */
    private fun drawArrow(g: GuiGraphicsExtractor, rot: Float, isSelf: Boolean, color: Int) {
        val setup = arrowSetup() ?: return
        val selfScale = if (isSelf) cfg.dungeons.mapPlayerScaleSelf else 1f
        val r = (cell * (if (isSelf) 0.34f else 0.28f) * selfScale).coerceAtLeast(3f)
        g.pose().pushMatrix()
        g.pose().rotate(rot)
        // Black outline: the same arrow, slightly larger, in black behind the tinted one.
        val br = r * 1.25f
        texturedQuad(g, setup, -br, -br, br, br, 0f, 0f, 1f, 1f, 0xFF000000.toInt())
        texturedQuad(g, setup, -r, -r, r, r, 0f, 0f, 1f, 1f, color)
        g.pose().popMatrix()
    }

    /**
     * Rotating player-head marker (skin face + hat) with an optional **square** class-color border.
     * The head's "up" points in the facing direction (offset +90° from the arrow's +x convention).
     */
    private fun drawHead(g: GuiGraphicsExtractor, player: DungeonPlayer, borderColor: Int?, rot: Float, isSelf: Boolean, preview: Boolean): Boolean {
        // In the config preview there are no real skins loaded — use the default (Steve) texture so
        // head styling (scale, class border) is still previewable.
        val loc = (if (preview) DefaultPlayerSkin.getDefaultTexture() else skinLocation(player)) ?: return false
        val setup = skinSetup(loc) ?: return false
        val scale = if (isSelf) cfg.dungeons.mapPlayerScaleSelf else cfg.dungeons.mapPlayerHeadScale
        val hR = (cell * 0.42f * scale).coerceIn(3f, 14f)

        g.pose().pushMatrix()
        g.pose().rotate(rot + (Math.PI / 2).toFloat())

        if (borderColor != null) {
            // Square border (not rounded): a solid filled square behind the head shows as a frame.
            val b = (hR + (cell * 0.1f).coerceAtLeast(1f)).toInt()
            g.fill(-b, -b, b, b, borderColor)
        }
        // Face (skin 8,8..16,16) then hat overlay (40,8..48,16) — skin is 64px.
        texturedQuad(g, setup, -hR, -hR, hR, hR, 8f / 64, 8f / 64, 16f / 64, 16f / 64, 0xFFFFFFFF.toInt())
        texturedQuad(g, setup, -hR, -hR, hR, hR, 40f / 64, 8f / 64, 48f / 64, 16f / 64, 0xFFFFFFFF.toInt())
        g.pose().popMatrix()
        return true
    }

    private fun arrowSetup(): TextureSetup? = try {
        val view = Minecraft.getInstance().textureManager.getTexture(ARROW_TEX).textureView
        val sampler = RenderSystem.getSamplerCache().getSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
            FilterMode.LINEAR, FilterMode.LINEAR, false,
        )
        TextureSetup.singleTexture(view, sampler)
    } catch (t: Throwable) {
        null
    }

    /** True while the local player holds a Spirit Leap / Infinileap (for names-on-leap). */
    private fun holdingLeap(): Boolean {
        val stack = Minecraft.getInstance().player?.mainHandItem ?: return false
        if (stack.isEmpty) return false
        return stack.hoverName.string.contains("leap", ignoreCase = true)
    }

    private fun skinLocation(player: DungeonPlayer): Identifier? {
        val uuid = player.uuid ?: return null
        val info = Minecraft.getInstance().connection?.getPlayerInfo(uuid) ?: return null
        return try {
            info.skin.body().texturePath()
        } catch (t: Throwable) {
            null
        }
    }

    private fun skinSetup(loc: Identifier): TextureSetup? = try {
        val view = Minecraft.getInstance().textureManager.getTexture(loc).textureView
        val sampler = RenderSystem.getSamplerCache().getSampler(
            AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
            FilterMode.NEAREST, FilterMode.NEAREST, false,
        )
        TextureSetup.singleTexture(view, sampler)
    } catch (t: Throwable) {
        null
    }

    // ---------------------------------------------------------------- helpers

    private fun cellX(comp: ComponentPos): Int = originX + (comp.x / 2) * (cell + gap)

    private fun cellY(comp: ComponentPos): Int = originY + (comp.z / 2) * (cell + gap)

    private fun roundRect(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
        pipeline: RenderPipeline,
    ) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null),
        )
    }

    private fun texturedQuad(
        g: GuiGraphicsExtractor,
        setup: TextureSetup,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        color: Int,
    ) {
        val pose = Matrix3x2f(g.pose())
        // Non-null bounds (culling): AABB of the rotated quad.
        val bounds = ScreenRectangle(x0.toInt(), y0.toInt(), (x1 - x0).toInt() + 1, (y1 - y0).toInt() + 1)
            .transformMaxBounds(pose)
        g.guiRenderState.addGuiElement(
            TexturedQuadRenderState(
                RenderPipelines.GUI_TEXTURED, setup, pose,
                x0, y0, x1, y1, u0, v0, u1, v1, color, bounds,
            ),
        )
    }
}
