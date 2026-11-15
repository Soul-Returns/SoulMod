package com.soulreturns.features.inventory

import com.soulreturns.config.cfg
import com.soulreturns.features.storage.StorageOverlay
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

/**
 * Keeps the cursor where it was when one container menu replaces another (storage pages, SkyBlock
 * menu navigation). Hypixel closes the old menu before opening the next, so vanilla grabs the mouse
 * (centering it) and releases it again (centering it once more). [MouseHandlerMixin] reports the
 * position before the grab, and the release puts the cursor back there when a container opens again
 * within [WINDOW_MS] of a container closing.
 *
 * [com.soulreturns.mixin.MouseHandlerMixin] calls [onGrab] and [releasePosition] on the render thread.
 */
object KeepCursor {
    /** Close and reopen arrive in one packet batch; ping spikes and menus opened by a command take longer. */
    private const val WINDOW_MS = 500L

    private var containerClosedAt = 0L
    private var grabbedAt = 0L
    private var x = 0.0
    private var y = 0.0

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is AbstractContainerScreen<*>) return@AfterInit
                ScreenEvents.remove(screen).register(
                    ScreenEvents.Remove { containerClosedAt = System.currentTimeMillis() },
                )
            },
        )
    }

    @JvmStatic
    fun onGrab(
        cursorX: Double,
        cursorY: Double,
    ) {
        x = cursorX
        y = cursorY
        grabbedAt = System.currentTimeMillis()
    }

    /** The position to release the cursor at instead of the window center, or null for vanilla. */
    @JvmStatic
    fun releasePosition(): DoubleArray? {
        if (!cfg.general.keepCursorBetweenMenus && StorageOverlay.pendingPage() == null) return null
        if (Minecraft.getInstance().gui.screen() !is AbstractContainerScreen<*>) return null
        val now = System.currentTimeMillis()
        if (now - grabbedAt > WINDOW_MS || now - containerClosedAt > WINDOW_MS) return null
        return doubleArrayOf(x, y)
    }
}
