package com.soulreturns.mixin.render;

import com.soulreturns.features.chat.ChatRightClickCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/**
 * Right-click in the chat screen → copy the message under the cursor to the clipboard.
 * Injects at the HEAD of {@code ChatScreen.mouseClicked} so we get the click before
 * Mojang's styled-link handling. Right-click (button == 1) isn't used by vanilla chat,
 * so consuming the event has no functional side-effect — but we still gate on
 * {@code button == 1} to leave left/middle clicks for the existing mixin / Mojang flow.
 *
 * <p><b>Coordinate transform.</b> The existing {@link ChatScreenMixin} applies a
 * scale-inverse to {@code click.x()} / {@code click.y()} via {@code WrapOperation} so
 * the styled-link hit-test matches the visually-scaled chat. That {@code WrapOperation}
 * only fires for the specific {@code INVOKE} sites it targets — at our HEAD inject
 * point the raw screen coordinates are still on the event, so we have to mirror the
 * same transform manually before calling
 * {@link net.minecraft.client.gui.components.ChatComponent#getMessageEndIndexAt}.
 * Pivot is {@code (0, screenHeight)} (chat is anchored to bottom-left).
 *
 * <p><b>Modifiers.</b> {@code MouseButtonEvent} implements {@code InputWithModifiers}
 * (records the GLFW modifier bitmask at click time via {@code hasShiftDown()} /
 * {@code hasControlDown()} / {@code hasAltDown()}). The old {@code Screen.hasShiftDown()}
 * static helpers were removed in 1.21.11. Four intended combos: plain → full message
 * plain text; Shift → single visible line plain text; Ctrl → full message with §-color
 * codes; Alt → JSON envelope including any hover-tooltip components. Implemented inside
 * {@link ChatRightClickCopy#handleRightClick}; this mixin only collects + forwards the
 * modifier state.
 */
@Mixin(ChatScreen.class)
public class ChatScreenRightClickCopyMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void soulmod$rightClickCopy(MouseButtonEvent click, boolean ignoredFlag, CallbackInfoReturnable<Boolean> cir) {
        if (click.button() != 1) return;
        if (!ChatRightClickCopy.INSTANCE.isEnabled()) return;

        double rawX = click.x();
        double rawY = click.y();
        float scale = getCfg().render.hudScale.chatScale();
        double x = scale == 1.0f ? rawX : rawX / scale;
        double y;
        if (scale == 1.0f) {
            y = rawY;
        } else {
            double screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
            y = screenHeight + (rawY - screenHeight) / scale;
        }

        boolean shift = click.hasShiftDown();
        boolean ctrl = click.hasControlDown();
        boolean alt = click.hasAltDown();
        boolean handled = ChatRightClickCopy.INSTANCE.handleRightClick(x, y, shift, ctrl, alt);
        if (handled) {
            cir.setReturnValue(true);
        }
    }
}
