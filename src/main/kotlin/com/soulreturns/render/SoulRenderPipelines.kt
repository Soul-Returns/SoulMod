// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License, Copyright (c) odtheking
package com.soulreturns.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

object SoulRenderPipelines {
    val ROUND_RECT: RenderPipeline =
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("soul", "pipeline/round_rect"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("soul", "core/round_rect"))
                .withVertexShader(Identifier.fromNamespaceAndPath("soul", "core/round_rect"))
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .withUniform("u", UniformType.UNIFORM_BUFFER)
                .withBlend(BlendFunction.TRANSLUCENT)
                .build()
        )
}
