// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License, Copyright (c) odtheking
package com.soulreturns.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.client.gl.UniformType
import net.minecraft.client.render.VertexFormats
import net.minecraft.util.Identifier

object SoulRenderPipelines {
    val ROUND_RECT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.of("soul", "pipeline/round_rect"))
            .withFragmentShader(Identifier.of("soul", "core/round_rect"))
            .withVertexShader(Identifier.of("soul", "core/round_rect"))
            .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.QUADS)
            .withUniform("u", UniformType.UNIFORM_BUFFER)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build()
    )
}
