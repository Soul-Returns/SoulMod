package com.soulreturns.features.darkui

import com.mojang.blaze3d.platform.NativeImage
import com.soulreturns.config.cfg
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * "Dark UI": container screens in the style of the Skyblock-Dark-UI resource pack, done in code so
 * it agrees with Soul's own inventory changes (a pack cannot know about the hidden crafting grid,
 * the info display or the cover colors).
 *
 * - **Textures are recolored, not shipped.** Every `textures/gui/container/` blit is redirected
 *   (`GuiGraphicsExtractorMixin`) to a copy of whatever texture is currently loaded, with the
 *   vanilla GUI grays swapped for the pack's dark palette ([PALETTE], measured pixel by pixel
 *   against the pack). Nothing from the pack, which has no license, is bundled; colors the
 *   palette does not name — item art, a third-party pack's own colors — pass through untouched.
 * - **SkyBlock filler panes** (a black stained glass pane with a blank name) draw as plain
 *   background, so menus read as panels rather than a grid of glass.
 * - **Labels** in vanilla's 0x404040 are lightened, or they vanish on the dark background.
 *
 * Copies are made lazily on first use and dropped on every resource reload.
 */
object DarkUi {
    private val logger = SoulLogger("Soul/DarkUi")

    /** Background, matching the recolored textures; used by everything Soul paints into a container. */
    val BACKGROUND = 0xFF1C1C1C.toInt()
    val LABEL = 0xFFC8C8C8.toInt()
    private val VANILLA_LABEL = 0xFF404040.toInt()

    private val PALETTE = mapOf(
        0xFFC6C6C6.toInt() to 0xFF1C1C1C.toInt(), // panel
        0xFF8B8B8B.toInt() to 0xFF282828.toInt(), // slot fill
        0xFFFFFFFF.toInt() to 0xFF434343.toInt(), // bevel highlight
        0xFF373737.toInt() to 0xFF000000.toInt(), // slot shadow
        0xFF555555.toInt() to 0xFF060606.toInt(), // bevel shadow
    )

    private const val PREFIX = "textures/gui/container/"

    /** Original id → recolored id (or the original itself when it could not be read). */
    private val remapped = HashMap<Identifier, Identifier>()

    val active: Boolean get() = cfg.general.darkUi

    fun register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath("soul", "dark_ui"),
            ResourceManagerReloadListener { clear() },
        )
    }

    /** The texture to draw instead of [id]; [id] itself when Dark UI is off or does not apply. */
    fun texture(id: Identifier): Identifier {
        if (!active || !id.path.startsWith(PREFIX)) return id
        return remapped.getOrPut(id) { recolor(id) }
    }

    /** A container label color, lightened when it is vanilla's dark gray. */
    fun label(color: Int): Int = if (active && color == VANILLA_LABEL) LABEL else color

    /** True for the blank-named black glass panes SkyBlock fills its menus with. */
    fun isFiller(stack: ItemStack): Boolean =
        active && stack.`is`(Items.STAINED_GLASS_PANE.black()) && stack.hoverName.string.isBlank()

    private fun recolor(id: Identifier): Identifier {
        val mc = Minecraft.getInstance()
        return try {
            val image = mc.resourceManager.open(id).use { NativeImage.read(it) }
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val swapped = PALETTE[image.getPixel(x, y)] ?: continue
                    image.setPixel(x, y, swapped)
                }
            }
            val target = Identifier.fromNamespaceAndPath("soul", "dark_ui/${id.namespace}/${id.path}")
            mc.textureManager.register(target, DynamicTexture({ "Soul dark UI $id" }, image))
            target
        } catch (e: Exception) {
            logger.warn("Dark UI could not recolor $id: ${e.message}")
            id
        }
    }

    private fun clear() {
        val textures = Minecraft.getInstance().textureManager
        for (target in remapped.values) if (target.namespace == "soul") textures.release(target)
        remapped.clear()
    }
}
