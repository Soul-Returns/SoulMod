package com.soulreturns.platform.http

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleFlightTest {
    private val callers = 16

    private class Harness(
        val cache: AtomicReference<String?> = AtomicReference(null),
        val blocked: Boolean = false,
    ) {
        val handshakes = AtomicInteger()
        val minted = AtomicInteger()
        val flight = SingleFlight<String>({ cache.get() }, { blocked })
    }

    /** Runs [callers] threads released together; returns each caller's result. */
    private fun race(h: Harness, skipCache: Boolean = false, handshake: () -> String?): List<String?> {
        val pool = Executors.newFixedThreadPool(callers)
        try {
            val ready = CountDownLatch(callers)
            val go = CountDownLatch(1)
            val futures = (0 until callers).map {
                pool.submit<String?> {
                    ready.countDown()
                    go.await()
                    h.flight.run(
                        skipCache = skipCache,
                        bypassCooldown = false,
                        onMinted = { h.minted.incrementAndGet() },
                        handshake = handshake,
                    )
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            return futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentCallersShareOneHandshake() {
        val h = Harness()
        val results = race(h) {
            h.handshakes.incrementAndGet()
            Thread.sleep(200)
            "token".also { h.cache.set(it) }
        }
        assertEquals(1, h.handshakes.get())
        assertEquals(1, h.minted.get())
        assertEquals(List(callers) { "token" }, results)
    }

    @Test
    fun failedHandshakeIsNotRetriedByTheWaiters() {
        val h = Harness()
        val results = race(h) {
            h.handshakes.incrementAndGet()
            Thread.sleep(200)
            null
        }
        assertEquals(1, h.handshakes.get())
        assertEquals(0, h.minted.get())
        assertTrue(results.all { it == null })
    }

    @Test
    fun cachedValueRunsNoHandshake() {
        val h = Harness(AtomicReference("cached"))
        val results = race(h) {
            h.handshakes.incrementAndGet()
            "other"
        }
        assertEquals(0, h.handshakes.get())
        assertEquals(List(callers) { "cached" }, results)
    }

    @Test
    fun forcedCallersStillShareOneHandshake() {
        val h = Harness(AtomicReference("stale"))
        val results = race(h, skipCache = true) {
            h.handshakes.incrementAndGet()
            Thread.sleep(200)
            "fresh".also { h.cache.set(it) }
        }
        assertEquals(1, h.handshakes.get())
        assertEquals(List(callers) { "fresh" }, results)
    }

    @Test
    fun cooldownBlocksTheHandshake() {
        val h = Harness(blocked = true)
        val results = race(h) {
            h.handshakes.incrementAndGet()
            "token"
        }
        assertEquals(0, h.handshakes.get())
        assertTrue(results.all { it == null })
    }

    @Test
    fun throwingHandshakeReleasesTheLock() {
        val h = Harness()
        runCatching {
            h.flight.run(skipCache = false, bypassCooldown = false, onMinted = {}) { error("boom") }
        }
        val again = h.flight.run(skipCache = false, bypassCooldown = false, onMinted = {}) { "ok" }
        assertEquals("ok", again)
    }

    @Test
    fun recognisesMojangRateLimit() {
        val cause = RuntimeException(
            "MinecraftClientHttpException[type=HTTP_ERROR, status=429, response=ErrorResponse[path=/session/minecraft/join, error=RateLimiter disallowed request]]",
        )
        assertTrue(isMojangRateLimit(Exception("RateLimiter disallowed request", cause)))
        assertTrue(isMojangRateLimit(Exception("wrapper", cause)))
        assertTrue(!isMojangRateLimit(Exception("Invalid session")))
    }
}
