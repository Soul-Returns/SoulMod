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
        @Nest public Fixes fixes = new Fixes();
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
    }

    public static class Dragons {
        // Default OFF — every user-facing feature in this mod is opt-in. See the policy notes
        // in the FishingHud section above.
        public boolean showProfitHud = false;
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
