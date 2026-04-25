package com.soulreturns.profileviewer.service

import com.google.gson.JsonObject
import com.soulreturns.config.cfg
import com.soulreturns.profileviewer.SpvExecutor
import com.soulreturns.api.BackendClient
import com.soulreturns.profileviewer.api.MojangApi
import com.soulreturns.profileviewer.gui.ProfileViewerScreen
import com.soulreturns.profileviewer.model.SkyblockProfile
import com.soulreturns.profileviewer.model.SkyblockProfilesResponse
import com.soulreturns.util.soulChat
import net.minecraft.client.MinecraftClient
import java.util.UUID
import java.util.concurrent.CompletableFuture

object ProfileViewerService {

    /** Enters the profile-viewer flow: resolve UUID -> fetch profiles -> open screen. */
    fun openFor(rawName: String, profileOverride: String?) {
        if (!cfg.profileViewer.enabled()) {
            soulChat("§cProfile viewer is disabled in config.")
            return
        }
        val name = rawName.trim()
        if (name.isEmpty() || !name.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            soulChat("§cInvalid username: $rawName")
            return
        }

        soulChat("§7Loading §f$name§7...")
        MojangApi.resolveUuid(name)
            .thenComposeAsync({ uuid ->
                if (uuid == null) {
                    soulChat("§cUnknown player: $name")
                    CompletableFuture.completedFuture(null)
                } else {
                    fetchProfiles(uuid).thenApply { resp -> uuid to resp }
                }
            }, SpvExecutor.executor)
            .thenAcceptAsync({ pair ->
                if (pair == null) return@thenAcceptAsync
                val (uuid, response) = pair
                if (response == null) return@thenAcceptAsync
                if (response.profiles.isEmpty()) {
                    soulChat("§cNo SkyBlock profiles found for §f$name§c.")
                    return@thenAcceptAsync
                }
                val target: SkyblockProfile? = profileOverride?.let { response.byCuteName(it) }
                    ?: response.selected()
                    ?: response.profiles.first()
                if (profileOverride != null && target?.cuteName?.equals(profileOverride, true) != true) {
                    val available = response.profiles.joinToString(", ") { it.cuteName }
                    soulChat("§cProfile §f$profileOverride§c not found. Available: §7$available")
                    return@thenAcceptAsync
                }
                if (target == null) {
                    soulChat("§cCould not pick a profile for $name.")
                    return@thenAcceptAsync
                }
                openScreen(name, uuid, response, target)
            }, SpvExecutor.executor)
            .exceptionally { ex ->
                SpvExecutor.warn("openFor pipeline crashed", ex)
                soulChat("§cUnexpected error: ${ex.message}")
                null
            }
    }

    private fun fetchProfiles(uuid: UUID): CompletableFuture<SkyblockProfilesResponse?> {
        val undashed = MojangApi.toUndashed(uuid)
        val endpoint = "/skyblock/profiles?uuid=$undashed"
        return BackendClient.get(endpoint, intent = "spv-dungeons").thenApply { result ->
            when (result) {
                is BackendClient.Result.Ok -> parseProfiles(result.json.asJsonObject)
                is BackendClient.Result.Error -> {
                    soulChat("§cBackend error ${result.statusCode}: ${result.message}")
                    null
                }
            }
        }
    }

    private fun parseProfiles(root: JsonObject): SkyblockProfilesResponse {
        val arr = root.getAsJsonArray("profiles") ?: return SkyblockProfilesResponse(root, emptyList())
        val profiles = arr.mapNotNull { el ->
            val obj = el?.asJsonObject ?: return@mapNotNull null
            val profileId = obj["profile_id"]?.asString ?: return@mapNotNull null
            val cute = obj["cute_name"]?.asString ?: profileId.take(8)
            val mode = obj["game_mode"]?.takeIf { !it.isJsonNull }?.asString
            val selected = obj["selected"]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val membersObj = obj.getAsJsonObject("members") ?: JsonObject()
            val members = membersObj.entrySet().associate { (k, v) ->
                k to (v as JsonObject)
            }
            SkyblockProfile(obj, profileId, cute, mode, selected, members)
        }
        return SkyblockProfilesResponse(root, profiles)
    }

    private fun openScreen(name: String, uuid: UUID, response: SkyblockProfilesResponse, initial: SkyblockProfile) {
        MinecraftClient.getInstance().execute {
            val screen = ProfileViewerScreen(name, uuid, response, initial)
            MinecraftClient.getInstance().setScreen(screen)
        }
    }
}
