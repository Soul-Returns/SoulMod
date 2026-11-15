package com.soulreturns.features.dungeons.roles

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.DungeonFloor
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.HudElement
import com.soulreturns.ui.hud.SoulHud
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Read-only presentation of the current route snapshot, per mocks/archer-role/. */
object ArcherRoleDisplay : HudElement("archer_role") {
    private const val EYEBROW = 0xFFACAFB5.toInt()
    private const val LEAP = 0xFFDDF4E8.toInt()
    private const val WAIT = 0xFFDFCD9C.toInt()
    private const val DETAIL = 0xFFBBC2C7.toInt()
    private const val WORLD_EDGE = 0xFF87E8BB.toInt()
    private const val WORLD_FILL = 0x1587E8BB
    private const val WORLD_LABEL = 0xFFA6F0CE.toInt()
    private val shadow = listOf(SoulText.Copy(0f, 2f, 0xFF000000.toInt(), true))
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        SoulHud.add(this)
        LevelRenderEvents.BEFORE_GIZMOS.register { renderObjective() }
    }

    fun currentCue(): ArcherCue? {
        if (!cfg.dungeons.roles.enabled || !DungeonRunState.inRun ||
            (DungeonRunState.floor != DungeonFloor.F7 && DungeonRunState.floor != DungeonFloor.M7)) return null
        if (DungeonRunState.self?.dungeonClass != DungeonClass.ARCHER) return null
        return ArcherRoles.cue
    }

    override fun isEnabled(): Boolean {
        if (Minecraft.getInstance().gui.screen() != null) return false
        val cue = currentCue() ?: return false
        return ArcherRoleLayout.showsCue(cue, cfg.dungeons.roles.showCue)
    }

    override fun extract(g: GuiGraphicsExtractor) {
        val cue = currentCue() ?: return
        if (!ArcherRoleLayout.showsCue(cue, cfg.dungeons.roles.showCue)) return
        val factor = SoulUi.factor()
        val cx = g.guiWidth() / factor / 2f
        var y = ArcherRoleLayout.cueY(g.guiHeight() / factor)
        val regular = MsdfFonts.regularFor(MsdfFonts.INTER)
        val semi = MsdfFonts.semiboldFor(MsdfFonts.INTER)
        line(g, "ARCHER · ${cue.phase}", cx, y, ArcherRoleLayout.EYEBROW_SIZE, EYEBROW, regular, ArcherRoleLayout.EYEBROW_TRACKING)
        y += ArcherRoleLayout.EYEBROW_SIZE * ArcherRoleLayout.LINE_HEIGHT + ArcherRoleLayout.GAP
        line(g, cue.text, cx, y, ArcherRoleLayout.MAIN_SIZE, if (cue.waiting) WAIT else LEAP, semi)
        y += ArcherRoleLayout.MAIN_SIZE * ArcherRoleLayout.LINE_HEIGHT + ArcherRoleLayout.GAP
        line(g, cue.detail, cx, y, ArcherRoleLayout.DETAIL_SIZE, DETAIL, regular)
    }

    private fun line(g: GuiGraphicsExtractor, text: String, cx: Float, y: Float, size: Float, color: Int, font: MsdfFont, tracking: Float = 0f) {
        val width = font.widthF(text, size) + maxOf(0, text.length - 1) * tracking
        SoulText.drawWithCopies(g, text, cx - width / 2f, y, size, color, font, tracking, shadow)
    }

    private fun renderObjective() {
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() != null || !cfg.dungeons.roles.markObjective) return
        val cue = currentCue() ?: return
        if (cue.waiting) return
        val objective = cue.objective ?: return
        val level = mc.level ?: return
        val pos = BlockPos.containing(objective.x, objective.y, objective.z)
        if (!level.hasChunkAt(pos)) return
        val shape = level.getBlockState(pos).getShape(level, pos)
        // Crystals and gate centres can occupy an empty block; the recorded coordinate is still the target.
        val box = if (shape.isEmpty) {
            AABB(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble(), pos.x + 1.0, pos.y + 1.0, pos.z + 1.0)
        } else shape.bounds().move(pos)
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            Gizmos.cuboid(box.inflate(0.03), GizmoStyle.strokeAndFill(WORLD_EDGE, 2f, WORLD_FILL)).setAlwaysOnTop()
            Gizmos.billboardText(
                objective.label,
                Vec3((box.minX + box.maxX) / 2, box.maxY + 0.45, (box.minZ + box.maxZ) / 2),
                TextGizmo.Style.forColorAndCentered(WORLD_LABEL).withScale(0.6f),
            ).setAlwaysOnTop()
        }
    }
}
