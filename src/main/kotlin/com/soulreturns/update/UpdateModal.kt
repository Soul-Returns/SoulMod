package com.soulreturns.update

import com.soulreturns.Soul
import com.soulreturns.config.gui.Theme
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.OwoUIAdapter
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.net.URI

class UpdateModal(private val info: UpdateInfo) : BaseOwoScreen<FlowLayout>(Component.literal("Soul Update")) {

    companion object {
        /** Set to true for the lifetime of the game session when the player dismisses the modal. */
        var dismissed = false
    }

    private enum class State { IDLE, DOWNLOADING, DONE, ERROR }

    private var state = State.IDLE
    private var errorMessage = ""
    private lateinit var buttonsRow: FlowLayout
    private lateinit var statusLabel: LabelComponent

    override fun createAdapter(): OwoUIAdapter<FlowLayout> =
        OwoUIAdapter.create(this) { hSize, vSize -> UIContainers.verticalFlow(hSize, vSize) }

    override fun build(root: FlowLayout) {
        root.surface(Surface.BLANK)
        root.horizontalAlignment(HorizontalAlignment.CENTER)
        root.verticalAlignment(VerticalAlignment.CENTER)

        val card = UIContainers.verticalFlow(Sizing.fixed(340), Sizing.content())
        card.surface(Theme.panelSurface)
        card.padding(Insets.of(22))
        card.gap(10)

        card.child(
            UIComponents.label(Component.literal("Soul Update Available"))
                .color(Theme.color(Theme.TEXT))
        )

        val currentVer = Soul.version.substringBefore("+")
        card.child(
            UIComponents.label(Component.literal("$currentVer  →  ${info.version}"))
                .color(Theme.color(Theme.ACCENT))
        )

        statusLabel = UIComponents.label(Component.empty())
        statusLabel.color(Theme.color(Theme.TEXT_DIM))
        statusLabel.horizontalSizing(Sizing.fill(100))
        card.child(statusLabel)

        buttonsRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.fixed(20))
        buttonsRow.horizontalAlignment(HorizontalAlignment.RIGHT)
        buttonsRow.gap(8)
        card.child(buttonsRow)

        rebuildButtons()
        root.child(card)
    }

    private fun rebuildButtons() {
        buttonsRow.clearChildren()
        when (state) {
            State.IDLE -> {
                buttonsRow.child(ghostButton("Not Now") { dismiss() })
                buttonsRow.child(ghostButton("View Release") {
                    Util.getPlatform().openUri(URI.create(info.releaseUrl))
                })
                buttonsRow.child(accentButton("Update") { startDownload() })
            }
            State.DOWNLOADING -> {
                buttonsRow.child(
                    UIComponents.label(Component.literal("Downloading…"))
                        .color(Theme.color(Theme.TEXT_DIM))
                )
            }
            State.DONE -> {
                buttonsRow.child(ghostButton("Later") { dismiss() })
                buttonsRow.child(accentButton("Restart Now") { System.exit(0) })
            }
            State.ERROR -> {
                buttonsRow.child(ghostButton("Close") { dismiss() })
            }
        }
    }

    private fun startDownload() {
        state = State.DOWNLOADING
        rebuildButtons()
        Updater.downloadAndSchedule(
            info,
            onProgress = { p ->
                statusLabel.text(Component.literal("Downloading… ${(p * 100).toInt()}%"))
            },
            onDone = {
                state = State.DONE
                statusLabel.text(Component.literal("Download complete. Restart to apply the update."))
                rebuildButtons()
            },
            onError = { msg ->
                state = State.ERROR
                errorMessage = msg
                statusLabel.text(Component.literal("Error: $errorMessage"))
                rebuildButtons()
            }
        )
    }

    private fun dismiss() {
        dismissed = true
        Minecraft.getInstance().setScreen(null)
    }

    override fun shouldCloseOnEsc() = true

    override fun onClose() {
        dismissed = true
        super.onClose()
    }

    private fun accentButton(label: String, action: () -> Unit): ButtonComponent {
        val btn = UIComponents.button(Component.empty()) { action() }
        btn.horizontalSizing(Sizing.fixed(100))
        btn.verticalSizing(Sizing.fill(100))
        btn.margins(Insets.none())
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            val bg = if (button.isHovered) Theme.ACCENT_DIM else Theme.ACCENT
            DrawContextRenderer.roundedFill(
                ctx, button.x, button.y,
                button.x + button.width, button.y + button.height,
                bg, Theme.ITEM_RADIUS
            )
            val tr = Minecraft.getInstance().font
            val tx = button.x + (button.width - tr.width(label)) / 2
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(label), tx, ty, Theme.TEXT, false)
        })
        return btn
    }

    private fun ghostButton(label: String, action: () -> Unit): ButtonComponent {
        val btn = UIComponents.button(Component.empty()) { action() }
        btn.horizontalSizing(Sizing.fixed(80))
        btn.verticalSizing(Sizing.fill(100))
        btn.margins(Insets.none())
        btn.renderer(ButtonComponent.Renderer { ctx, button, _ ->
            if (button.isHovered) {
                DrawContextRenderer.roundedFill(
                    ctx, button.x, button.y,
                    button.x + button.width, button.y + button.height,
                    Theme.PANEL_HOVER, Theme.ITEM_RADIUS
                )
            }
            val tr = Minecraft.getInstance().font
            val tx = button.x + (button.width - tr.width(label)) / 2
            val ty = button.y + (button.height - tr.lineHeight) / 2
            ctx.drawString(tr, Component.literal(label), tx, ty, Theme.TEXT_DIM, false)
        })
        return btn
    }
}
