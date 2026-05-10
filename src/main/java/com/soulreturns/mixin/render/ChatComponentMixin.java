package com.soulreturns.mixin.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    // Targets the public render overload called by both InGameHud (unfocused overlay)
    // and ChatScreen (focused, when pressing T). Using RETURN (not TAIL) so the pop
    // fires at every return point, including any early-return guard clauses.
    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
        at = @At("HEAD")
    )
    private void beforeRenderChat(GuiGraphics context, Font textRenderer,
                                  int i1, int i2, int i3, boolean b1, boolean b2,
                                  CallbackInfo ci) {
        // When ChatScreen is open, ChatScreenMixin scales the whole screen (messages + input)
        // so we skip here to avoid double-scaling the messages.
        if (Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
            context.pose().pushMatrix();
            return;
        }
        float scale = getCfg().render.hudScale.chatScale();
        Matrix3x2fStack matrices = context.pose();
        matrices.pushMatrix();
        if (scale != 1.0f) {
            float pivotY = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            matrices.translate(0.0f, pivotY);
            matrices.scale(scale, scale);
            matrices.translate(0.0f, -pivotY);
        }
    }

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;IIIZZ)V",
        at = @At("RETURN")
    )
    private void afterRenderChat(GuiGraphics context, Font textRenderer,
                                 int i1, int i2, int i3, boolean b1, boolean b2,
                                 CallbackInfo ci) {
        context.pose().popMatrix();
    }
}
