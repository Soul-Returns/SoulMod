#!/usr/bin/env bash
# Build the release jar for tag v<version> from a clean, temporary `git worktree` (never the
# working tree, so local edits and untracked files cannot leak into a signed release).
#
# usage: build-jar.sh <version> <out-jar>
#
# Same recipe as .github/workflows/release.yml: JDK 25, `./gradlew build -Pmod_version=<version>`.
# JAVA25_HOME overrides the JDK location. The worktree lives under ~/.cache/soulmod/ (disk, not
# tmpfs; Loom unpacks a Minecraft jar per fresh checkout, so the first build takes a few minutes)
# and is removed on exit.
set -euo pipefail

version="${1:?usage: build-jar.sh <version> <out-jar>}"
out="${2:?usage: build-jar.sh <version> <out-jar>}"
tag="v$version"

die() { echo "error: $*" >&2; exit 1; }

git rev-parse -q --verify "refs/tags/$tag^{commit}" >/dev/null \
    || die "tag $tag does not exist locally; run 'make bump V=$version' (or 'git fetch --tags') first"
commit="$(git rev-parse "refs/tags/$tag^{commit}")"

jdk="${JAVA25_HOME:-/usr/lib/jvm/java-25-openjdk}"
[ -x "$jdk/bin/java" ] || die "no JDK at $jdk (install jdk25-openjdk or set JAVA25_HOME)"
"$jdk/bin/java" -version 2>&1 | grep -q 'version "25[."]' \
    || die "$jdk is not a JDK 25 ($("$jdk/bin/java" -version 2>&1 | head -1))"

root="${XDG_CACHE_HOME:-$HOME/.cache}/soulmod/release-build"
mkdir -p "$root"
git worktree prune
wt="$(mktemp -d "$root/$tag.XXXXXX")"
cleanup() {
    git worktree remove --force "$wt" >/dev/null 2>&1 || true
    rm -rf "$wt"
    git worktree prune
}
trap cleanup EXIT

echo "Building $tag ($commit) in a temporary worktree with $("$jdk/bin/java" -version 2>&1 | head -1)"
git worktree add --detach -q "$wt" "$commit"
(
    cd "$wt"
    JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH" ./gradlew build "-Pmod_version=$version" --no-daemon --console=plain -q
)

built="$wt/build/libs/soul-$version.jar"
[ -f "$built" ] || die "expected $built; got: $(ls "$wt/build/libs" 2>&1)"
mkdir -p "$(dirname "$out")"
cp "$built" "$out"
echo "Built $out (sha256 $(sha256sum "$out" | cut -d' ' -f1))"
