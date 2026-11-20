package com.soulreturns.features.profileviewer.gui

import com.mojang.authlib.GameProfile
import com.soulreturns.features.profileviewer.gui.kit.PlayerModel
import com.soulreturns.util.MojangUuid
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.renderer.PlayerSkinRenderCache
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.player.PlayerSkin
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * The Overview's full-body model of one viewed player: a client-side [RemotePlayer] that is never
 * added to the level, wearing the decoded armor, drawn by vanilla's inventory entity renderer (which
 * also turns it toward the pointer). The screen keeps one per viewed player and drops it on close.
 *
 * The skin comes from [PlayerSkinRenderCache.lookup] by uuid, so it works for players who are not in
 * the tab list (an entity's own skin would come from the tab list's PlayerInfo, which is null for
 * them). The renderer reads the skin through `ClientAvatarEntity.getSkin()`, so [FigurePlayer]
 * overrides it.
 */
class ProfileFigure(val uuidUndashed: String, private val name: String) {
    private val uuid: UUID = MojangUuid.parse(uuidUndashed) ?: UUID(0L, 0L)
    private val lookup: CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>> = when (fixtureSkinStates[uuidUndashed]) {
        "loading" -> CompletableFuture()
        "none" -> CompletableFuture.completedFuture(Optional.empty())
        else -> Minecraft.getInstance().playerSkinRenderCache().lookup(profile(uuidUndashed))
    }
    private var player: FigurePlayer? = null
    private var worn: List<ItemStack?>? = null

    fun state(): Int {
        if (!lookup.isDone) return PlayerModel.LOADING
        return if (skin() != null) PlayerModel.READY else PlayerModel.FAILED
    }

    private fun skin(): PlayerSkin? = try {
        lookup.getNow(Optional.empty()).map { it.playerSkin() }.orElse(null)
    } catch (_: Throwable) {
        null
    }

    /**
     * Draws the model in the box (GUI px, not Soul px: the entity renders in its own pass that ignores
     * the pose) when the skin is ready. [armor] is helmet to boots. Returns the [PlayerModel] state.
     */
    fun draw(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, mouseX: Float, mouseY: Float, armor: List<ItemStack?>?): Int {
        val state = state()
        if (state != PlayerModel.READY) return state
        val level = Minecraft.getInstance().level ?: return PlayerModel.FAILED
        val skin = skin() ?: return PlayerModel.FAILED
        val p = player ?: FigurePlayer(level, GameProfile(uuid, name), skin).also { player = it }
        if (worn != armor) {
            worn = armor
            for ((i, slot) in ARMOR_SLOTS.withIndex()) p.setItemSlot(slot, armor?.getOrNull(i) ?: ItemStack.EMPTY)
        }
        val scale = ((y1 - y0) / 2.05f).toInt()
        InventoryScreen.extractEntityInInventoryFollowsMouse(g, x0, y0, x1, y1, scale, 0.0625f, mouseX, mouseY, p)
        return state
    }

    private class FigurePlayer(level: ClientLevel, profile: GameProfile, private val skin: PlayerSkin) : RemotePlayer(level, profile) {
        init {
            // Never added to the level, so it gets no id there; renderers key per-entity state on one.
            id = FIGURE_ENTITY_ID
            // Every skin layer (hat, jacket, sleeves, trousers) on, as the player's own client shows them.
            entityData.set(DATA_PLAYER_MODE_CUSTOMISATION, 0x7F.toByte())
        }

        override fun getSkin(): PlayerSkin = skin

        // No name tag over the model.
        override fun isInvisibleTo(player: Player): Boolean = true
    }

    companion object {
        private val ARMOR_SLOTS = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

        /** Negative: no server-assigned entity id is ever negative, so it cannot collide with a real one. */
        private const val FIGURE_ENTITY_ID = -0x5_0760

        /** Dev fixtures: skins given inline (`/soul dev spv fixture`), since the test client has no session server. */
        val fixtureSkins = ConcurrentHashMap<String, GameProfile>()

        /** Dev fixtures: `"loading"` holds the skin lookup forever, `"none"` fails it (the figure's two fallbacks). */
        val fixtureSkinStates = ConcurrentHashMap<String, String>()

        /** What the skin cache resolves for [uuidUndashed]: a fixture's inline skin, else the uuid. */
        fun profile(uuidUndashed: String): ResolvableProfile =
            fixtureSkins[uuidUndashed]?.let { ResolvableProfile.createResolved(it) }
                ?: ResolvableProfile.createUnresolved(MojangUuid.parse(uuidUndashed) ?: UUID(0L, 0L))
    }
}
