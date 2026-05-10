package com.soulreturns.ui.config.components

import com.soulreturns.ui.theme.Theme
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.component.ButtonComponent
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Stateless [ButtonComponent.Renderer] factories used across the config screen.
 *
 * No instance state — every `Renderer` returned is a fresh closure. Renderers are pure
 * presentation: they take the button geometry and a hover flag and draw the right thing.
 */
internal object ConfigRenderers {

    /** Sidebar category-header row: arrow + uppercased label, hover highlight. White text. */
    fun categoryHeader(text: String, expanded: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val tr = Minecraft.getInstance().font
            if (button.isHovered) {
                DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS,
                )
            }
            val arrow = if (expanded) "▾" else "▸"
            val label = "$arrow  $text"
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(label), button.x + 6, ty, Theme.TEXT, false)
        }
    }

    /** Sidebar subcategory item: rounded selection highlight + dim/bright text. */
    fun sidebarItem(text: String, selected: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val tr = Minecraft.getInstance().font
            when {
                selected -> DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.ACCENT, Theme.ITEM_RADIUS,
                )
                button.isHovered -> DrawContextRenderer.roundedFill(
                    ctx,
                    button.x, button.y, button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS,
                )
            }
            val textColor = if (selected) Theme.TEXT else Theme.TEXT_DIM
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(text), button.x + 10, ty, textColor, false)
        }
    }

    /**
     * Footer-style button background. Two visual modes:
     *  - `accent = true`  → blue (ACCENT) / dim-blue (ACCENT_DIM) on hover.
     *  - `accent = false` → ghost (PANEL_INSET) / panel hover.
     *
     * Used for the screen `Done` button, the `Move GUI` sidebar footer, and inline ghost buttons.
     */
    fun footerButton(accent: Boolean): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val bg = when {
                accent && button.isHovered -> Theme.ACCENT_DIM
                accent                     -> Theme.ACCENT
                button.isHovered           -> Theme.PANEL_HOVER
                else                       -> Theme.PANEL_INSET
            }
            DrawContextRenderer.roundedFill(
                ctx,
                button.x, button.y, button.x + button.width, button.y + button.height,
                bg, Theme.ITEM_RADIUS,
            )
        }
    }

    /**
     * Action-row button background — visible against `panelInsetSurface` cards.
     * Normal: PANEL_HOVER (one shade brighter than the card). Hover: ACCENT.
     */
    fun actionButton(): ButtonComponent.Renderer {
        return ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.ACCENT else Theme.PANEL_HOVER
            DrawContextRenderer.roundedFill(
                ctx,
                button.x, button.y, button.x + button.width, button.y + button.height,
                bg, Theme.ITEM_RADIUS,
            )
        }
    }
}
