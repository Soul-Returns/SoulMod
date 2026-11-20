package com.soulreturns.ui

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.render.GuiItemAtlas
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Items drawn under a scaled pose, rasterized at the pixel size they are shown at.
 *
 * Vanilla renders every GUI item into one atlas at 16 × GUI scale pixels and blits that cell with NEAREST under the
 * item's pose, so an item drawn at any other size (every Soul screen: `SoulUi.factor()`, plus kit sizes 12/14/18…)
 * is resampled and looks soft or uneven. `GuiRendererMixin` routes such items here: one [GuiItemAtlas] per physical
 * slot size, the item drawn at exactly that size and blitted 1:1 at a whole physical pixel. Render thread only.
 */
object CrispItems {
    /** Atlases are closed once unused for this many frames. */
    private const val IDLE_FRAMES = 120
    private const val MAX_SIZE = 256
    /** An item animated through many sizes (another mod's zoom) must not get a texture per frame; the rest stay vanilla. */
    private const val MAX_SIZES = 12

    private class Sized(var atlas: GuiItemAtlas?, val items: MutableSet<Any> = HashSet(), var idle: Int = 0)

    private val bySize = HashMap<Int, Sized>()
    private var guiScale = 1

    /** Called before vanilla prepares its items: collect the scaled ones per size and make room for them. */
    @JvmStatic
    fun beginFrame(state: GuiRenderState, dispatcher: FeatureRenderDispatcher) {
        guiScale = Minecraft.getInstance().gameRenderer.gameRenderState().windowRenderState.guiScale
        for (s in bySize.values) s.items.clear()
        state.forEachItem { item ->
            val size = sizeOf(item) ?: return@forEachItem
            val sized = bySize[size] ?: if (bySize.size < MAX_SIZES) Sized(null).also { bySize[size] = it } else return@forEachItem
            sized.items += item.itemStackRenderState().modelIdentity
        }
        for ((size, s) in bySize) {
            if (s.items.isEmpty()) continue
            s.idle = 0
            val atlas = s.atlas
            if (atlas != null && atlas.tryPrepareFor(s.items)) continue
            val texture = GuiItemAtlas.computeTextureSizeFor(size, s.items.size)
            // At the largest texture already: keep it, items without room fall back to vanilla's blit.
            if (atlas != null && atlas.textureSize() == texture) continue
            atlas?.close()
            s.atlas = GuiItemAtlas(dispatcher, texture, size)
        }
    }

    /** Replaces vanilla's blit for [item]; false = vanilla keeps it (unscaled, rotated, oversized or no room). */
    @JvmStatic
    fun submit(state: GuiRenderState, item: GuiItemRenderState): Boolean {
        val size = sizeOf(item) ?: return false
        val atlas = bySize[size]?.atlas ?: return false
        val slot = atlas.getOrUpdate(item.itemStackRenderState()) ?: return false
        val p = item.pose()
        val gs = guiScale.toFloat()
        val x = (Math.round((p.m00() * item.x() + p.m20()) * gs) / gs)
        val y = (Math.round((p.m11() * item.y() + p.m21()) * gs) / gs)
        val pose = Matrix3x2f().translate(x, y).scale(size / gs)
        state.addBlitToCurrentLayer(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(slot.textureView(), RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST)),
                pose, 0, 0, 1, 1,
                slot.u0(), slot.u1(), slot.v0(), slot.v1(),
                -1, item.scissorArea(), null,
            ),
        )
        return true
    }

    @JvmStatic
    fun endFrame() {
        val it = bySize.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.atlas?.endFrame()
            if (++s.idle > IDLE_FRAMES) {
                s.atlas?.close()
                it.remove()
            }
        }
    }

    @JvmStatic
    fun close() {
        for (s in bySize.values) s.atlas?.close()
        bySize.clear()
    }

    /** The item's physical slot size when its pose scales it uniformly to anything but vanilla's 16 × GUI scale. */
    private fun sizeOf(item: GuiItemRenderState): Int? {
        if (item.oversizedItemBounds() != null) return null
        val p = item.pose()
        if (p.m01() != 0f || p.m10() != 0f || p.m00() <= 0f || abs(p.m00() - p.m11()) > 1e-4f) return null
        val exact = 16f * guiScale * p.m00()
        val size = exact.roundToInt()
        return if (size == 16 * guiScale || size < 1 || size > MAX_SIZE) null else size
    }
}
