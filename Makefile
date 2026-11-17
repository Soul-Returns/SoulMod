# Release helpers. Everything else goes through ./gradlew.
#
# The one command that matters:
#
#     make bump V=0.2.0
#
# It rewrites the version in the files that carry it, commits that, and tags the commit
# `v0.2.0`. Pushing is left to you. Releasing to players needs no GitHub: `make publish V=0.2.0`
# builds the tag locally (see "auto updater releases" below). A pushed tag still fires
# .github/workflows/release.yml, which publishes the GitHub release with the same notes file.
#
# Every release needs its player-facing notes first (skill `release-notes`): the committed file
# release-notes/<V>.md and the What's-new entry Releases.notes["<V>"] in MenuState.kt. `bump`
# refuses without them, and `publish` uploads the file as it exists at the tag.

SHELL := /bin/bash

# Every file that hardcodes the mod version. fabric.mod.json is NOT one of them: it carries
# `${version}` and is interpolated by processResources from gradle.properties.
VERSION_FILE := gradle.properties
NOTES_KT := src/main/kotlin/com/soulreturns/config/gui/menu/MenuState.kt

CURRENT_VERSION := $(shell sed -n 's/^mod_version=//p' $(VERSION_FILE))

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "Current version: $(CURRENT_VERSION)"
	@echo
	@echo "  make bump V=<x.y.z>   bump the version, commit it, tag the commit v<x.y.z> (needs the release notes, see skill release-notes)"
	@echo "  make version          print the current version"
	@echo "  make keygen           create the release signing key in your 1Password (once)"
	@echo "  make sign V=<x.y.z>   build tag v<x.y.z> locally (temporary git worktree, JDK 25), write + sign its manifest"
	@echo "  make notes V=<x.y.z>  write the changelog from release-notes/<x.y.z>.md as committed at the tag"
	@echo "  make publish V=<x.y.z> [ROLLOUT=100]   check the notes, sign, then upload to the backend"
	@echo
	@echo "  Options for sign/publish (all optional; append them, e.g. make publish V=0.2.1 DRY_RUN=1):"
	@echo "    GITHUB=1       use the CI release's jar (digest-checked) instead of a local build; needs the"
	@echo "                   tag pushed and its workflow finished first"
	@echo "    DRY_RUN=1      build + sign, stop before the upload (publish only)"
	@echo "    JAR=<path>     sign this jar instead of building (its fabric.mod.json must say <x.y.z>)"
	@echo "    CHANGELOG=<f>  use this notes file instead of release-notes/<x.y.z>.md at the tag"
	@echo "    BACKEND=<url> ADMIN_PASSWORD=<pw>   target another backend (default $(BACKEND))"
	@echo
	@echo "Release: make bump V=<x.y.z> && make publish V=<x.y.z>, then git push origin <branch> --follow-tags"

.PHONY: version
version:
	@echo $(CURRENT_VERSION)

.PHONY: bump
bump:
ifndef V
	$(error usage: make bump V=0.2.0   (current: $(CURRENT_VERSION)))
endif
	@# Shape check only — never a closed list of allowed versions. Accepts 0.2.0 and 0.2.0-rc1.
	@[[ "$(V)" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$$ ]] \
		|| { echo "error: '$(V)' is not a version like 0.2.0 or 0.2.0-rc1" >&2; exit 1; }
	@# SemVer compares `beta10` < `beta9` (alphanumeric identifiers are lexical), and so do Fabric
	@# and the updater. Past 9 the number must be its own identifier: 0.2.0-beta.10.
	@! [[ "$(V)" =~ [A-Za-z][0-9]{2,} ]] \
		|| { echo "error: '$(V)' would sort before its single-digit predecessors; write e.g. 0.2.0-beta.10" >&2; exit 1; }
	@# Release notes are part of the tag: both must be COMMITTED (read from HEAD, so an untracked
	@# file does not count). The skill `release-notes` writes them.
	@git show "HEAD:release-notes/$(V).md" 2>/dev/null | grep -q '[^[:space:]]' \
		|| { echo "error: release-notes/$(V).md is missing or empty at HEAD; run the release-notes skill (.agents/skills/release-notes/SKILL.md) and commit its result before bumping" >&2; exit 1; }
	@git show "HEAD:$(NOTES_KT)" 2>/dev/null | grep -qE '^[[:space:]]*"$(subst .,\.,$(V))"[[:space:]]+to([[:space:]]|$$)' \
		|| { echo "error: Releases.notes in $(NOTES_KT) has no entry for \"$(V)\" at HEAD; run the release-notes skill (.agents/skills/release-notes/SKILL.md) and commit its result before bumping" >&2; exit 1; }
	@# Refuse when a TRACKED file is modified: the tag must point at the tree you tested, and a
	@# bump made on top of uncommitted work tags a state that exists on nobody else's machine.
	@# Untracked files are deliberately ignored — the commit stages gradle.properties only.
	@git diff --quiet && git diff --cached --quiet \
		|| { echo "error: working tree is dirty — commit or stash first" >&2; exit 1; }
	@git rev-parse -q --verify "refs/tags/v$(V)" >/dev/null \
		&& { echo "error: tag v$(V) already exists" >&2; exit 1; } || true
	@[ "$(CURRENT_VERSION)" != "$(V)" ] \
		|| { echo "error: version is already $(V)" >&2; exit 1; }
	@sed -i 's/^mod_version=.*/mod_version=$(V)/' $(VERSION_FILE)
	@git add $(VERSION_FILE)
	@git commit -q -m "Bump version to $(V)" -m "- Set mod_version to $(V) in gradle.properties"
	@git tag -a "v$(V)" -m "v$(V)"
	@echo "Bumped $(CURRENT_VERSION) -> $(V), committed and tagged v$(V)."
	@echo "Publish it with 'make publish V=$(V)' (local build, no GitHub needed); push it with:"
	@echo "    git push origin $$(git rev-parse --abbrev-ref HEAD) --follow-tags"

# ---------------------------------------------------------------- auto updater releases
#
# Installed mods take an update only if its manifest is signed by a key listed in TrustedKeys.kt.
# The private key lives only in your own 1Password (base64 of the Ed25519 PKCS#8 DER, one line) and
# is read into memory at signing time: never a file, never GitHub, never the backend. Every `op` call
# runs as you, with OP_SERVICE_ACCOUNT_TOKEN unset: this machine's service account sees vault
# `server`, which server tooling can read, so the key must not live there.
#
#     make keygen                      once; prints the public key for TrustedKeys.kt + the backend
#     make sign V=0.2.0                build tag v0.2.0 locally → build/release/0.2.0/soul-0.2.0.{jar,manifest.json,manifest.sig}
#     make publish V=0.2.0 ROLLOUT=10  sign, upload to the backend
#
# The jar is built from tag `v<V>` in a temporary `git worktree` (tools/release/build-jar.sh; JDK 25,
# `./gradlew build -Pmod_version=<V>`), never the working tree; a missing tag is an error. GitHub is
# optional: `GITHUB=1` takes the CI release's jar instead (asset digest checked; tag pushed and
# workflow finished first). `DRY_RUN=1` stops publish before the upload. `JAR=path` signs a given jar;
# the notes are release-notes/<V>.md as committed at the tag (`make notes`; `CHANGELOG=file` overrides);
# `BACKEND=` and `ADMIN_PASSWORD=` point publish at a
# local backend. Contract: docs/research/auto-updater-2026-11-10.md §API.

SIGNING_VAULT   ?= Personal
SIGNING_ITEM    ?= SoulMod release signing key
SIGNING_FIELD   ?= private key
SIGNING_REF     := op://$(SIGNING_VAULT)/$(SIGNING_ITEM)/$(SIGNING_FIELD)
TRUSTED_KEYS    := src/main/kotlin/com/soulreturns/platform/updater/TrustedKeys.kt
BACKEND         ?= https://sky.soulreturns.com
ADMIN_USER      ?= soul
ADMIN_ENV_DOC   ?= main-vps/sky/secret.env
ADMIN_ENV_VAULT ?= server
ROLLOUT         ?= 100
OP              := env -u OP_SERVICE_ACCOUNT_TOKEN op
RELEASE_DIR      = build/release/$(V)

.PHONY: keygen
keygen:
	@# `op whoami` reports "not signed in" under desktop-app integration until a real command authorizes the session; `vault get` triggers that prompt.
	@$(OP) vault get "$(SIGNING_VAULT)" >/dev/null 2>&1 || { echo "error: can't open vault '$(SIGNING_VAULT)' with your own 1Password; unlock the desktop app (CLI integration) or run: op signin. Other vault: make keygen SIGNING_VAULT=<name> (list: env -u OP_SERVICE_ACCOUNT_TOKEN op vault list)" >&2; exit 1; }
	@if $(OP) read "$(SIGNING_REF)" >/dev/null 2>&1; then echo "error: $(SIGNING_REF) already exists; refusing to replace the release key" >&2; exit 1; fi
	@set -euo pipefail; \
	key="$$(openssl genpkey -algorithm ed25519 -outform DER | base64 -w0)"; \
	$(OP) item create --category "Secure Note" --vault "$(SIGNING_VAULT)" --title "$(SIGNING_ITEM)" \
		"$(SIGNING_FIELD)[concealed]=$$key" >/dev/null; \
	unset key; \
	pub="$$($(OP) read "$(SIGNING_REF)" | base64 -d | openssl pkey -inform DER -pubout -outform DER | base64 -w0)"; \
	echo "Stored the private key as $(SIGNING_REF)"; \
	echo "Public key (goes into TrustedKeys.kt and the backend's MOD_RELEASE_PUBLIC_KEYS):"; \
	echo "$$pub"

.PHONY: sign
sign:
ifndef V
	$(error usage: make sign V=0.2.0 [GITHUB=1] [JAR=path/to/soul-0.2.0.jar])
endif
	@set -euo pipefail; mkdir -p "$(RELEASE_DIR)"; \
	jar="$(RELEASE_DIR)/soul-$(V).jar"; \
	if [ -n "$(JAR)" ]; then cp "$(JAR)" "$$jar"; \
	elif [ "$(GITHUB)" = 1 ]; then \
		gh release download "v$(V)" -p "soul-$(V).jar" -D "$(RELEASE_DIR)" --clobber; \
		want="$$(gh release view "v$(V)" --json assets -q '.assets[] | select(.name=="soul-$(V).jar") | .digest')"; \
		have="sha256:$$(sha256sum "$$jar" | cut -d' ' -f1)"; \
		[ "$$want" = "$$have" ] || { echo "error: downloaded $$have but the release asset says $$want" >&2; exit 1; }; \
	else bash tools/release/build-jar.sh "$(V)" "$$jar"; fi; \
	python3 tools/release/manifest.py "$$jar" "$(V)" "$(RELEASE_DIR)/soul-$(V).manifest.json"; \
	key="$$($(OP) read "$(SIGNING_REF)")"; \
	pub="$$(printf '%s' "$$key" | base64 -d | openssl pkey -inform DER -pubout -outform DER | base64 -w0)"; \
	grep -qF "\"$$pub\"" "$(TRUSTED_KEYS)" \
		|| { echo "error: signing key $$pub is not in $(TRUSTED_KEYS); installed mods would refuse this release" >&2; exit 1; }; \
	printf '%s' "$$key" | base64 -d | openssl pkeyutl -sign -rawin -keyform DER -inkey /dev/stdin \
		-in "$(RELEASE_DIR)/soul-$(V).manifest.json" -out "$(RELEASE_DIR)/soul-$(V).manifest.sig"; \
	unset key; \
	printf '%s' "$$pub" | base64 -d | openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey /dev/stdin \
		-in "$(RELEASE_DIR)/soul-$(V).manifest.json" -sigfile "$(RELEASE_DIR)/soul-$(V).manifest.sig" >/dev/null; \
	echo "Signed $(RELEASE_DIR)/soul-$(V).manifest.json"

.PHONY: publish
publish: notes sign
	@set -euo pipefail; \
	if [ "$(DRY_RUN)" = 1 ]; then \
		echo "DRY_RUN: stopping before upload. Would POST to $(BACKEND)/admin/api/mod-releases (rollout $(ROLLOUT)%):"; \
		ls -l "$(RELEASE_DIR)"/soul-$(V).jar "$(RELEASE_DIR)"/soul-$(V).manifest.json "$(RELEASE_DIR)"/soul-$(V).manifest.sig "$(RELEASE_DIR)/changelog.md"; \
		exit 0; \
	fi; \
	pw="$${ADMIN_PASSWORD:-$$($(OP) document get "$(ADMIN_ENV_DOC)" --vault "$(ADMIN_ENV_VAULT)" \
		| sed -n 's/^ADMIN_PASSWORD=//p' | sed -e "s/^'\(.*\)'\$$/\1/" -e 's/^"\(.*\)"$$/\1/')}"; \
	[ -n "$$pw" ] || { echo "error: no ADMIN_PASSWORD (env or $(ADMIN_ENV_DOC))" >&2; exit 1; }; \
	curl --fail-with-body -sS -K <(printf 'user = "%s:%s"\n' "$(ADMIN_USER)" "$$pw") \
		-F "jar=@$(RELEASE_DIR)/soul-$(V).jar;type=application/java-archive" \
		-F "manifest=@$(RELEASE_DIR)/soul-$(V).manifest.json;type=application/json" \
		-F "signature=@$(RELEASE_DIR)/soul-$(V).manifest.sig;type=application/octet-stream" \
		-F "changelog=<$(RELEASE_DIR)/changelog.md" \
		-F "rolloutPercent=$(ROLLOUT)" \
		"$(BACKEND)/admin/api/mod-releases"; \
	echo; \
	unset pw; \
	if [ "$(GITHUB)" = 1 ] && gh release view "v$(V)" >/dev/null 2>&1; then gh release edit "v$(V)" --draft=false --notes-file "$(RELEASE_DIR)/changelog.md" >/dev/null; echo "GitHub release v$(V) published"; fi; \
	echo "Uploaded v$(V) to $(BACKEND) (rollout $(ROLLOUT)%)"

# release-notes/<V>.md as committed at tag v<V> (never the working tree) becomes the changelog that
# publish uploads; CHANGELOG=<file> overrides. Runs before sign so a missing file fails in seconds.
.PHONY: notes
notes:
ifndef V
	$(error usage: make notes V=0.2.0 [CHANGELOG=file])
endif
	@set -euo pipefail; mkdir -p "$(RELEASE_DIR)"; \
	out="$(RELEASE_DIR)/changelog.md"; \
	if [ -n "$(CHANGELOG)" ]; then \
		grep -qs '[^[:space:]]' "$(CHANGELOG)" || { echo "error: CHANGELOG=$(CHANGELOG) is missing or empty" >&2; exit 1; }; \
		cp "$(CHANGELOG)" "$$out"; \
	else \
		git rev-parse -q --verify "refs/tags/v$(V)^{commit}" >/dev/null \
			|| { echo "error: tag v$(V) does not exist locally; run the release-notes skill, then 'make bump V=$(V)' (or 'git fetch --tags')" >&2; exit 1; }; \
		git cat-file -e "v$(V):release-notes/$(V).md" 2>/dev/null \
			|| { echo "error: tag v$(V) has no release-notes/$(V).md; write it with the release-notes skill (.agents/skills/release-notes/SKILL.md) before 'make bump', or pass CHANGELOG=<file>" >&2; exit 1; }; \
		git show "v$(V):release-notes/$(V).md" > "$$out"; \
		grep -q '[^[:space:]]' "$$out" \
			|| { echo "error: release-notes/$(V).md at tag v$(V) is empty; write it with the release-notes skill (.agents/skills/release-notes/SKILL.md)" >&2; exit 1; }; \
	fi; \
	echo "Release notes for $(V): $$out"
