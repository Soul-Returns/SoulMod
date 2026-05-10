package com.soulreturns.platform.mixinbridge;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

/**
 * Java helpers called from Mixin classes (and a couple of Kotlin call sites).
 * Lives in Java specifically because Mixin classes are Java and can't have
 * non-private static helpers — these need to live outside the mixin.
 */
public class RenderHelper {
    public static void pushScaledMatrix(GuiGraphics context, float scale, float pivotX, float pivotY) {
        Matrix3x2fStack matrices = context.pose();
        matrices.pushMatrix();
        if (scale != 1.0f) {
            matrices.translate(pivotX, pivotY);
            matrices.scale(scale, scale);
            matrices.translate(-pivotX, -pivotY);
        }
    }

    /**
     * Draw scaled text with shadow at the center of the screen
     * This method properly handles Matrix operations for Minecraft 1.21
     */
    public static void drawScaledText(GuiGraphics context, Font textRenderer,
                                      Component text, int centerX, int centerY,
                                      float scale, int color) {
        Matrix3x2fStack matrices = context.pose();

        int textWidth = textRenderer.width(text);

        // Calculate scaled dimensions
        int scaledTextWidth = (int)(textWidth * scale);

        // Calculate position in scaled coordinate space
        float x = (centerX - scaledTextWidth / 2.0f) / scale;
        float y = centerY / scale;

        // Save matrix state and apply scaling
        matrices.pushMatrix();
        matrices.scale(scale, scale);

        // Draw text at the scaled position
        context.drawString(textRenderer, text, (int)x, (int)y, color);

        // Restore matrix state
        matrices.popMatrix();
    }

    /**
     * Draws a colored border around an inventory slot
     *
     * @param context The DrawContext for rendering
     * @param x The x position of the slot
     * @param y The y position of the slot
     * @param color The color in ARGB format (0xAARRGGBB)
     */
    public static void drawSlotHighlight(GuiGraphics context, int x, int y, int color) {
        // Draw a 2-pixel thick border around the 16x16 slot
        // Slots are 16x16 pixels in size

        // Top border (2 pixels thick)
        context.fill(x, y, x + 16, y + 2, color);

        // Bottom border (2 pixels thick)
        context.fill(x, y + 14, x + 16, y + 16, color);

        // Left border (2 pixels thick, excluding corners to avoid overlap)
        context.fill(x, y + 2, x + 2, y + 14, color);

        // Right border (2 pixels thick, excluding corners to avoid overlap)
        context.fill(x + 14, y + 2, x + 16, y + 14, color);
    }
}
