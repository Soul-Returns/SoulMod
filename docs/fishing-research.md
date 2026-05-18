# Fishing features — research notes

Source-grounded reference for Double Hook tracking, sea-creature tracking, and Fishing Festival
state. Every chat line / regex below is verified against either the user's `latest.log`
(`C:\Users\soul\AppData\Roaming\PrismLauncher\instances\26\minecraft\logs\latest.log`) or
SkyHanni's source / repo data. **Do not invent additional patterns** — verify against one of
those before adding.

---

## Feature scope reminder

- Double-hook + sea-creature tracking are **always-on, mayor-independent** features. They run
  every time the player fishes.
- The **Fishing Festival** is an additive layer that adds *extra* bucket dimensions for the
  same data (and unlocks the SHARK variant of catches). It never changes the core tracker's
  behavior — just gives it another counter slot to write to.
- **Mayor / Marina state is intentionally ignored.** Special cases exist where non-Marina
  mayors (e.g. Jerry perkpocalypse) can fire a fishing festival, and we do not want to
  duplicate that logic. The festival chat lines are the only signal we trust.
- **Backend sync is deferred to v2.** Collect all the data shapes the backend will eventually
  want (per-creature breakdown, festival buckets, etc.) but do not wire up any telemetry POSTs
  yet. The user wants to see the in-game features first before deciding on event granularity.

---

## Fishing Festival — start / end / cadence

### Chat lines (verified from `latest.log`)

Both lines are server-side, on the `CHAT` channel (i.e. `ChatMessage.Source.SERVER`). Color codes
are stripped to give the literal text below.

| Event | Literal text (after `stripColorCodes`) | Log evidence |
|---|---|---|
| Start  | `FISHING FESTIVAL The festival is now underway! Break out your fishing rods and watch out for sharks!` | `[01:15:00] [CHAT] FISHING FESTIVAL The festival is now underway! …` (log line 14191) |
| End    | `FISHING FESTIVAL The festival has concluded! Time to dry off and repair your rods!` | `[02:14:58] [CHAT] FISHING FESTIVAL The festival has concluded! …` (log line 22266) |

Substring match on the distinctive prefix is enough:

```kotlin
private const val FESTIVAL_UNDERWAY = "FISHING FESTIVAL The festival is now underway"
private const val FESTIVAL_CONCLUDED = "FISHING FESTIVAL The festival has concluded"
```

### Start-message semantics (this is the messy part)

The "underway" line is **NOT** a clean "festival just started" event. It is a "festival is
currently active" indicator, and Hypixel emits it in several situations:

- **Real start** — the festival just transitioned from inactive to active. Sent once at the
  scheduled start time. *Usually* the first time we see it.
- **Server join during an active festival** — a player who logs in mid-festival receives the
  message on join. This is *intentional* on Hypixel's side: it's how the client learns the
  festival is currently running.
- **Random duplicate during an already-active festival** — there is a known Hypixel bug where
  the underway message fires again partway through an active festival, with no actual state
  change. We must treat duplicates as no-ops.

**Rule for the mod:** the underway message means "a festival is active right now". First
occurrence while we believe no festival is active → mark active and stamp `firstSeenStartAt`.
Subsequent occurrences while already active → ignore. Do not restart the timer.

### End-message semantics

The "concluded" line is **reliable and authoritative**:

- Sent exactly once per festival, at the moment the festival ends.
- Not duplicated, not echoed on server join.
- Always trust it as the true end of the festival.

**Rule for the mod:** on a "concluded" message while active → mark inactive immediately,
publish the festival-end event, clear persisted festival state.

### Duration cap

- Festivals are advertised as 1 hour long. We use this as a **safety-net cap**, not the primary
  end signal.
- If the mod has been in "festival active" state for ≥ 1 hour from `firstSeenStartAt` and no
  end message has arrived, auto-end the festival. This protects against a missed end message
  (e.g. server disconnect at the wrong moment, chat-filter mod swallowing the line).
- Player-observed duration may be **shorter** than 1 hour: a player who logged in 40 minutes
  into a festival sees only 20 minutes of festival on their counters. That's correct — the
  bucket records what the player actually witnessed.

### Cadence / mayor gating

- We do **not** parse mayor data. Cadence prediction ("next festival in HH:MM") is out of
  scope. The user will revisit countdown UI later if desired.
- The SkyBlock-side cadence of 1 SB day (9 h 20 m RL) only applies under specific mayors —
  Marina, and certain perkpocalypse rolls. We don't track this. The chat lines are the source
  of truth.

### State model

In-memory + persisted to `stats.json`:

| Field | Type | Meaning |
|---|---|---|
| `active` | `Boolean` | derived from `festivalStartAt != 0L && now - festivalStartAt < 1h` (and not cleared by end message) |
| `festivalStartAt` | `Long` (epoch ms) | timestamp of the first underway message we observed for the current festival; `0L` when inactive |

Persisting `festivalStartAt` lets a mid-festival client restart continue the same bucket
without reattributing it as a new festival.

### Published events

```kotlin
sealed class FishingFestivalEvent : Event {
    data class Started(val firstSeenStartAt: Long) : FishingFestivalEvent()
    data class Ended(val firstSeenStartAt: Long, val endedAt: Long, val reason: EndReason) : FishingFestivalEvent()
}
enum class EndReason { CONCLUDED_MESSAGE, ONE_HOUR_CAP }
```

---

## Double Hook — chat patterns

### SkyHanni's authoritative regex

From `SkyHanni/src/main/java/at/hannibal2/skyhanni/features/fishing/SeaCreatureManager.kt:35`:

```
§eIt's a §r§aDouble Hook§r§e!(?: Woot woot!)?
```

After `stripColorCodes` the literal text is either:

- `It's a Double Hook!`
- `It's a Double Hook! Woot woot!`

Both forms are server-sent by Hypixel — the `Woot woot!` flavour suffix is a Hypixel-server
variant, **not** our `DoubleHookResponse` party-chat output. (Our `/pc` send goes through the
party-chat channel as `Party > [RANK] Player: Woot Woot!`, never as `It's a Double Hook!`.)

### Chat-compactor suffix (`(N)`)

The user runs a chat-compacting mod (Compacting / ChatPatches / similar). It coalesces repeated
lines and appends a `(N)` counter. Examples from `latest.log`:

```
It's a Double Hook! (2)
It's a Double Hook! (15)
It's a Double Hook! Woot woot! (7)
```

For attribution we must decode `(N)` deltas the same way `SeasoningTracker.onChat` does:
when receive-level compactors suppress intermediate emissions, the `(N)` count is the only
record of how many double-hooks fired between firings. Each delta must be attributed to a
catch via the sticky-flag mechanism described next.

### Double-hook ↔ catch attribution — sticky flag (SkyHanni's pattern)

**SkyHanni's mechanism works perfectly and is what we'll mirror — do not introduce a
time-window heuristic.** Fishing can be fast enough that consecutive double-hooks land within
~1.5 s, and a ±2 s window would mis-attribute.

The pattern is:

1. Maintain a single `var doubleHook = false` flag in the tracker.
2. On a **double-hook chat line** → set `doubleHook = true`. Don't reset on unrelated lines.
3. On a **catch chat line** (matched against `SeaCreatureCatalog`) → emit
   `SeaCreatureCaught(creature, doubleHook = this.doubleHook)`, then reset `doubleHook = false`.
4. On **any other server message** that isn't an interleaver (see below) → reset
   `doubleHook = false`. This guards against a stale flag persisting across an unrelated
   double-hook → unrelated event sequence.

Order in chat is consistently: double-hook line FIRST, catch line SECOND, even when they
arrive in the same Minecraft tick. The example below from `latest.log` shows both stamped at
`00:41:01` but the double-hook line comes first in display order:

```
[00:41:01] It's a Double Hook! Woot woot!
[00:41:01] You reeled in a Sea Archer.
```

So the sticky-flag approach is robust: set on hook, consumed on catch.

### Interleaving messages between double-hook and catch

Per `SeaCreatureManager.kt:106-118`, the following can land **between** the double-hook line
and the catch line and **must not** reset the flag:

- Autopet trigger lines (Sinker rod parts: Sponge, Prismarine, Icy).
- `§e> Your bottle of thunder has fully charged!` (Thunder sea-creature side-effect).
- `A Reindrake forms from the depths.` plus surrounding empty lines.
- Empty / whitespace-only lines.

Implementation: an explicit `isInterleaverLine(message)` predicate. Lines matching it are
skipped (no state mutation); only catch matches consume the flag, and only "anything else"
clears the flag.

### Catch detection

A "catch" is any chat line whose `stripColorCodes` content matches an entry in
`SeaCreatureCatalog.byCleanMessage` (built from `chat_message` and stripped
`alternate_messages` from the bundled JSON). Lookup is one map probe.

---

## Sea-creature catch messages

### Source

SkyHanni does **not** hard-code these — they're loaded from the SkyHanniRepo at runtime. The
local cached copy on the user's machine is at:

```
C:\Users\soul\AppData\Roaming\PrismLauncher\instances\26\minecraft\config\skyhanni\repo\constants\SeaCreatures.json
```

Top-level keys group creatures by **fishing context** (variant), and each creature has a
`chat_message`, an `alternate_messages` list (color-coded variants), `fishing_experience`, and
a `rarity`. We use the canonical `chat_message` (uncolored) for matching after
`stripColorCodes`.

### Variants present in the current SkyHanniRepo snapshot

`PARK`, `CHUMCAP`, `CARROT`, `WATER`, `WINTER_ISLAND`, `SPOOKY`, **`SHARK` (Fishing-Festival
only)**, `OASIS`, `ABANDONED_QUARRY`, `MAGMA_FIELDS`, `LAVA_PRECURSOR`, `GOBLIN_BURROWS`,
`WATER_CRYSTAL_HOLLOWS`, `LAVA_CRIMSON_ISLE`, `PLHLEGBLAST`, `BACKWATER_BAYOU`, `LAVA_HOTSPOT`,
`WATER_HOTSPOT`, `GALETEA`.

The **SHARK variant** (Nurse / Blue / Tiger / Great White) is **only catchable while a fishing
festival is active**. Catching a shark is therefore implicit evidence that the festival is
active — but we don't use this as a primary festival detector; the chat line is the source of
truth.

### Strategy

- **Bundle a snapshot** of `SeaCreatures.json` inside the mod's resources at
  `src/main/resources/assets/soul/sea_creatures.json`, parsed once at startup. Self-contained;
  no SkyHanni dependency, no live repo fetch.
- Build two indices at load time:
  - `byCleanMessage: Map<String, SeaCreature>` keyed by `chat_message` (uncolored canonical).
  - Also walk every entry in `alternate_messages`, strip color codes, and add to the same map.
    If two creatures collide on a stripped key (unlikely but possible), log a warning and keep
    the first.
- Lookup at runtime: one stripped-text map probe per `ChatMessage`.
- **Out-of-scope for v1:** auto-updating the snapshot from the SkyHanniRepo. Refresh manually
  when SkyHanni adds new creatures (rare event).

### Catch line examples (from `latest.log`)

The only sea creature in this log is **Sea Archer** (`§aYou reeled in a Sea Archer.` →
`You reeled in a Sea Archer.`). All other variants are unverified locally and must be trusted
to the SkyHanni snapshot.

---

## Counter scopes (per profile)

| Scope | Lifetime | Persistence |
|---|---|---|
| All-time | forever, per SkyBlock profile slot | `stats.json` via `PersistentStats.update { … }` |
| Session | from client launch | in-memory only — reset on JVM restart |
| Festival | from the first observed underway message until end (concluded message OR 1h cap) | persist `festivalStartAt` + festival counters in `stats.json` so a mid-festival restart keeps counting; cleared on festival end |

For each scope we track:

- `doubleHooks: Long`
- `catches: Long`
- `doubleHooksByCreature: Map<String, Long>` (collected even though not surfaced in the HUD —
  reserved for v2 backend + SPV)
- `catchesByCreature: Map<String, Long>` (same — collected for backend, not shown in HUD)

HUD shows only the four scalar totals (double-hooks all-time/session/festival, catches
all-time/session/festival). Per-creature data lives only in `stats.json` until v2.

---

## Backend sync — deferred to v2

Do not ship any backend telemetry in v1. The user wants to see the in-game features first
before deciding on event granularity / aggregation. When v2 happens:

- Bridge will be a new `prompts/fishing/01-stats.md` brief for SkyBackend.
- Data already exists in `PersistentStats` (per-creature maps, festival buckets) — telemetry
  will derive from periodic snapshots / deltas, not require schema changes.
- Probable endpoint shape: `POST /fishing/snapshot` with the current per-profile data, or
  `POST /fishing/event` for per-catch streaming. Decide later.

---

## Existing related code in the mod

- `features/DoubleHookResponse.kt` — already detects `Double Hook!` and sends a configurable
  `/pc` message. This will eventually subscribe to `FishingTracker`'s `SeaCreatureCaught`
  event instead of duplicating chat parsing, so the "Woot Woot!" message can include the
  creature name if the user wants. **v1: leave as-is**; the new tracker runs alongside it.
- `features/fishing/BobbinSpotter.kt` — existing fishing feature; lives under
  `features/fishing/`. New code follows this domain folder convention.
- `data/model/ChatEvents.kt` — `ChatMessage(raw, source)` is the event-bus payload.
  `MessageDetector.stripColorCodes` removes `§.` codes (incl. Hypixel placeholders).
- No mayor/election infrastructure exists yet — confirmed via `grep`, and we're intentionally
  not adding any.
