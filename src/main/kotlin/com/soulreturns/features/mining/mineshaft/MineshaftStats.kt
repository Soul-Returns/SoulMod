package com.soulreturns.features.mining.mineshaft

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * The `/mineshaft/stats` body — the backend's aggregate over this player's logged visits.
 *
 * The same shape arrives twice: on its own from `GET /mineshaft/stats` (your own, always) and as the
 * `mineshaft` namespace of `GET /player/{uuid}/soul-stats` (someone else's, gated on their opt-in).
 * The backend computes both from one service, so this is one type here too — [from] decodes it out of
 * whichever envelope it came in.
 *
 * **The contract is additive-only**, so every field has a default and unknown keys are ignored: a
 * backend that grows a metric must never break a client that has not shipped yet.
 *
 * The nested breakdowns keep the bucket key as a plain map. `byType` and `byInstance` share a shape
 * that carries a nested `corpses` map; `byCorpse` and `bySource` are flat. **Bucket keys are never
 * validated** — mineshaft types, instances and corpse names are open sets on both sides.
 *
 * Every one of those maps is read through [EmptyArrayAsMap]. A default of `emptyMap()` only covers an
 * **absent** key; a key present as `[]` throws and takes the whole body with it. That is not
 * hypothetical — PHP encodes an empty associative array as a JSON array, so a corpse-less mineshaft
 * and a player with no visits both produced `[]` here until the backend started normalising them,
 * which is to say the two most ordinary states in the feature. The backend guarantees `{}` now and has
 * a test on the raw bytes; this stays because the failure is silent (a section quietly degrades to the
 * generic renderer) and the guarantee lives in another repo.
 */
@Serializable
data class MineshaftStats(
    val totalVisits: Int = 0,
    val totalDurationSec: Long = 0,
    val averageDurationSec: Double = 0.0,
    val littlefootFound: Int = 0,
    val littlefootFindRate: Double = 0.0,
    @Serializable(with = BucketMap::class) val byType: Map<String, BucketStats> = emptyMap(),
    @Serializable(with = BucketMap::class) val byInstance: Map<String, BucketStats> = emptyMap(),
    @Serializable(with = CorpseMap::class) val byCorpse: Map<String, CorpseStats> = emptyMap(),
    @Serializable(with = SourceMap::class) val bySource: Map<String, SourceStats> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Decode a `mineshaft` namespace payload, or null if it is not one. */
        fun from(payload: JsonObject): MineshaftStats? =
            try {
                json.decodeFromJsonElement(serializer(), payload)
            } catch (_: Throwable) {
                null
            }
    }
}

/** One `byType` / `byInstance` bucket. */
@Serializable
data class BucketStats(
    val visits: Int = 0,
    val durationSec: Long = 0,
    val averageDurationSec: Double = 0.0,
    val littlefootFound: Int = 0,
    val littlefootRate: Double = 0.0,
    @Serializable(with = CorpseMap::class) val corpses: Map<String, CorpseStats> = emptyMap(),
)

/**
 * Reads a JSON **array** where a map is expected as an empty map, and passes anything else straight
 * through. See [MineshaftStats]' KDoc for why that is a real wire state and not paranoia.
 */
private abstract class EmptyArrayAsMap<V>(value: KSerializer<V>) :
    JsonTransformingSerializer<Map<String, V>>(MapSerializer(String.serializer(), value)) {
    final override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element is JsonArray) JsonObject(emptyMap()) else element
}

private object BucketMap : EmptyArrayAsMap<BucketStats>(BucketStats.serializer())

private object CorpseMap : EmptyArrayAsMap<CorpseStats>(CorpseStats.serializer())

private object SourceMap : EmptyArrayAsMap<SourceStats>(SourceStats.serializer())

/**
 * One corpse type's numbers within a bucket.
 *
 * @property spawnedIn visits where this corpse appeared at all (`found > 0`)
 * @property spawnRate [spawnedIn] over the bucket's visits
 * @property avgFoundWhenSpawned [found] over [spawnedIn] — how many there tend to be when there are any
 * @property lootRate [looted] over [found]
 */
@Serializable
data class CorpseStats(
    val found: Int = 0,
    val looted: Int = 0,
    val lootRate: Double = 0.0,
    val spawnedIn: Int = 0,
    val spawnRate: Double = 0.0,
    val avgFoundWhenSpawned: Double = 0.0,
)

/** One `bySource` bucket: `discovered`, `warped` or `unknown`. */
@Serializable
data class SourceStats(
    val visits: Int = 0,
    val littlefootFound: Int = 0,
    val totalDurationSec: Long = 0,
)

/**
 * `GET /mineshaft/stats` — **your own** lifetime mineshaft numbers, with no sharing check, because it
 * is scoped to the bearer rather than to a uuid.
 *
 * Everyone else's copy of the same numbers arrives through
 * [SoulStatsClient][com.soulreturns.data.hypixel.SoulStatsClient] instead, gated on their opt-in.
 * Two surfaces, one aggregation — this client does not compute anything.
 *
 * The backend caches it for 30 s (`X-Backend-Expire-In: 30000`), which [BackendClient] honours, so
 * `/soul mineshaft` in quick succession costs one request.
 */
object MineshaftStatsClient {
    const val INTENT = "mineshaft-stats"

    /**
     * The result rather than a nullable: `/soul mineshaft` is a command the player typed, so "the
     * backend said 429" and "you have never logged a visit" have to read differently on screen.
     */
    suspend fun stats(): BackendResult<MineshaftStats> =
        BackendClient.get("/mineshaft/stats", MineshaftStats.serializer(), intent = INTENT)
}
