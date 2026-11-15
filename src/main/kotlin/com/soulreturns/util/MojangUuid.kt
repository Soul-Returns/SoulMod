package com.soulreturns.util

import java.util.UUID

/**
 * The mod's **only** pair of uuid form converters.
 *
 * Mojang and Hypixel write uuids as bare 32-char hex (`706c56af…`); `java.util.UUID.fromString`
 * accepts only the dashed form and throws on the other. That mismatch has bitten this codebase from
 * both directions — Hypixel's `/skyblock/profiles` wants undashed and keys `members` by it, while
 * the Soul backend stamps dungeon-sync senders undashed into a field we parse back into a `UUID`.
 * Both directions live here so there is one thing to get right.
 */
object MojangUuid {
    private val HEX_32 = Regex("[0-9a-fA-F]{32}")

    /** `706c56af-…` → `706c56af…`, the form Hypixel's endpoints require. */
    fun undashed(uuid: UUID): String = uuid.toString().replace("-", "")

    /** Parse either form; null when [raw] is neither (rather than throwing at the call site). */
    fun parse(raw: String?): UUID? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return try {
            if (s.length == 32 && '-' !in s) UUID.fromString(dash(s)) else UUID.fromString(s)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * Normalize either form to lowercase 32-hex, or null if it is not a uuid. Use this on anything
     * inbound before putting it in a URL — it validates as a side effect, so a malformed name can
     * never be smuggled into a query string.
     */
    fun undashedOrNull(raw: String?): String? = parse(raw)?.let(::undashed)

    /** True when [raw] is already exactly 32 hex characters. */
    fun isUndashed(raw: String): Boolean = HEX_32.matches(raw)

    private fun dash(hex32: String): String =
        "${hex32.substring(0, 8)}-${hex32.substring(8, 12)}-${hex32.substring(12, 16)}-" +
            "${hex32.substring(16, 20)}-${hex32.substring(20)}"
}
