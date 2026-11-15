package com.soulreturns.devbridge

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The client's current frame as a PNG in `<gameDir>/soul-dev/screenshots/`: a read-back of the
 * main render target (vanilla's own screenshot path minus its chat line); encoding and the file
 * write happen off the render thread.
 */
object Screenshots {
    private val dir: Path = BridgeInfo.dir.resolve("screenshots")
    private val io = Executors.newSingleThreadExecutor { Thread(it, "Soul devbridge screenshot").apply { isDaemon = true } }
    private val names = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

    /** Blocks the calling (server) thread ≤10 s; returns the file. */
    fun take(): Path {
        val mc = Minecraft.getInstance()
        val target = dir.resolve(LocalDateTime.now().format(names) + ".png")
        val done = CompletableFuture<Path>()
        mc.execute {
            try {
                Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget()) { image: NativeImage ->
                    io.execute {
                        try {
                            Files.createDirectories(dir)
                            image.use { it.writeToFile(target) }
                            done.complete(target)
                        } catch (t: Throwable) {
                            done.completeExceptionally(t)
                        }
                    }
                }
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        val path = done.get(10, TimeUnit.SECONDS)
        Bridge.emit("screenshot") { json(mapOf("file" to path.toString())) }
        return path
    }
}
