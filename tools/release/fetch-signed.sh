#!/usr/bin/env bash
# Fetch the published release <version> from the backend and prove it is the maintainer's:
# the manifest signature must verify against a key in TrustedKeys.kt, and the jar must match the
# signed size and sha256. Writes soul-<version>.jar, .manifest.json and .manifest.sig to <out-dir>.
#
# usage: fetch-signed.sh <version> <out-dir> [backend-url]
#
# Used by .github/workflows/release.yml so the GitHub release carries the exact jar players get.
# Needs only public data: no credentials, no signing key.
set -euo pipefail

version="${1:?usage: fetch-signed.sh <version> <out-dir> [backend-url]}"
out="${2:?usage: fetch-signed.sh <version> <out-dir> [backend-url]}"
backend="${3:-https://sky.soulreturns.com}"
keys_file="src/main/kotlin/com/soulreturns/platform/updater/TrustedKeys.kt"

die() { echo "error: $*" >&2; exit 1; }

mkdir -p "$out"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Everything that fails is retried: a blip must not cost a release. That includes 404, which a
# backend in the middle of a deploy also answers; only a 404 that persists means "not published".
for attempt in 1 2 3 4 5 6; do
    status="$(curl -sS -o "$tmp/release.json" -w '%{http_code}' --retry 5 --retry-all-errors --retry-delay 10 \
        "$backend/mod/update/$version/manifest")"
    [ "$status" != 404 ] && break
    [ "$attempt" = 6 ] || sleep 10
done
case "$status" in
    200) ;;
    404) die "the backend has no release $version. Run 'make publish V=$version', then re-run this job" ;;
    410) die "release $version is revoked on the backend" ;;
    *) die "$backend/mod/update/$version/manifest answered HTTP $status" ;;
esac

manifest="$out/soul-$version.manifest.json"
signature="$out/soul-$version.manifest.sig"
jq -er '.manifest' "$tmp/release.json" | base64 -d > "$manifest"
jq -er '.signature' "$tmp/release.json" | base64 -d > "$signature"

# The signature covers the exact manifest bytes; only a key the mod itself trusts counts.
mapfile -t keys < <(grep -oE '"MCowBQYDK2VwAyEA[A-Za-z0-9+/]+=*"' "$keys_file" | tr -d '"')
[ "${#keys[@]}" -gt 0 ] || die "no trusted keys in $keys_file"
verified=""
for key in "${keys[@]}"; do
    printf '%s' "$key" | base64 -d > "$tmp/key.der"
    if openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey "$tmp/key.der" \
        -in "$manifest" -sigfile "$signature" >/dev/null 2>&1; then
        verified="$key"
        break
    fi
done
[ -n "$verified" ] || die "the manifest of $version is not signed by a key in $keys_file"

# Only signed fields from here on.
field() { jq -er "$1" "$manifest"; }
[ "$(field .mod)" = "soul" ] || die "manifest is for mod '$(field .mod)'"
[ "$(field .version)" = "$version" ] || die "manifest is for version '$(field .version)', not $version"
[ "$(field .file)" = "soul-$version.jar" ] || die "manifest names file '$(field .file)'"
want_sha="$(field .sha256)"
want_size="$(field .size)"

jar="$out/soul-$version.jar"
curl -fsS --retry 5 --retry-all-errors --retry-delay 10 -o "$jar" "$backend/mod/update/$version/jar"
have_size="$(stat -c %s "$jar")"
have_sha="$(sha256sum "$jar" | cut -d' ' -f1)"
[ "$have_size" = "$want_size" ] || die "jar is $have_size bytes, the signed manifest says $want_size"
[ "$have_sha" = "$want_sha" ] || die "jar sha256 is $have_sha, the signed manifest says $want_sha"

echo "Verified soul-$version.jar (sha256 $have_sha) against trusted key $verified"
