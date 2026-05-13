package com.soulreturns.ui.hud

import com.soulreturns.features.notifications.BackendNotificationCenter
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * Top-center toast strip for backend-pushed notifications. Renders whatever
 * [BackendNotificationCenter] currently has active; the center handles queueing and expiry.
 *
 * Not user-positionable on purpose — these are server-initiated and rare; a fixed prominent
 * spot is fine. If we ever need positioning we'd push it through `GuiLayoutApi` like the
 * other HUDs.
 *
 * Color by severity:
 *   - `error`   → red text on dark backdrop
 *   - `warning` → yellow
 *   - `info`    → white (default)
 */
object BackendNotificationHud {
    private const val PADDING = 6
    private const val TOP_OFFSET = 20
    private const val LINE_SPACING = 4
    private const val BACKGROUND_ARGB: Int = 0xC0000000.toInt()

    private val ELEMENT_ID = Identifier.fromNamespaceAndPath("soul", "backend_notifications")

    fun register() {
        HudElementRegistry.addLast(
            ELEMENT_ID,
            HudElement { context, _ ->
                val entries = BackendNotificationCenter.visible()
                if (entries.isEmpty()) return@HudElement
                val client = Minecraft.getInstance()
                if (client.options.hideGui) return@HudElement
                val font = client.font
                val screenW = context.guiWidth()

                var y = TOP_OFFSET
                for (entry in entries) {
                    val width = font.width(entry.message)
                    val boxW = width + PADDING * 2
                    val boxH = font.lineHeight + PADDING * 2
                    val x = (screenW - boxW) / 2

                    context.fill(x, y, x + boxW, y + boxH, BACKGROUND_ARGB)
                    val textColor = severityColor(entry.severity)
                    context.drawString(font, entry.message, x + PADDING, y + PADDING, textColor, true)

                    y += boxH + LINE_SPACING
                }
            }
        )
    }

    private fun severityColor(severity: String): Int =
        when (severity.lowercase()) {
            "error" -> 0xFFFF5555.toInt()
            "warning", "warn" -> 0xFFFFD744.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
}
