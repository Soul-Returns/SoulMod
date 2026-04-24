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

    @SectionHeader("render")
    @Nest public Render render = new Render();

    @SectionHeader("fishing")
    @Nest public Fishing fishing = new Fishing();

    @SectionHeader("mining")
    @Nest public Mining mining = new Mining();

    @SectionHeader("fixes")
    @Nest public Fixes fixes = new Fixes();

    @SectionHeader("profileViewer")
    @Nest public ProfileViewer profileViewer = new ProfileViewer();

    @SectionHeader("backend")
    @Nest public Backend backend = new Backend();

    @SectionHeader("debug")
    @Nest public Debug debug = new Debug();

    public static class Render {
        @Nest public HudScale hudScale = new HudScale();
        @Nest public Highlights highlights = new Highlights();
        @Nest public Overlays overlays = new Overlays();

        public boolean hideHeldItemTooltip = false;
        public boolean showSkyblockIdInTooltip = false;
        public boolean oldSneakHeight = false;
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
        public boolean itemHighlightingEnabled = false;
        public boolean highlightPestEquipment = false;
        public boolean usePestVest = false;
        public boolean highlightFarmingEquipment = false;
        public boolean highlightCustomItems = false;
    }

    public static class Overlays {
        public boolean enableLegionCounter = false;
        public boolean enablePartyOverlay = false;
    }

    public static class Fishing {
        @Nest public FishingChat chat = new FishingChat();
        @Nest public BobbinTime bobbinTime = new BobbinTime();
    }

    public static class FishingChat {
        public boolean doubleHookMessageToggle = false;
        public String doubleHookMessageText = "Woot Woot!";
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
    }

    public static class DwarvenMines {
        public boolean donExpressoAlert = false;
    }

    public static class Fixes {
        public boolean fixDoubleSneak = false;
    }

    public static class ProfileViewer {
        public boolean enabled = true;
    }

    public static class Backend {
        public String backendUrlOverride = "";
    }

    public static class Debug {
        public boolean debugMode = false;
        @Nest public Logging logging = new Logging();
    }

    public static class Logging {
        public boolean logConfigChanges = false;
        public boolean logGuiLayout = false;
        public boolean logWidgetInteractions = false;
        public boolean logMessageHandler = false;
        public boolean logFeatureEvents = false;
        public boolean logCommandsAndMessages = false;
    }
}
