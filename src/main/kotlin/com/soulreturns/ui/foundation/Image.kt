package com.soulreturns.ui.foundation

import com.soulreturns.platform.render.nvg.NvgRenderer
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.drawBackgrounds
import com.soulreturns.ui.composer.recordHitRegions

/**
 * Paints an image loaded from the classpath. The [resourcePath] is relative to the resource
 * root, e.g. `"assets/soul/textures/gui/discord.png"`. The image is loaded once and cached
 * for the JVM lifetime via [NvgRenderer.loadImage].
 *
 * Size: pulled from `Modifier.size(...)` / `.width(...)` / `.height(...)` / `.fillMaxSize()`.
 * Without a size modifier the image defaults to 16×16 logical pixels.
 *
 * [alpha] is a multiplier (0..1) on the image's own alpha channel — set < 1 for dim/hover
 * effects.
 */
@SoulComposable
fun Image(
    resourcePath: String,
    modifier: SoulModifier = SoulModifier.Empty,
    alpha: Float = 1f,
) {
    SoulComposer.current.composable(ImageNode(resourcePath, modifier, alpha))
}

internal class ImageNode(
    private val resourcePath: String,
    override val modifier: SoulModifier,
    private val alpha: Float,
) : SoulNode() {
    companion object {
        private const val DEFAULT_SIZE = 16f
    }

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        val w = if (c.hasBoundedWidth()) c.maxWidth else DEFAULT_SIZE
        val h = if (c.hasBoundedHeight()) c.maxHeight else DEFAULT_SIZE
        val (cw, ch) = c.constrain(w, h)
        val out = SoulMeasured(cw, ch)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        val m = measured ?: return
        modifier.drawBackgrounds(x, y, m.width, m.height)
        val handle = NvgRenderer.loadImage(resourcePath)
        NvgRenderer.image(x, y, m.width, m.height, handle, alpha)
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
    }
}
