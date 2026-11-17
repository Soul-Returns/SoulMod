#!/usr/bin/env bash
# Upload a release jar to Modrinth as version <version>, with the release notes as changelog.
# Does nothing when Modrinth already has that version number, so it is safe to re-run.
#
# usage: MODRINTH_TOKEN=… modrinth-upload.sh <version> <jar> <notes.md>
#
# Environment:
#   MODRINTH_TOKEN          personal access token with the "Create versions" scope
#   MODRINTH_PROJECT        project id (default: SoulMod's)
#   MODRINTH_GAME_VERSIONS  space-separated Minecraft versions the jar runs on (default: 26.2)
#
# Pass only a jar that fetch-signed.sh verified: Modrinth downloads are not signature-checked by
# anyone, so this upload is where a wrong file would reach new players.
set -euo pipefail

version="${1:?usage: modrinth-upload.sh <version> <jar> <notes.md>}"
jar="${2:?usage: modrinth-upload.sh <version> <jar> <notes.md>}"
notes="${3:?usage: modrinth-upload.sh <version> <jar> <notes.md>}"
project="${MODRINTH_PROJECT:-x73d15rL}"
game_versions="${MODRINTH_GAME_VERSIONS:-26.2}"
api="https://api.modrinth.com/v2"
agent="Soul-Returns/SoulMod/$version (release workflow)"

die() { echo "error: $*" >&2; exit 1; }

[ -n "${MODRINTH_TOKEN:-}" ] || die "MODRINTH_TOKEN is not set"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || die "'$version' is not a release version"
[ -s "$jar" ] || die "no jar at $jar"
[ -s "$notes" ] || die "no release notes at $notes"

# The token goes through a config file on stdin, never the command line.
modrinth() {
    curl -sS --retry 5 --retry-all-errors --retry-delay 10 -A "$agent" \
        -K <(printf 'header = "Authorization: %s"\n' "$MODRINTH_TOKEN") "$@"
}

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

status="$(modrinth -o "$tmp/versions.json" -w '%{http_code}' "$api/project/$project/version")"
[ "$status" = 200 ] || die "listing versions of project $project answered HTTP $status: $(head -c 300 "$tmp/versions.json")"
if jq -e --arg v "$version" 'any(.[]; .version_number == $v)' "$tmp/versions.json" >/dev/null; then
    echo "Modrinth already has $version; nothing to upload"
    exit 0
fi

# `x.y.z-suffix` is the updater's Beta channel.
case "$version" in
    *-*) type=beta ;;
    *) type=release ;;
esac

# Fabric API and Fabric Language Kotlin must be installed; the Hypixel Mod API ships inside the jar.
jq -n --arg version "$version" --arg type "$type" --arg project "$project" \
    --rawfile changelog "$notes" --arg games "$game_versions" '{
        name: ("Soul " + $version),
        version_number: $version,
        changelog: $changelog,
        version_type: $type,
        project_id: $project,
        loaders: ["fabric"],
        game_versions: ($games | split(" ") | map(select(length > 0))),
        featured: false,
        dependencies: [
            {project_id: "P7dR8mSH", dependency_type: "required"},
            {project_id: "Ha28R6CL", dependency_type: "required"},
            {project_id: "1A2mKfBx", dependency_type: "embedded"}
        ],
        file_parts: ["file"],
        primary_file: "file"
    }' > "$tmp/data.json"

status="$(modrinth -o "$tmp/created.json" -w '%{http_code}' -X POST "$api/version" \
    -F "data=<$tmp/data.json;type=application/json" \
    -F "file=@$jar;type=application/java-archive;filename=soul-$version.jar")"
[ "$status" = 200 ] || die "creating version $version answered HTTP $status: $(head -c 600 "$tmp/created.json")"

# Modrinth hashes what it stored; it must be the file we sent.
want="$(sha512sum "$jar" | cut -d' ' -f1)"
have="$(jq -r '.files[0].hashes.sha512' "$tmp/created.json")"
[ "$have" = "$want" ] || die "Modrinth stored sha512 $have, the uploaded jar is $want"
echo "Uploaded soul-$version.jar to Modrinth as $type: version id $(jq -r .id "$tmp/created.json")"
