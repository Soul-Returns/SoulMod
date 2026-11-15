package com.soulreturns.features.updater

import com.soulreturns.api.LocationApi
import com.soulreturns.platform.updater.UpdatePhase
import com.soulreturns.platform.updater.UpdateService
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.Vec3

object InGameUpdatePrompt {
    private val policy = IdleRestartPolicy()
    private var previousPlayer: LocalPlayer? = null
    private var position: Vec3? = null
    private var yaw = 0f
    private var pitch = 0f

    @JvmStatic
    fun onInput() { policy.activity() }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick(it) })
    }

    private fun tick(mc: Minecraft) {
        val s = UpdateService.snapshot
        val version = s.release?.version
        val ready = UpdateService.automatic && s.autoInstall && s.phase == UpdatePhase.READY_RESTART
        val screen = mc.gui.screen()
        if (ready && screen is UpdateScreen && version != null) policy.markShown(version)
        val p = mc.player
        val moved = p !== previousPlayer || (p != null && (p.position() != position || p.yRot != yaw || p.xRot != pitch))
        previousPlayer = p
        position = p?.position()
        yaw = p?.yRot ?: 0f
        pitch = p?.xRot ?: 0f
        val keys = mc.options
        val active = moved || p == null || !p.isAlive || p.hurtTime > 0 || p.isUsingItem || p.fishing != null ||
            !p.onGround() || p.isInWater || p.isInLava || keys.keyAttack.isDown || keys.keyUse.isDown ||
            keys.keyUp.isDown || keys.keyDown.isDown || keys.keyLeft.isDown || keys.keyRight.isDown ||
            keys.keyJump.isDown || keys.keyShift.isDown
        val eligible = ready && p != null && screen == null && mc.gui.overlay() == null && !active &&
            !LocationApi.inDungeon && !(LocationApi.inSkyblock && LocationApi.mode == "kuudra")
        if (policy.due(System.nanoTime() / 1_000_000L, eligible, version)) {
            UpdateNotices.markSaid("ready:$version")
            mc.setScreenAndShow(UpdateScreen(null, UpdateScreen.AUTO))
        }
    }
}
