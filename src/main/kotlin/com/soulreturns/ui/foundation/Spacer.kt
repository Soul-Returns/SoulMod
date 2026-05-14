package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.composable

/**
 * Empty composable that occupies space. Use to push siblings apart inside a [Row] or [Column],
 * or to introduce padding without wrapping content in a Box.
 *
 * Sizes itself from `Modifier.size` / `Modifier.width` / `Modifier.height` / `Modifier.fillMaxWidth`
 * etc. — the modifier chain is the only sizing source. With no size modifier the spacer measures
 * to its min-constraint values, which is typically 0×0.
 */
@SoulComposable
fun Spacer(modifier: SoulModifier = SoulModifier.Empty) {
    SoulComposer.current.composable(SpacerNode(modifier))
}

internal class SpacerNode(override val modifier: SoulModifier) : SoulNode() {
    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        val (w, h) = c.constrain(c.minWidth, c.minHeight)
        val out = SoulMeasured(w, h)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) = Unit // intentional no-op
}
