package com.soulreturns.config.gui

import com.soulreturns.config.ChromaConfig
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.model.CycleOption
import com.soulreturns.config.gui.model.SliderOption

/**
 * The global chroma rows ([ChromaConfig], drawn by `render/Chroma`): Settings › Interface › Chroma, and any other page
 * that wants the same two controls (the scoreboard's Look). Each call returns a fresh option, so two pages never
 * share widget state.
 */
object ChromaOptions {
    private val ids = listOf(ChromaConfig.SCREEN, ChromaConfig.TOGETHER)

    fun motion(): CycleOption =
        CycleOption(
            "Chroma motion", "Across the screen: one rainbow over the whole screen, every chroma color lines up with its neighbors. All together: one color at a time",
            { ids.indexOf(cfg.chroma.motion).coerceAtLeast(0) },
            { i -> ConfigManager.update { copy(chroma = chroma.copy(motion = ids[i.coerceIn(0, ids.size - 1)])) } },
            listOf("Across the screen", "All together"), 0,
        )

    fun speed(): SliderOption =
        SliderOption(
            "Chroma speed", "How fast every chroma color moves",
            { cfg.chroma.speed.toDouble() },
            { v -> ConfigManager.update { copy(chroma = chroma.copy(speed = v.toFloat())) } },
            ChromaConfig().speed.toDouble(), ChromaConfig.MIN_SPEED.toDouble(), ChromaConfig.MAX_SPEED.toDouble(), 0.5,
        )
}
