package com.soulreturns.features

import com.soulreturns.Soul
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry
import net.minecraft.fluid.Fluids

object ReplaceLava {
    fun replaceLava() {
        if (Soul.configManager.config.instance.RenderCategory.replaceLava) {
            FluidRenderHandlerRegistry.INSTANCE.register(
                Fluids.LAVA,
                FluidRenderHandlerRegistry.INSTANCE.get(Fluids.WATER)
            )
            FluidRenderHandlerRegistry.INSTANCE.register(
                Fluids.FLOWING_LAVA,
                FluidRenderHandlerRegistry.INSTANCE.get(Fluids.FLOWING_WATER)
            )
        }
    }
}