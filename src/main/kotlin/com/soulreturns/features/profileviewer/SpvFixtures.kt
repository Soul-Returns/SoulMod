package com.soulreturns.features.profileviewer

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import com.soulreturns.data.hypixel.HotmTreeClient
import com.soulreturns.data.hypixel.HotmTreeSnapshot
import com.soulreturns.data.hypixel.PlayerIdentity
import com.soulreturns.data.hypixel.SkillCapsClient
import com.soulreturns.data.hypixel.SkillCapsEnvelope
import com.soulreturns.data.hypixel.SkyblockProfiles
import com.soulreturns.data.hypixel.SoulStatsAnswer
import com.soulreturns.data.hypixel.SoulStatsClient
import com.soulreturns.data.hypixel.SoulStatsEnvelope
import com.soulreturns.data.sacks.SackCatalogClient
import com.soulreturns.data.sacks.SackCatalogSnapshot
import com.soulreturns.features.dev.DevOutput
import com.soulreturns.features.experiments.RngMeterStore
import com.soulreturns.features.fishing.FishingCounts
import com.soulreturns.features.mining.metaldetector.DetectorLootCounts
import com.soulreturns.features.mining.nucleus.NucleusCounts
import com.soulreturns.features.profileviewer.gui.LocalSoulCards
import com.soulreturns.features.profit.dragon.DragonProfitStats
import com.soulreturns.features.profileviewer.gui.ProfileFigure
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

/**
 * `/soul dev spv fixture <name>`: plays `<gameDir>/soul-dev/spv/<name>.json` through
 * [ProfileViewerService]'s presenter seams, exactly as a real lookup would arrive, without Hypixel
 * or a backend. For the test client and for building sections against invented players
 * (`src/test/resources/profileviewer/fixtures/` holds the shared ones). Dev-only (the command is
 * gated by DevAccess).
 *
 * File shape (every key but `name` and `uuid` optional):
 * ```
 * { "name": "Slayer_Sam", "uuid": "<32 hex>", "self": false,
 *   "skin": "<textures.minecraft.net hash>" | "loading" | "none", "slim": false,
 *   "resolveMs": 400, "profilesMs": 900, "hold": false,
 *   "failure": { "kind": "not-found" | "no-profiles" | "rate-limited" | "offline" | "other", "retryAfter": 30 },
 *   "profiles": { …a Hypixel /skyblock/profiles body… }, "profile": "Banana",
 *   "soulStats": { "status": 200, "body": { "schemaVersion": 1, "shared": true, "namespaces": { … } } },
 *   "caps": { "schemaVersion": 2, "profiles": { "<profile id>": { "farming": 60 } } },
 *   "hotmTree": { …a `GET /hotm/tree` body… }, "sackCatalog": { …a `GET /sacks` body… },
 *   "localSoul": { "nucleus": { …NucleusCounts… }, "fishing": { …FishingCounts… }, "dragons": { …DragonProfitStats… },
 *                  "detector": { …DetectorLootCounts… }, "rng": { "name": "Jaderald", "odds": 0.5, "readAt": "now-600000" } } }
 * ```
 * `self: true` swaps in your own name and uuid (the fixture's `uuid` member becomes yours); `hold: true`
 * stops after resolving (the loading state). `skin` gives the player a skin texture inline (the test
 * client has no session server to resolve one by uuid); without it the skin lookup goes by uuid; `"loading"`
 * and `"none"` force the figure's "Loading skin" and "No skin" fallbacks. `sackCatalog`
 * does the same for the sack catalog (the Inventories sack tiles). `hotmTree`
 * stands in for the backend's Heart of the Mountain catalog until its next refresh (the shared payload is
 * `src/test/resources/profileviewer/fragments/hotm-tree.json`). `localSoul` (with `self: true`) seeds the local
 * Soul cards in memory, for every profile of the fixture, in place of this computer's `stats.json` buckets
 * (each key optional; absent ones read the real stores); stats files are never written, and any real lookup
 * drops the seed again.
 *
 * Times relative to the load: any string `"now"`, `"now-<ms>"` or `"now+<ms>"` becomes that epoch-ms number
 * ([resolveTimes]), so Forge start times and the like stay plausible whenever the fixture is played.
 */
object SpvFixtures {
    private val json = Json { ignoreUnknownKeys = true }

    fun present(name: String) {
        DevOutput.begin("/soul dev spv fixture $name")
        val file = FabricLoader.getInstance().gameDir.resolve("soul-dev").resolve("spv").resolve("$name.json")
        if (!Files.isRegularFile(file)) {
            DevOutput.line("§cNo fixture at §f$file")
            return
        }
        val root = try {
            resolveTimes(json.parseToJsonElement(Files.readString(file)), System.currentTimeMillis()).jsonObject
        } catch (e: Exception) {
            DevOutput.line("§cCould not read §f$file§c: ${e.message}")
            return
        }
        val self = root.bool("self")
        val user = Minecraft.getInstance().user
        val identity = if (self) {
            PlayerIdentity(MojangUuid.undashed(user.profileId), user.name)
        } else {
            PlayerIdentity(root.str("uuid") ?: "00000000000000000000000000000000", root.str("name") ?: name)
        }
        val skin = root.str("skin")
        if (skin == "loading" || skin == "none") ProfileFigure.fixtureSkinStates[identity.uuidUndashed] = skin
        else ProfileFigure.fixtureSkinStates.remove(identity.uuidUndashed)
        if (!self && skin != null && skin != "loading" && skin != "none") {
            val model = if (root.bool("slim")) ""","metadata":{"model":"slim"}""" else ""
            val textures = """{"textures":{"SKIN":{"url":"http://textures.minecraft.net/texture/$skin"$model}}}"""
            val value = Base64.getEncoder().encodeToString(textures.toByteArray())
            val uuid = MojangUuid.parse(identity.uuidUndashed) ?: UUID(0L, 0L)
            ProfileFigure.fixtureSkins[identity.uuidUndashed] =
                GameProfile(uuid, identity.name, PropertyMap(ImmutableMultimap.of("textures", Property("textures", value))))
        }
        (root["hotmTree"] as? JsonObject)?.let { tree ->
            try {
                HotmTreeClient.preview(json.decodeFromJsonElement(HotmTreeSnapshot.serializer(), tree))
            } catch (e: Exception) {
                DevOutput.line("§cIgnoring §fhotmTree§c: ${e.message}")
            }
        }
        (root["sackCatalog"] as? JsonObject)?.let { catalog ->
            try {
                SackCatalogClient.preview(json.decodeFromJsonElement(SackCatalogSnapshot.serializer(), catalog))
            } catch (e: Exception) {
                DevOutput.line("§cIgnoring §fsackCatalog§c: ${e.message}")
            }
        }
        DevOutput.line("§7Presenting §f${identity.name}§7 from §f$file")
        seedLocal(if (self) root["localSoul"] as? JsonObject else null)
        ProfileViewerService.onLookupStarted(identity.name)
        SoulScope.io.launch {
            delay(root.num("resolveMs") ?: 0L)
            val failure = root["failure"] as? JsonObject
            if (failure != null) {
                val kind = failure.str("kind") ?: ProfileLookupFailure.OTHER
                val known = kind != ProfileLookupFailure.NOT_FOUND && kind != ProfileLookupFailure.INVALID_NAME
                ProfileViewerService.presentFailure(
                    ProfileLookupFailure(
                        identity.name,
                        failure.str("headline") ?: "Could not load ${identity.name}",
                        failure.str("detail"),
                        kind,
                        if (known) identity else null,
                        failure.num("retryAfter"),
                    ),
                )
                return@launch
            }
            ProfileViewerService.onResolved(identity)
            if (root.bool("hold")) return@launch
            delay(root.num("profilesMs") ?: 0L)
            var body = root["profiles"] as? JsonObject ?: JsonObject(emptyMap())
            val fixtureUuid = root.str("uuid")
            if (self && fixtureUuid != null) body = json.parseToJsonElement(body.toString().replace(fixtureUuid, identity.uuidUndashed)).jsonObject
            val profiles = SkyblockProfiles.from(body)
            val profile = profiles.pick(root.str("profile"))
            val member = profile?.member(identity.uuidUndashed)
            if (profile == null || member == null) {
                ProfileViewerService.presentFailure(
                    ProfileLookupFailure(identity.name, "No SkyBlock profiles", null, ProfileLookupFailure.NO_PROFILES, identity),
                )
                return@launch
            }
            ProfileViewerService.present(
                ProfileView(
                    identity, profiles, profile, member, fromCache = false,
                    preloaded = PreloadedExtras(soulStats(root["soulStats"] as? JsonObject), caps(root["caps"] as? JsonObject), root.num("loadedAgoMs") ?: 0L),
                ),
            )
        }
    }

    /** The `localSoul` seed in place of [LocalSoulCards.Stored] while [spec] is non-null; null restores the real stores. */
    fun seedLocal(spec: JsonObject?) {
        if (spec == null) {
            if (LocalSoulCards.source is SeededLocal) LocalSoulCards.source = LocalSoulCards.Stored
            return
        }
        LocalSoulCards.source = try {
            SeededLocal(
                (spec["nucleus"] as? JsonObject)?.let { json.decodeFromJsonElement(NucleusCounts.serializer(), it) },
                (spec["fishing"] as? JsonObject)?.let { json.decodeFromJsonElement(FishingCounts.serializer(), it) },
                (spec["dragons"] as? JsonObject)?.let { json.decodeFromJsonElement(DragonProfitStats.serializer(), it) },
                (spec["detector"] as? JsonObject)?.let { json.decodeFromJsonElement(DetectorLootCounts.serializer(), it) },
                (spec["rng"] as? JsonObject)?.let { RngMeterStore.SelectedDrop(it.str("name") ?: "?", (it["odds"] as? JsonPrimitive)?.doubleOrNull ?: 0.0, it.num("readAt") ?: 0L) },
            )
        } catch (e: Exception) {
            DevOutput.line("§cIgnoring §flocalSoul§c: ${e.message}")
            LocalSoulCards.Stored
        }
    }

    private class SeededLocal(
        val nucleus: NucleusCounts?,
        val fishing: FishingCounts?,
        val dragons: DragonProfitStats?,
        val detector: DetectorLootCounts?,
        val rng: RngMeterStore.SelectedDrop?,
    ) : LocalSoulCards.Source by LocalSoulCards.Stored {
        override fun nucleus(profile: String) = nucleus ?: LocalSoulCards.Stored.nucleus(profile)

        override fun fishing(profile: String) = fishing ?: LocalSoulCards.Stored.fishing(profile)

        override fun dragons(profile: String) = dragons ?: LocalSoulCards.Stored.dragons(profile)

        override fun detector(profile: String) = detector ?: LocalSoulCards.Stored.detector(profile)

        override fun rngRead() = rng != null || LocalSoulCards.Stored.rngRead()

        override fun rngSelected(profileId: String, profileName: String) = rng ?: LocalSoulCards.Stored.rngSelected(profileId, profileName)
    }

    private val RELATIVE = Regex("now([+-]\\d+)?")

    /** [element] with every `"now"`, `"now-<ms>"` and `"now+<ms>"` string replaced by `nowMs` plus the offset. */
    internal fun resolveTimes(element: JsonElement, nowMs: Long): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { resolveTimes(it.value, nowMs) })
        is JsonArray -> JsonArray(element.map { resolveTimes(it, nowMs) })
        is JsonPrimitive -> if (!element.isString) element else {
            RELATIVE.matchEntire(element.content)?.let { JsonPrimitive(nowMs + (it.groupValues[1].toLongOrNull() ?: 0L)) } ?: element
        }
    }

    private fun soulStats(spec: JsonObject?): SoulStatsAnswer {
        val status = spec?.num("status")?.toInt() ?: return SoulStatsAnswer(null, SoulStatsClient.CODE_UNAVAILABLE)
        if (status == 403) return SoulStatsAnswer(null, SoulStatsClient.CODE_NOT_SHARED)
        if (status == 404) return SoulStatsAnswer(null, SoulStatsClient.CODE_NOT_A_USER)
        if (status != 200) return SoulStatsAnswer(null, SoulStatsClient.CODE_UNAVAILABLE)
        val body = spec["body"] as? JsonObject ?: return SoulStatsAnswer(null, SoulStatsClient.CODE_NO_NAMESPACES)
        val envelope = json.decodeFromJsonElement(SoulStatsEnvelope.serializer(), body)
        return if (envelope.namespaces.isEmpty()) SoulStatsAnswer(null, SoulStatsClient.CODE_NO_NAMESPACES) else SoulStatsAnswer(envelope, SoulStatsClient.CODE_OK)
    }

    private fun caps(spec: JsonObject?) = spec?.let { SkillCapsClient.fromEnvelope(json.decodeFromJsonElement(SkillCapsEnvelope.serializer(), it)) }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.intOrNull?.toLong() }

    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true
}
