package com.soulreturns.platform.updater

import net.fabricmc.loader.api.Version
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signs fixtures with a throwaway Ed25519 key, the way `make sign` signs a release. */
class ReleaseVerifierTest {
    private val key: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val otherKey: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val trusted = listOf(spki(key))
    private val sha = "a".repeat(64)
    private val mc = Version.parse("26.2")

    private fun spki(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded)

    private fun sign(bytes: ByteArray, k: KeyPair = key): String =
        Base64.getEncoder().encodeToString(
            Signature.getInstance("Ed25519").run {
                initSign(k.private)
                update(bytes)
                sign()
            },
        )

    private fun manifest(
        version: String = "0.2.0",
        channel: String = if ('-' in version) "beta" else "stable",
        minecraft: String = "~26.2",
        file: String = "soul-$version.jar",
        size: Long = 3_238_626,
        sha256: String = sha,
        mod: String = "soul",
    ) = """{"mod":"$mod","version":"$version","channel":"$channel","minecraft":"$minecraft","depends":{"fabric-api":"*","minecraft":"$minecraft"},"file":"$file","size":$size,"sha256":"$sha256"}"""
        .toByteArray()

    private fun evaluate(
        bytes: ByteArray,
        signature: String = sign(bytes),
        offered: String = "0.2.0",
        running: String = "0.1.2",
        acceptBeta: Boolean = false,
        keys: List<String> = trusted,
    ) = ReleaseVerifier.evaluate(Base64.getEncoder().encodeToString(bytes), signature, offered, keys, running, acceptBeta, mc)

    private fun rejected(verdict: OfferVerdict): String = assertIs<OfferVerdict.Rejected>(verdict).reason

    @Test
    fun acceptsAManifestSignedByATrustedKey() {
        val bytes = manifest()
        val verdict = assertIs<OfferVerdict.Accepted>(evaluate(bytes))
        assertEquals("0.2.0", verdict.manifest.version)
        assertEquals(sha, verdict.manifest.sha256)
        assertTrue(bytes.contentEquals(verdict.manifestBytes))
    }

    @Test
    fun signatureFromAnUntrustedKeyIsRejected() {
        val bytes = manifest()
        assertTrue("signature" in rejected(evaluate(bytes, signature = sign(bytes, otherKey))))
    }

    @Test
    fun aSecondTrustedKeyVerifiesToo() {
        val bytes = manifest()
        assertIs<OfferVerdict.Accepted>(evaluate(bytes, signature = sign(bytes, otherKey), keys = listOf(spki(otherKey), spki(key))))
    }

    @Test
    fun anyChangedByteBreaksTheSignature() {
        val bytes = manifest()
        val signature = sign(bytes)
        val tampered = manifest(sha256 = "b".repeat(64))
        assertTrue("signature" in rejected(evaluate(tampered, signature = signature)))
    }

    @Test
    fun noTrustedKeyMeansNothingVerifies() {
        assertTrue("no trusted" in rejected(evaluate(manifest(), keys = emptyList())))
    }

    @Test
    fun garbageSignaturesAreRejected() {
        val bytes = manifest()
        assertTrue("base64" in rejected(evaluate(bytes, signature = "%%%")))
        assertTrue("signature" in rejected(evaluate(bytes, signature = Base64.getEncoder().encodeToString(ByteArray(10)))))
    }

    @Test
    fun theSignedFieldsMustMatchTheOffer() {
        assertTrue("signed 0.2.0" in rejected(evaluate(manifest(), offered = "0.3.0")))
        assertTrue("'other'" in rejected(evaluate(manifest(mod = "other"))))
        assertTrue("file name" in rejected(evaluate(manifest(file = "evil.jar"))))
        assertTrue("size" in rejected(evaluate(manifest(size = 0))))
        assertTrue("size" in rejected(evaluate(manifest(size = ReleaseVerifier.MAX_JAR_BYTES + 1))))
        assertTrue("sha256" in rejected(evaluate(manifest(sha256 = "XYZ"))))
    }

    @Test
    fun neverADowngradeOrTheSameVersion() {
        assertTrue("not newer" in rejected(evaluate(manifest(), running = "0.2.0")))
        assertTrue("not newer" in rejected(evaluate(manifest(), running = "0.3.0")))
        assertTrue("SemVer" in rejected(evaluate(manifest(), running = "Unknown")))
    }

    @Test
    fun betasOnlyOnTheBetaChannel() {
        val beta = manifest(version = "0.2.0-beta1")
        assertTrue("Stable" in rejected(evaluate(beta, offered = "0.2.0-beta1")))
        assertIs<OfferVerdict.Accepted>(evaluate(beta, offered = "0.2.0-beta1", acceptBeta = true))
        // Beta also takes newer stable releases.
        assertIs<OfferVerdict.Accepted>(evaluate(manifest(), acceptBeta = true, running = "0.2.0-beta1"))
    }

    @Test
    fun channelMustFitTheVersion() {
        assertTrue("channel" in rejected(evaluate(manifest(channel = "beta"), acceptBeta = true)))
        assertTrue("channel" in rejected(evaluate(manifest(version = "0.2.0-beta1", channel = "stable"), offered = "0.2.0-beta1")))
    }

    @Test
    fun minecraftRangeIsChecked() {
        assertTrue("Minecraft ~26.3" in rejected(evaluate(manifest(minecraft = "~26.3"))))
        assertIs<OfferVerdict.Accepted>(evaluate(manifest(minecraft = ">=26.2 <26.3")))
    }

    @Test
    fun downloadedJarMustMatchTheManifest() {
        val m = assertIs<OfferVerdict.Accepted>(evaluate(manifest())).manifest
        val meta = JarMetadata("soul", "0.2.0", mapOf("fabric-api" to listOf("*")), emptyMap(), emptyMap())
        val installed = { id: String -> if (id == "fabric-api") Version.parse("0.153.0+26.2") else null }

        fun problem(size: Long = m.size, hash: String = sha, jar: JarMetadata? = meta) =
            assertNotNull(ReleaseVerifier.checkJar(m, size, hash, jar, installed))

        assertNull(ReleaseVerifier.checkJar(m, m.size, sha, meta, installed))
        for (p in listOf(
            problem(size = m.size - 1) to "size",
            problem(hash = "c".repeat(64)) to "sha256",
            problem(jar = null) to "not a Fabric mod",
            problem(jar = meta.copy(id = "evil")) to "'evil'",
            problem(jar = meta.copy(version = "0.1.9")) to "0.1.9",
        )) {
            assertEquals(UpdateError.VERIFY, p.first.kind)
            assertTrue(p.second in p.first.reason)
        }
        val tooNew = problem(jar = meta.copy(depends = mapOf("fabric-api" to listOf(">=0.160.0"))))
        assertEquals(UpdateError.INCOMPATIBLE, tooNew.kind)
        assertTrue("Fabric API" in tooNew.reason)
    }
}
