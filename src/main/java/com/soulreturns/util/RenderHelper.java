package com.soulreturns.util;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
//? if >=1.21.8 {
import org.joml.Matrix3x2fStack;
//?} else {
/*import net.minecraft.client.util.math.MatrixStack;
*///?}

public class RenderHelper {
    /**
     * Draw scaled text with shadow at the center of the screen
     * This method properly handles Matrix operations for Minecraft 1.21
     */
    public static void drawScaledText(DrawContext context, TextRenderer textRenderer,
                                     Text text, int centerX, int centerY,
                                     float scale, int color) {
        //? if >=1.21.8 {
        Matrix3x2fStack matrices = context.getMatrices();
        //?} else {
        /*MatrixStack matrices = context.getMatrices();
        *///?}

        int textWidth = textRenderer.getWidth(text);

        // Calculate scaled dimensions
        int scaledTextWidth = (int)(textWidth * scale);

        // Calculate position in scaled coordinate space
        float x = (centerX - scaledTextWidth / 2.0f) / scale;
        float y = centerY / scale;

        // Save matrix state and apply scaling
        //? if >=1.21.8 {
        matrices.pushMatrix();
        matrices.scale(scale, scale);
        //?} else {
        /*matrices.push();
        matrices.scale(scale, scale, 1.0f);
        *///?}

        // Draw text at the scaled position
        context.drawTextWithShadow(textRenderer, text, (int)x, (int)y, color);

        // Restore matrix state
        //? if >=1.21.8 {
        matrices.popMatrix();
        //?} else {
        /*matrices.pop();
        *///?}
    }

    /**
     * Draws a colored border around an inventory slot
     *
     * @param context The DrawContext for rendering
     * @param x The x position of the slot
     * @param y The y position of the slot
     * @param color The color in ARGB format (0xAARRGGBB)
     */
    public static void drawSlotHighlight(DrawContext context, int x, int y, int color) {
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

