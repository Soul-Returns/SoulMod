package com.soulreturns.platform.updater

/**
 * Release signing keys this build trusts: base64 of the 44-byte X.509 SPKI DER of an Ed25519 public
 * key (`make keygen` prints it; it always starts with `MCowBQYDK2VwAyEA`). The private half lives
 * only in the maintainer's 1Password; neither GitHub nor the backend holds it, so a compromise of
 * either can withhold updates but never ship code. `make sign` refuses a key that is not listed here.
 * Rotation: a release signed by an old key adds the new one here. The backend's
 * `MOD_RELEASE_PUBLIC_KEYS` lists the same keys.
 *
 * Empty = this build verifies nothing and therefore installs nothing (check + notify only).
 */
object TrustedKeys {
    val keys: List<String> = listOf(
        // Release key 1 (2026-11-10), private half: op://Personal/SoulMod release signing key/private key
        "MCowBQYDK2VwAyEAPzM48YSLX11DPY6dFx2HhfubZ6AJdLQt7kBm30m3JRc=",
    )
}
