package com.soulreturns.features.combo

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.AddressMode
import com.mojang.blaze3d.textures.FilterMode
import com.soulreturns.config.gui.render.TexturedQuadRenderState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f

/**
 * The Magic-Find sparkle (✦) drawn for the combo overlay — the star isn't in the MSDF atlas charset
 * (nor in the text fonts), so it's a white texture blitted and tinted per [ComboConfig]. Mirrors the
 * dungeon map's `map_arrow` texture path.
 */
object StarIcon {
    private val TEX = Identifier.fromNamespaceAndPath("soul", "textures/gui/combo_star.png")

    private fun setup(): TextureSetup? =
        try {
            val view = Minecraft.getInstance().textureManager.getTexture(TEX).textureView
            val sampler =
                RenderSystem.getSamplerCache().getSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR, FilterMode.LINEAR, false,
                )
            TextureSetup.singleTexture(view, sampler)
        } catch (t: Throwable) {
            null
        }

    /** Draw the star with top-left at ([x], [y]), [size]×[size] px, tinted [color] (ARGB). */
    fun draw(g: GuiGraphicsExtractor, x: Float, y: Float, size: Float, color: Int) {
        val s = setup() ?: return
        val pose = Matrix3x2f(g.pose())
        val bounds =
            ScreenRectangle(x.toInt(), y.toInt(), size.toInt() + 1, size.toInt() + 1)
                .transformMaxBounds(pose)
        g.guiRenderState.addGuiElement(
            TexturedQuadRenderState(
                RenderPipelines.GUI_TEXTURED, s, pose,
                x, y, x + size, y + size, 0f, 0f, 1f, 1f, color, bounds,
            ),
        )
    }
}
