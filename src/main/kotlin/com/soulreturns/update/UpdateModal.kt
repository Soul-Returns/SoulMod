package com.soulreturns.update

import com.soulreturns.Soul
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.HorizontalAlignment
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.fillMaxSize
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulScreen
import com.soulreturns.ui.theme.SoulTheme
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.util.Util
import java.net.URI

/**
 * Update-available modal — rebuilt on the Soul UI framework (P4.1 migration).
 *
 * State lives on the screen object as `@Volatile` fields; the composable re-reads them
 * each frame, so the async-callback path from [Updater.downloadAndSchedule] just mutates
 * state and the visual updates on the next frame. No manual `rebuildButtons()` needed.
 */
class UpdateModal(private val info: UpdateInfo) : SoulScreen(Component.literal("Soul Update")) {
    companion object {
        /** Set to true for the lifetime of the game session when the player dismisses the modal. */
        var dismissed: Boolean = false
    }

    private enum class State { IDLE, DOWNLOADING, DONE, ERROR }

    @Volatile private var state: State = State.IDLE

    @Volatile private var statusText: String = ""

    override fun shouldCloseOnEsc(): Boolean = true

    // Temporarily disabled — testing how the dim alone looks. Restore by returning `true`
    // (or just delete this override; the SoulScreen default is `false`).
    override fun blurBackground(): Boolean = false

    override fun onClose() {
        dismissed = true
        super.onClose()
    }

    @SoulComposable
    override fun Content() {
        // Full-screen opaque backdrop. The title-screen panorama is brighter than typical
        // game backgrounds (HDR-ish sunlit skybox) — any translucent dim leaves the bright
        // sand / sky bleeding through unpleasantly. Solid `Theme.colors.background` keeps the
        // modal readable. Users still see the spinning panorama briefly before the modal
        // opens (the 1.2 s opening delay in `Soul.onInitializeClient`) and after it closes.
        Column(
            modifier = SoulModifier.Empty.fillMaxSize().background(SoulTheme.colors.background),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = HorizontalAlignment.Center,
        ) {
            Surface(
                modifier = SoulModifier.Empty.width(340f),
                padding = 22f,
            ) {
                Column(gap = 10f, modifier = SoulModifier.Empty.fillMaxWidth()) {
                    Text(
                        text = "Soul Update Available",
                        size = SoulTheme.typography.title.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.title.font,
                    )

                    val currentVer = Soul.version.substringBefore("+")
                    Text(
                        text = "$currentVer  →  ${info.version}",
                        size = SoulTheme.typography.heading.size,
                        color = SoulTheme.colors.accent,
                        font = SoulTheme.typography.heading.font,
                    )

                    if (statusText.isNotEmpty()) {
                        Text(
                            text = statusText,
                            size = SoulTheme.typography.body.size,
                            color = SoulTheme.colors.textDim,
                            font = SoulTheme.typography.body.font,
                        )
                    }

                    ButtonsRow()
                }
            }
        }
    }

    @SoulComposable
    private fun ButtonsRow() {
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            gap = 8f,
        ) {
            when (state) {
                State.IDLE -> {
                    Button(label = "Not Now", onClick = { dismiss() }, key = "update.notnow")
                    Button(
                        label = "View Release",
                        onClick = { Util.getPlatform().openUri(URI.create(info.releaseUrl)) },
                        key = "update.release",
                    )
                    Button(
                        label = "Update",
                        onClick = { startDownload() },
                        key = "update.start",
                        accent = true,
                    )
                }
                State.DOWNLOADING -> {
                    Text(
                        text = "Downloading…",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.body.font,
                    )
                }
                State.DONE -> {
                    Button(label = "Later", onClick = { dismiss() }, key = "update.later")
                    Button(
                        label = "Restart Now",
                        onClick = { System.exit(0) },
                        key = "update.restart",
                        accent = true,
                    )
                }
                State.ERROR -> {
                    Button(label = "Close", onClick = { dismiss() }, key = "update.close")
                }
            }
        }
    }

    private fun startDownload() {
        state = State.DOWNLOADING
        statusText = "Downloading… 0%"
        Updater.downloadAndSchedule(
            info,
            onProgress = { p -> statusText = "Downloading… ${(p * 100).toInt()}%" },
            onDone = {
                state = State.DONE
                statusText = "Download complete. Restart to apply the update."
            },
            onError = { msg ->
                state = State.ERROR
                statusText = "Error: $msg"
            },
        )
    }

    private fun dismiss() {
        dismissed = true
        Minecraft.getInstance().setScreen(null)
    }
}
