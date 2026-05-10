package com.soulreturns.ui.config.components

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.core.Sizing
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.util.Util
import java.net.URI

/**
 * Discord and GitHub link buttons used in the config screen's title bar.
 * Render their PNG textures with a hover-tint and open the matching URL on click.
 */
internal object SocialIcons {

    private val DISCORD_ICON: Identifier = Identifier.fromNamespaceAndPath("soul", "textures/gui/discord.png")
    private const val DISCORD_TEX_W = 528
    private const val DISCORD_TEX_H = 400

    private val GITHUB_ICON: Identifier = Identifier.fromNamespaceAndPath("soul", "textures/gui/github.png")
    private const val GITHUB_TEX_W = 294
    private const val GITHUB_TEX_H = 288

    fun discord(): ButtonComponent = linkButton(
        DISCORD_ICON, DISCORD_TEX_W, DISCORD_TEX_H,
        9, 7,
        "https://discord.gg/Mn5dzEJEaJ",
        "Join the Discord",
    )

    fun github(): ButtonComponent = linkButton(
        GITHUB_ICON, GITHUB_TEX_W, GITHUB_TEX_H,
        7, 7,
        "https://github.com/Soul-Returns/SoulMod",
        "View on GitHub",
    )

    private fun linkButton(
        texture: Identifier,
        texW: Int, texH: Int,
        destW: Int, destH: Int,
        url: String,
        tooltip: String,
    ): ButtonComponent {
        val btn = UIComponents.button(Component.empty()) {
            Util.getPlatform().openUri(URI.create(url))
        }
        btn.horizontalSizing(Sizing.fixed(destW))
        btn.verticalSizing(Sizing.fixed(destH))
        btn.tooltip(Component.literal(tooltip))
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val tint = if (button.isHovered) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()
            ctx.blit(
                RenderPipelines.GUI_TEXTURED,
                texture,
                button.x, button.y,
                0f, 0f,
                button.width, button.height,
                texW, texH,
                texW, texH,
                tint,
            )
        })
        return btn
    }
}
