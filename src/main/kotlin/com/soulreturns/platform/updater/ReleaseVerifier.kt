package com.soulreturns.platform.updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.fabricmc.loader.api.SemanticVersion
import net.fabricmc.loader.api.Version
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The signed release manifest (backend contract, research doc §API). Only these fields are trusted;
 * everything else in the check response is a hint. Signed as exact bytes, never re-serialized.
 */
@Serializable
data class ReleaseManifest(
    val mod: String,
    val version: String,
    val channel: String,
    val minecraft: String,
    val depends: JsonObject = JsonObject(emptyMap()),
    val file: String,
    val size: Long,
    val sha256: String,
)

/** Ed25519 over the exact manifest bytes, JDK only (`SunEC`, present in Mojang's runtimes too). */
object ReleaseSignature {
    fun verify(message: ByteArray, signature: ByteArray, trustedKeys: List<String>): Boolean {
        if (signature.size != 64) return false
        return trustedKeys.any { key ->
            try {
                val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
                Signature.getInstance("Ed25519").run {
                    initVerify(publicKey)
                    update(message)
                    verify(signature)
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

/** Verdict on one offered release. Match with `is`, never a subject `when` (rule://mc-api). */
sealed interface OfferVerdict {
    data class Accepted(val manifest: ReleaseManifest, val manifestBytes: ByteArray, val version: SemanticVersion) : OfferVerdict

    data class Rejected(val reason: String) : OfferVerdict
}

/**
 * Pure checks on an offer from `GET /mod/update` before anything is downloaded: signature by a
 * trusted key, then every trusted field of the manifest against what this client runs.
 */
object ReleaseVerifier {
    const val MOD_ID = "soul"

    /** Far above any real Soul jar (~3 MB); bounds what a hostile manifest can make us download. */
    const val MAX_JAR_BYTES = 64L * 1024 * 1024

    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param offeredVersion `latest.version` from the response; must equal the signed one.
     * @param running the running Soul version.
     * @param acceptBeta the player's channel is Beta (Beta also takes newer stable releases).
     * @param minecraft the running Minecraft version, null to skip that check (tests).
     */
    fun evaluate(
        manifestBase64: String,
        signatureBase64: String,
        offeredVersion: String,
        trustedKeys: List<String>,
        running: String,
        acceptBeta: Boolean,
        minecraft: Version?,
    ): OfferVerdict {
        if (trustedKeys.isEmpty()) return OfferVerdict.Rejected("this build has no trusted release key")
        val bytes = decode(manifestBase64) ?: return OfferVerdict.Rejected("manifest is not base64")
        val signature = decode(signatureBase64) ?: return OfferVerdict.Rejected("signature is not base64")
        if (!ReleaseSignature.verify(bytes, signature, trustedKeys)) return OfferVerdict.Rejected("signature does not verify")

        val manifest = try {
            json.decodeFromString(ReleaseManifest.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            return OfferVerdict.Rejected("manifest does not parse: ${e.message}")
        }
        if (manifest.mod != MOD_ID) return OfferVerdict.Rejected("manifest is for '${manifest.mod}'")
        val version = ReleaseVersions.parse(manifest.version) ?: return OfferVerdict.Rejected("manifest version '${manifest.version}' is not SemVer")
        if (manifest.version != offeredVersion) return OfferVerdict.Rejected("offered ${offeredVersion} but signed ${manifest.version}")
        if (manifest.channel != ReleaseVersions.channelOf(version)) return OfferVerdict.Rejected("channel '${manifest.channel}' does not fit ${manifest.version}")
        if (manifest.channel == ReleaseVersions.BETA && !acceptBeta) return OfferVerdict.Rejected("${manifest.version} is a beta and the channel is Stable")
        val current = ReleaseVersions.parse(running) ?: return OfferVerdict.Rejected("running version '$running' is not SemVer")
        if (version <= current) return OfferVerdict.Rejected("${manifest.version} is not newer than $running")
        if (manifest.file != "$MOD_ID-${manifest.version}.jar") return OfferVerdict.Rejected("unexpected file name '${manifest.file}'")
        if (manifest.size !in 1..MAX_JAR_BYTES) return OfferVerdict.Rejected("implausible size ${manifest.size}")
        if (!SHA256_HEX.matches(manifest.sha256)) return OfferVerdict.Rejected("sha256 is not 64 lowercase hex")
        if (minecraft != null && !DependencyGuard.matches(manifest.minecraft, minecraft)) {
            return OfferVerdict.Rejected("${manifest.version} needs Minecraft ${manifest.minecraft}")
        }
        return OfferVerdict.Accepted(manifest, bytes, version)
    }

    /**
     * Checks a downloaded jar against its accepted manifest: size, sha256 (computed while
     * streaming), its own `fabric.mod.json` id/version, and its dependencies against the loader.
     *
     * @return null when the jar may be installed, else the [UpdateError] kind and reason it may not.
     */
    fun checkJar(
        manifest: ReleaseManifest,
        size: Long,
        sha256: String,
        metadata: JarMetadata?,
        installed: (String) -> Version?,
    ): JarProblem? {
        fun verify(reason: String) = JarProblem(UpdateError.VERIFY, reason)
        if (size != manifest.size) return verify("size $size, signed ${manifest.size}")
        if (!sha256.equals(manifest.sha256, ignoreCase = true)) return verify("sha256 does not match the signed manifest")
        if (metadata == null) return verify("the download is not a Fabric mod jar")
        if (metadata.id != MOD_ID) return verify("the jar's mod id is '${metadata.id}'")
        if (metadata.version != manifest.version) return verify("the jar says ${metadata.version}, signed ${manifest.version}")
        val problems = DependencyGuard.check(metadata, installed)
        if (problems.isNotEmpty()) return JarProblem(UpdateError.INCOMPATIBLE, "Soul ${manifest.version} " + problems.joinToString("; ") { it.describe() })
        return null
    }

    private fun decode(text: String): ByteArray? =
        try {
            Base64.getDecoder().decode(text.trim())
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** Why a downloaded jar may not be installed: [kind] is an [UpdateError] constant. */
data class JarProblem(val kind: String, val reason: String)
