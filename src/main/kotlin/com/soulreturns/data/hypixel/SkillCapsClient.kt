package com.soulreturns.data.hypixel

import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MojangUuid
import kotlinx.serialization.Serializable

/**
 * `GET /player/{uuid}/skyblock-caps` — the account-specific skill level caps.
 *
 * ### Two skills cap below the curve Hypixel publishes
 * - **Taming**: `50 +` the number of **unique pet types sacrificed to Kat**, i.e.
 *   `members[<uuid>].pets_data.pet_care.pet_types_sacrificed`, capped at 10.
 * - **Farming**: `50 + jacobs_contest.perks.farming_level_cap`.
 *
 * Both are **per profile** — a bingo profile and a main profile on one account really do differ — and
 * both are derivable from the `/skyblock/profiles` payload this mod already holds.
 *
 * ### Why ask the backend for something we could compute
 * Because the formula, not the data, is the part that goes wrong: this endpoint was first specified
 * against `player.achievements.skyblock_hidden_talent`, **a key that does not exist**, which would
 * have reported 50 for every player alive. A formula with one implementation server-side can be
 * corrected without shipping a mod. So the backend owns both caps and the mod deliberately keeps no
 * copy of either — there is no second implementation here to drift from it.
 *
 * ### Every failure is silence
 * [caps] returns null for a 404, a 429, an unreachable backend, and for a backend too old to have the
 * route. The consumer then levels against the curve's published cap, which over-states an account
 * with the XP but not the unlock — the behaviour the mod had before this existed. That is a visible
 * degradation rather than a wrong-looking error, and it is the safe direction: nothing to explain to a
 * user who did nothing wrong.
 */
object SkillCapsClient {
    const val INTENT = "spv-skill-caps"

    /** Skill keys are [com.soulreturns.data.hypixel.calc.SkillDef.key], lowercase. */
    const val TAMING = "taming"
    const val FARMING = "farming"

    /** Hypixel's floor and ceiling for both caps. Clamped here as well as server-side. */
    private const val MIN_CAP = 50
    private const val MAX_CAP = 60

    /** Why the last call returned what it did — for `/soul dev`, since failures are invisible. */
    @Volatile
    var lastOutcome: String = "not called yet"
        private set

    /**
     * Fetch every profile's caps for [uuidUndashed] in one request.
     *
     * Asking for all profiles at once is deliberate: the Profile Viewer switches profile client-side
     * without refetching, so a per-profile endpoint would cost a round trip per switch. The backend
     * computes one snapshot covering every profile anyway.
     */
    suspend fun caps(uuidUndashed: String): PlayerSkillCaps? {
        if (!MojangUuid.isUndashed(uuidUndashed)) {
            lastOutcome = "not attempted: '$uuidUndashed' is not a 32-hex uuid"
            return null
        }

        val result = BackendClient.get(
            endpoint = "/player/$uuidUndashed/skyblock-caps",
            serializer = SkillCapsEnvelope.serializer(),
            intent = INTENT,
        )
        // `if (result is …)` — never `when (result)` over a sealed type (the $WhenMappings trap).
        if (result !is BackendResult.Ok) {
            val failure = result as BackendResult.Failure
            lastOutcome = when (failure.status) {
                404 -> "no caps: Hypixel has no such player"
                429 -> "no caps: rate limited (${failure.retryAfter?.seconds ?: "?"}s)"
                // A backend without the route answers 404/405 — indistinguishable from, and treated
                // exactly like, any other "cannot tell". That is what makes this additive.
                else -> "no caps: ${failure.message}"
            }
            return null
        }

        val envelope = result.value
        val cached = if (result.fromCache) " (cached)" else ""
        val parsed = fromEnvelope(envelope)
        lastOutcome = when {
            parsed == null -> "no caps published (schemaVersion=${envelope.schemaVersion})$cached"
            envelope.profiles.isNotEmpty() -> "ok: ${envelope.profiles.size} profile(s) · $parsed$cached"
            else -> "ok (v1 envelope, selected profile only): $parsed$cached"
        }
        return parsed
    }

    /**
     * The caps an envelope speaks for, or null when it publishes none. v2 (per profile id) wins; a v1
     * flat map belongs to the SELECTED profile only — applying it to any other profile would be the
     * bug this contract grew a profile dimension to avoid (a bingo profile wearing a main profile's 60).
     */
    fun fromEnvelope(envelope: SkillCapsEnvelope): PlayerSkillCaps? = when {
        envelope.profiles.isNotEmpty() -> PlayerSkillCaps(envelope.profiles.mapValues { (_, caps) -> SkillCaps(clamp(caps)) }, selectedOnly = null)
        envelope.skillCaps.isNotEmpty() -> PlayerSkillCaps(emptyMap(), selectedOnly = SkillCaps(clamp(envelope.skillCaps)))
        else -> null
    }

    private fun clamp(caps: Map<String, Int>): Map<String, Int> =
        caps.mapValues { (_, cap) -> cap.coerceIn(MIN_CAP, MAX_CAP) }
}

/**
 * One player's caps across every profile.
 *
 * @property selectedOnly set only for a v1 backend, which answered for the player's selected profile
 *   without saying which that was. It is used for the selected profile and nothing else.
 */
class PlayerSkillCaps(
    byProfile: Map<String, SkillCaps>,
    private val selectedOnly: SkillCaps?,
) {
    /**
     * Keyed by the **normalised** profile id, and looked up the same way.
     *
     * Hypixel emits `profile_id` **dashed** in `/skyblock/profiles`, and the backend keys this map by
     * the undashed form. Matching the two raw strings therefore misses every profile — and misses
     * *silently*, because a missing cap is a legitimate answer here: it degrades to the published
     * curve, so Taming just reads 60 for everyone and nothing anywhere reports a problem. Normalising
     * both sides makes it work whichever form either end sends.
     */
    private val byProfile: Map<String, SkillCaps> = byProfile.mapKeys { (id, _) -> normalize(id) }

    /**
     * The caps for one profile, or null when this response cannot speak for it — in which case the
     * caller keeps the curve's published cap rather than borrowing another profile's number.
     *
     * @param isSelected whether [profileId] is the profile Hypixel flags `selected`, which is the only
     *   profile a v1 [selectedOnly] answer may be applied to.
     */
    fun forProfile(profileId: String, isSelected: Boolean): SkillCaps? =
        byProfile[normalize(profileId)] ?: selectedOnly?.takeIf { isSelected }

    /** Every profile this response speaks for, normalised — for `/soul dev caps`. */
    val profileIds: Set<String> get() = byProfile.keys

    override fun toString(): String =
        if (byProfile.isNotEmpty()) {
            byProfile.entries.joinToString(", ") { "${it.key.take(8)}=${it.value}" }
        } else {
            "selected=${selectedOnly ?: "-"}"
        }

    private companion object {
        /** Dashed or undashed, either end, one form. Anything unparseable keeps its own shape. */
        fun normalize(id: String): String = MojangUuid.undashedOrNull(id) ?: id.lowercase()
    }
}

/**
 * The caps for one profile, keyed by [com.soulreturns.data.hypixel.calc.SkillDef.key].
 *
 * A key that is absent means "we do not know", **not** "no cap" — so [capFor] returns null and the
 * caller keeps whatever the published curve says.
 */
class SkillCaps(private val byKey: Map<String, Int>) {
    fun capFor(skillKey: String): Int? = byKey[skillKey]

    val keys: Set<String> get() = byKey.keys

    override fun toString(): String = byKey.entries.joinToString("/") { "${it.key.take(4)}${it.value}" }
}

/**
 * The `skyblock-caps` envelope, in both shapes it has had.
 *
 * @property schemaVersion 2 carries [profiles]; 1 carried [skillCaps]. Adding a *skill* to either is
 *   additive and does not move it. Both fields are declared so a mod build spans a backend deploy in
 *   either direction — decoded with `ignoreUnknownKeys`, so a third shape is inert rather than fatal.
 * @property profiles profile id → (skill key → cap). The v2 shape, preferred.
 * @property skillCaps the v1 shape: one profile's caps, the selected one, unlabelled.
 */
@Serializable
data class SkillCapsEnvelope(
    val schemaVersion: Int = 1,
    val profiles: Map<String, Map<String, Int>> = emptyMap(),
    val skillCaps: Map<String, Int> = emptyMap(),
)
