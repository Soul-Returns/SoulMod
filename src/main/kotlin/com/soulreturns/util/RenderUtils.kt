package com.soulreturns.util

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.sounds.SoundEvents
import net.minecraft.network.chat.Component

object RenderUtils {
    private val alertMessages = mutableListOf<AlertMessage>()

    data class AlertMessage(
        val text: String,
        val color: Int,
        val textScale: Float = 4.0f,
        val expiryTime: Long = Long.MAX_VALUE
    )

    /**
     * Show an alert message in the center of the screen
     * @param text The text to display
     * @param color The color in ARGB format (0xAARRGGBB)
     * @param textScale The scale factor for the text size (default: 2.0 for double size)
     * @param durationMs How long to show the message in milliseconds (null for permanent until cleared)
     */
    fun showAlert(text: String, color: Int = 0xFFFF0000.toInt(), textScale: Float = 4.0f, durationMs: Long? = null) {
        val expiryTime = if (durationMs != null) {
            System.currentTimeMillis() + durationMs
        } else {
            Long.MAX_VALUE
        }
        alertMessages.add(AlertMessage(text, color, textScale, expiryTime))

        val client = Minecraft.getInstance()
        val player = client.player

        Thread {
            player?.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 5.0f, 0.5f)

            Thread.sleep(150)
            client.execute {
                player?.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 5.0f, 0.7f)
            }
            Thread.sleep(150)
            client.execute {
                player?.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 5.0f, 0.9f)
            }
            Thread.sleep(150)
            client.execute {
                player?.playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 5.0f, 1.1f)
            }
        }.start()
    }

    fun clearAlerts() {
        alertMessages.clear()
    }

    private fun clearExpiredAlerts() {
        val currentTime = System.currentTimeMillis()
        alertMessages.removeIf { it.expiryTime < currentTime }
    }

    /**
     * Render all active alert messages
     * Should be called from the HUD render mixin
     */
    fun renderAlerts(context: GuiGraphics) {
        clearExpiredAlerts()

        if (alertMessages.isEmpty()) return

        val minecraft = Minecraft.getInstance()
        val textRenderer = minecraft.font
        val window = minecraft.window

        val screenWidth = window.guiScaledWidth
        val screenHeight = window.guiScaledHeight
        val centerX = screenWidth / 2
        val centerY = (screenHeight / 2.5f).toInt()

        // Render each alert message
        alertMessages.forEachIndexed { index, alert ->
            val text = Component.literal(alert.text)
            val scale = alert.textScale
            val scaledTextHeight = (textRenderer.lineHeight * scale).toInt()

            // Calculate Y position with spacing for multiple alerts
            val posY = centerY - 20 + (index * scaledTextHeight)

            // Use RenderHelper to draw scaled text with proper matrix operations
            RenderHelper.drawScaledText(
                context,
                textRenderer,
                text,
                centerX,
                posY,
                scale,
                alert.color
            )
        }
    }
}

