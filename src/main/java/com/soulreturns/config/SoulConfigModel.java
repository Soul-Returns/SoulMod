package com.soulreturns.config;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;
import io.wispforest.owo.config.annotation.Nest;
import io.wispforest.owo.config.annotation.RangeConstraint;
import io.wispforest.owo.config.annotation.SectionHeader;

/**
 * Root config model for SoulMod, processed by owo-config's annotation
 * processor. The build generates a {@code SoulConfig} wrapper class with
 * accessors like {@code SoulConfig.INSTANCE.render().hudScale().chatScale()}.
 *
 * <p>Display names and tooltips for every option live in
 * {@code assets/soul/lang/en_us.json} under the keys
 * {@code text.config.soul.option.<path>} and {@code .tooltip}.
 */
@Modmenu(modId = "soul")
@Config(name = "soul/config", wrapperName = "SoulConfig")
public class SoulConfigModel {

    @SectionHeader("general")
    @Nest public General general = new General();

    @SectionHeader("render")
    @Nest public Render render = new Render();

    @SectionHeader("fishing")
    @Nest public Fishing fishing = new Fishing();

    @SectionHeader("mining")
    @Nest public Mining mining = new Mining();

    @SectionHeader("farming")
    @Nest public Farming farming = new Farming();

    @SectionHeader("combat")
    @Nest public Combat combat = new Combat();

    @SectionHeader("notifications")
    @Nest public Notifications notifications = new Notifications();

    @SectionHeader("profileViewer")
    @Nest public ProfileViewer profileViewer = new ProfileViewer();

    @SectionHeader("sync")
    @Nest public Sync sync = new Sync();

    @SectionHeader("dev")
    @Nest public Dev dev = new Dev();

    // "About" category isn't declared here — it has no backing fields and is purely a
    // virtual category surfaced via `ConfigSections.virtualSubs["about"]` + `actionRows`
    // (third-party attribution buttons). Adding empty `About` / `UsedSoftware` classes
    // produces zero owo-config options, so they'd be no-ops anyway.

    public static class General {
        @Nest public Ui ui = new Ui();
        @Nest public Chat chat = new Chat();
        @Nest public Fixes fixes = new Fixes();
    }

    public static class Chat {
        // Right-click any chat message to copy it to the clipboard. Plain click → message
        // plain text. Shift+click → only the line under the cursor (for multi-line server
        // messages). Ctrl+click → full message with §-color codes preserved. Default OFF —
        // opt-in per the project's feature policy.
        public boolean enableRightClickCopy = false;
        // Prefix the mod's outgoing conversational announcements (Double Hook response,
        // Legion stats on dragon death, dragon profit) sent to /pc and /ac with `[Soul] `
        // so other party / lobby members can see the message came from a Soul mod user.
        // **Protocol messages are NOT prefixed by design** — !ptme alerts (LapisCorpseAlert
        // / VanguardCorpseAlert / LittlefootAlert) and `x: N, y: N, z: N` waypoint shares
        // stay clean so they remain parseable by other mods' regexes. Default ON — this is
        // a sub-option of the chat-send features (not a user-facing feature itself), so
        // the project's "sub-options stay ON when their master is OFF" rule applies.
        public boolean prefixOutgoingMessages = true;
    }

    public static class Render {
        @Nest public HudScale hudScale = new HudScale();
        @Nest public Highlights highlights = new Highlights();
        @Nest public Overlays overlays = new Overlays();

        public boolean hideHeldItemTooltip = false;
        public boolean showSkyblockIdInTooltip = false;
        public boolean oldSneakHeight = false;
        public boolean hideEffectsInInventory = false;
        public boolean hideEffectsInHud = false;
        public boolean hideLightningFlash = false;
    }

    public static class HudScale {
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float tabListScale = 1.0f;
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float hotbarScale = 1.0f;
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float bossBarScale = 1.0f;
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float chatScale = 1.0f;
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float actionBarScale = 1.0f;
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float scoreboardScale = 1.0f;
    }

    public static class Highlights {
        public boolean highlightPestEquipment = false;
        public boolean usePestVest = false;
        public boolean highlightFarmingEquipment = false;
        public boolean highlightCustomItems = false;
    }

    public static class Overlays {
        public boolean enableLegionCounter = false;
        public boolean enablePartyOverlay = false;
    }

    public static class Ui {
        // When true, Soul HUDs scale with Minecraft's GUI Scale setting (vanilla-like).
        // When false (default), they render at a fixed on-screen pixel size regardless of
        // the user's GUI Scale option — the Soul-framework convention is "consistent
        // sizing across all setups" by default.
        public boolean respectMinecraftGuiScale = false;
        // Global scale multiplier applied to every Soul HUD element, layered on top of each
        // element's individual `scale` (set via /soul gui mouse-wheel). 1.0 = no change.
        @RangeConstraint(min = 0.5f, max = 2.0f, decimalPlaces = 2)
        public float globalScale = 1.0f;
        // Master switch for Soul HUD panel backgrounds. Off = transparent panels (text +
        // shapes only, no dark backdrop). Per-HUD overrides in /soul gui can opt INDIVIDUAL
        // HUDs out when this is on, but they cannot opt in when this is off — global wins.
        public boolean hudBackground = true;
        // Use Minecraft's vanilla / resource-pack font for Soul HUD text instead of the
        // bundled Inter NanoVG fonts. Off (default) keeps the crisp Inter rendering.
        // Per-HUD overrides in /soul gui follow the same global-wins rule.
        public boolean useMinecraftFont = false;
        // Render HUD text with a Minecraft-style drop shadow (offset black copy beneath
        // the glyphs). On by default — matches the visual weight of vanilla HUDs and reads
        // better when the HUD background is transparent or sits over bright terrain.
        public boolean hudTextShadow = true;
        // Shadow thickness multiplier in physical (monitor) pixels. 1 = thin, 4 = heavy.
        // 1.5 by default (1 px hard shadow plus a half-alpha second ring) — softer than
        // 1 but doesn't read as a heavy drop. Config UI snaps to 0.5 steps (see
        // `ConfigSections.optionStep`). Only meaningful when [hudTextShadow] is on.
        @RangeConstraint(min = 1.0f, max = 4.0f, decimalPlaces = 1)
        public float hudTextShadowSize = 1.5f;
        // Use a heavier Inter weight for HUD body text (Regular → Medium, Medium → SemiBold).
        // On by default — matches Odin's all-SemiBold approach and is what lets Inter render
        // crisply at small HUD sizes without needing a shadow (shadow + AA Inter glyphs causes
        // halo crossover; thicker strokes shrink the AA region so plain no-shadow text reads
        // clean). Per-HUD overrides in /soul gui follow the same global-wins rule.
        public boolean hudBoldFont = true;
    }

    public static class Fishing {
        @Nest public FishingChat chat = new FishingChat();
        @Nest public BobbinTime bobbinTime = new BobbinTime();
        @Nest public FishingHud fishingHud = new FishingHud();
    }

    public static class FishingChat {
        public boolean doubleHookMessageToggle = false;
        public String doubleHookMessageText = "Woot Woot!";
    }

    public static class FishingHud {
        // Default OFF — every user-facing feature in this mod is opt-in. Sub-options below
        // (showFestivalTimer, addDoubleHookToCatches, …) stay on because they're display
        // preferences inside the HUD; they only do anything once the user opts in by
        // flipping showHud on. The Welcome page's "Quick Settings" + the Fishing → Fishing
        // HUD page are the obvious places to enable it.
        public boolean showHud = false;
        // When true (default) the per-creature list renders inside a fixed-height
        // scrollable region. When false it renders as a complete list and the panel grows
        // with the row count. Live toggle — no restart needed.
        public boolean scrollableList = true;
        public boolean showFestivalTimer = true;
        // When true the Catches column / total includes Double Hook counts as well, so a
        // creature catch that was also a double hook is counted twice (once for catches,
        // once for the DH bonus). On by default — matches how most users think about their
        // catch count. Affects both per-creature rows AND the bottom totals row.
        public boolean addDoubleHookToCatches = true;
        // When true the Catches column / total includes Cocoon counts as well. Cocoons are
        // a separate kill-time outcome that don't currently feed into the catch counter;
        // turning this on rolls them into the displayed catch number. Off by default —
        // cocoons are a niche kill-outcome and most users treat them as their own bucket.
        public boolean addCocoonToCatches = false;
    }

    public static class BobbinTime {
        public boolean enableBobbinTimeCounter = false;
        public boolean enableBobbinTimeAlert = false;
        @RangeConstraint(min = 1, max = 5)
        public int alertBobberCount = 5;
        public boolean syncBobbinAlertWithParty = false;
        public String alertItemNameFilter = "Spade";
    }

    public static class Mining {
        @Nest public DwarvenMines dwarvenMines = new DwarvenMines();
        @Nest public Mineshaft mineshaft = new Mineshaft();
    }

    public static class DwarvenMines {
        public boolean donExpressoAlert = false;
    }

    public static class Mineshaft {
        public boolean enableLapisPtme = false;
        @RangeConstraint(min = 1, max = 4)
        public int lapisCorpseThreshold = 2;
        public boolean enableVanguardPtme = false;
        public boolean showCorpsesHud = false;
        public boolean enableLittlefootPtme = false;
        public boolean autoShareLittlefootWaypoint = true;
    }

    public static class Farming {
        @Nest public Seasonings seasonings = new Seasonings();
    }

    public static class Combat {
        @Nest public Dragons dragons = new Dragons();
        @Nest public Diana diana = new Diana();
    }

    public static class Diana {
        // Show the Mythological Mob HUD overlay (counts the mobs you dig out during Mayor
        // Diana's Mythological Ritual). Tracking always runs as long as the tracker master
        // toggle below is on. Default off — opt-in feature policy.
        public boolean showMobHud = false;
        // Show the Mythological Profit HUD overlay (counts treasure-burrow drops + per-mob
        // drops once admins curate them). Default off — opt-in.
        public boolean showProfitHud = false;
        // When true the Count column / Mobs total / Mobs/hr rate include Cocoon counts as
        // well, so a mob you cocooned counts toward your headline mobs total. Default off —
        // cocoons are a distinct kill-outcome and most users keep them separate. Affects
        // both per-mob rows AND the bottom totals row so the numbers stay consistent.
        public boolean addCocoonsToTotal = false;
        // Default OFF for this tracker (vs. ON for Fishing / Dragon) — the Mythological
        // catalog is small (12 mobs) and the panel is most readable as a full list rather
        // than a scrollable region pinched into the same vertical slot. Flip to true if
        // you want the fixed-height scrollable look.
        public boolean scrollableList = false;
        // Pricing source for Mythological loot drops. ON = sell-offer (ASK side, higher);
        // OFF = instant-sell (BID side, lower). Same semantics as the Dragon tracker's
        // matching field. Ignored when [lootPriceUseNpc] is on. Default ON.
        public boolean lootPriceSellOffer = true;
        // When true, value loot at NPC sell price. Mirrors the Dragon tracker's matching
        // field — Ironman-focused. Default off.
        public boolean lootPriceUseNpc = false;
    }

    public static class Dragons {
        // Default OFF — every user-facing feature in this mod is opt-in. See the policy notes
        // in the FishingHud section above.
        public boolean showProfitHud = false;
        // When true (default) the per-drop list renders inside a fixed-height scrollable
        // region. When false it renders as a complete list and the panel grows with the
        // row count.
        public boolean scrollableList = true;
        // When ON, the mod fires a Legion announcement on every dragon-down banner. The
        // [sendLegionToPartyChat] sub-toggle decides whether it goes to /pc or just the
        // local client chat. Default OFF — opt-in feature policy.
        public boolean sendLegionOnDeath = false;
        // Sub-toggle for [sendLegionOnDeath]. ON = `/pc Legion: N players (X.XX%)` (falls
        // back to local chat when not in a party — Hypixel silently drops /pc otherwise).
        // OFF = show the same line in the player's own chat only (no broadcast). Default
        // OFF — broadcasting your legion stats to party chat on every dragon kill is the
        // noisier choice; opt in deliberately.
        public boolean sendLegionToPartyChat = false;
        // When ON, the mod fires a per-dragon profit announcement 5 seconds after the
        // first loot stand is parsed for that kill — long enough for the player to walk
        // closer and load most stragglers. The [sendDragonProfitToPartyChat] sub-toggle
        // decides party-vs-local. Default OFF — opt-in.
        public boolean sendDragonProfit = false;
        // Sub-toggle for [sendDragonProfit]. Same semantics as [sendLegionToPartyChat].
        // Default ON.
        public boolean sendDragonProfitToPartyChat = true;
        // Pricing source for items the player BUYS (Summoning Eyes — drives the "Eyes
        // placed: N (cost)" header annotation and the eye-cost subtraction inside Profit /
        // Per dragon). ON = instant-buy (ASK side, higher) — what you pay if you hit a
        // sell-order. OFF = buy-order (BID side, lower) — what you'd pay placing a buy
        // offer. Default ON since most players instant-buy eyes on the way in.
        public boolean eyePriceInstantBuy = true;
        // Pricing source for items the player SELLS (loot drops — drives the per-row
        // "Coins" column and the Profit total). ON = sell-offer (ASK side, higher) — what
        // your offer fills at. OFF = instant-sell (BID side, lower) — what you'd get by
        // hitting a buy-order. Default ON since most players sell-offer loot for the
        // better price. Same toggles flow into [DragonProfitAnnouncer]'s chat output so
        // the announcement matches what the HUD shows. Ignored when [lootPriceUseNpc] is on.
        public boolean lootPriceSellOffer = true;
        // When true, value loot drops at NPC sell price (the catalog's `npc_sell_price`)
        // instead of bazaar / AH. Primary use case is Ironman players who can't trade. The
        // HUD's "Loot:" button cycles SellOffer → InstantSell → NPC, so this is one of
        // three states the same button surfaces. Default off.
        public boolean lootPriceUseNpc = false;
    }

    public static class Seasonings {
        public boolean enableTracker = false;
        public boolean showMaxMilestone = true;
        public boolean showNextMilestone = true;
        public boolean showFarmingTime = true;
        public boolean showPerHour = true;
    }

    public static class Fixes {
        public boolean fixDoubleSneak = false;
        public boolean oldCactusHitbox = false;
    }

    public static class Notifications {
        // Default OFF — chatAlerts is a user-facing feature (renders showAlert overlays via
        // RenderUtils when chat lines match a rule, e.g. "X Pests spawned"). The mod's
        // opt-in-by-default policy applies; user enables once they want the alerts.
        public boolean chatAlerts = false;
    }

    public static class ProfileViewer {
        public boolean enabled = true;
    }

    public static class Sync {
        public boolean enabled = true;
        public boolean syncConfig = true;
        public boolean syncGuiLayout = true;
        public boolean syncStats = true;
    }

    public static class Dev {
        @Nest public Updates updates = new Updates();
        @Nest public Backend backend = new Backend();
        @Nest public Debug debug = new Debug();
        @Nest public Data data = new Data();
        @Nest public Trackers trackers = new Trackers();
        @Nest public DevKeybinds keybinds = new DevKeybinds();
    }

    // Per-tracker master switches. Trackers are the chat-driven data layers that feed
    // HUDs / stats — turning one off stops accumulating data for that domain. Lives under
    // Dev because it's a wholesale opt-out, not a gameplay preference; defaults are on.
    public static class Trackers {
        public boolean fishingTracker = true;
        // Master switch for all profit-tracker data layers (dragon profit, future slayer
        // profit, etc.). Off ⇒ the price-cache poller stays idle and individual profit
        // HUDs short-circuit to Box {} regardless of their own showHud flag. On by default
        // because trackers only accumulate data when the player is actively in their domain
        // (e.g. in The End for dragon profit) — no cost when idle.
        public boolean profitTrackers = true;
        // Master switch for the chat-driven Mythological mob tracker (counts mobs dug out
        // during Mayor Diana's Mythological Ritual). Off ⇒ no chat parsing, no stat writes.
        // Default on — only fires when the relevant chat lines arrive, no idle cost.
        public boolean mythologicalTracker = true;
        // Global "NPC floor" for every profit tracker's loot price. When on, each loot
        // price lookup returns max(primary_source, npc_sell_price) so the player gets the
        // higher of the two for any item with an NPC value. Useful when NPC > bazaar
        // (early-game pets, niche drops). Per-tracker NPC mode is unaffected (it's already
        // NPC). Default off.
        public boolean useNpcPriceIfHigher = false;
    }

    public static class Data {
        public boolean logMineshaftVisits = true;
    }

    public static class DevKeybinds {
        // Each value is a Minecraft InputConstants key translation key (e.g. "key.keyboard.f6"),
        // or "" for unbound. Picker UI in SoulConfigScreen treats these specially.
        public String copyOpenedGui = "";
        public String copyItemUnderCursor = "";
        public String copyHeldItem = "";
        public String copyScoreboard = "";
        public String copyTablist = "";
        public String copyNearbyEntities = "";
    }

    public static class Updates {
        public boolean checkForUpdates = true;
    }

    public static class Backend {
        public String backendUrlOverride = "";
    }

    public static class Debug {
        public boolean debugMode = false;
        public boolean logToFile = true;
        // Sub-option of logToFile: when true, every parsed chat message + command execution
        // is teed into soul-latest.log. Default off because it spams the file with hundreds
        // of lines per minute when actively playing. Never reaches the console regardless.
        public boolean includeMessagesInLog = false;
        @Nest public Logging logging = new Logging();
    }

    // All sub-toggles default true and are gated by Debug.debugMode at the call sites.
    // Master switch off ⇒ no console output regardless of these values (and they're hidden
    // in the config UI). Master on ⇒ each toggle picks which category gets logged.
    public static class Logging {
        public boolean logConfigChanges = true;
        public boolean logGuiLayout = true;
        public boolean logWidgetInteractions = true;
        public boolean logFeatureEvents = true;
        public boolean logBackend = true;
        public boolean logRealtime = true;
    }
}
