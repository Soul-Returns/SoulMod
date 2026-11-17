package com.soulreturns.testkit.experiments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The non-game menus with the captured strings (docs/research/raw/experiments-2026-11-17/menus-table.txt,
 * menus-stakes-over.txt; full lore incl. blank lines from src/test/resources/experiments/menus.ndjson.gz).
 */
public final class MenuItems {
    private MenuItems() {}

    public static final String TEX_SUPERPAIRS = "81b843451184a8ccd8e6e49d0edf3451d3dea50fde5b6a2f98ab7cf1138bcece";
    public static final String TEX_CHRONO = "bc162a5ab7039b6042c59055e88e05088006c1e9b1487e857f16fbf4f52fb6da";
    public static final String TEX_ULTRA = "8b0cd070eb6d29d0903086f0ca75d30a1484928550c24f48ac07ae7a7000bdc9";

    public static final int TABLE_SP = 22, TABLE_CHRONO = 29, TABLE_ULTRA = 33, TABLE_RENEW = 31, TABLE_RNG = 48, TABLE_CLOSE = 49, TABLE_BOTTLES = 50;
    public static final int OVER_HEAD = 11, OVER_DECLINE = 15, REWARDS_HEAD = 13;

    /** One stakes tier: name, color code, dye, board text, XP label and value, required level, cost (SP) or retry cost after a decline. */
    public record Tier(String game, String name, char code, String dye, String board, String xpLabel, int xp, int req, int cost) {
        public String colored() {
            return "§r§" + code + name;
        }
    }

    public static final List<Tier> CHRONO = List.of(
        new Tier("chrono", "High", 'a', "lime_dye", "3", "1.5k", 1500, 20, 5),
        new Tier("chrono", "Grand", 'e', "yellow_dye", "5", "2.5k", 2500, 25, 10),
        new Tier("chrono", "Supreme", '6', "orange_dye", "7", "3.5k", 3500, 30, 15),
        new Tier("chrono", "Transcendent", 'c', "red_dye", "8", "4.5k", 4500, 35, 20),
        new Tier("chrono", "Metaphysical", 'd', "magenta_dye", "10", "6k", 6000, 40, 40));
    /** Retry costs after a decline are ASSUMED to equal Chronomatron's for the same tier name (never captured). */
    public static final List<Tier> ULTRA = List.of(
        new Tier("ultra", "Supreme", '6', "orange_dye", "7x3", "3.5k", 3500, 25, 15),
        new Tier("ultra", "Transcendent", 'c', "red_dye", "7x4", "5k", 5000, 30, 20),
        new Tier("ultra", "Metaphysical", 'd', "magenta_dye", "9x4", "7k", 7000, 40, 40));
    /** xp = default clicks here; cost = starting cost in XP levels. */
    public static final List<Tier> SP = List.of(
        new Tier("sp", "Beginner", 'b', "light_blue_dye", "7x2", "", 10, 10, 25),
        new Tier("sp", "High", 'a', "lime_dye", "5x4", "", 12, 20, 50),
        new Tier("sp", "Grand", 'e', "yellow_dye", "5x4", "", 12, 25, 75),
        new Tier("sp", "Supreme", '6', "orange_dye", "7x4", "", 14, 30, 100),
        new Tier("sp", "Transcendent", 'c', "red_dye", "7x4", "", 16, 40, 200),
        new Tier("sp", "Metaphysical", 'd', "gray_dye", "7x4", "", 12, 50, 350));
    public static final int[] STAKES_CHRONO = {20, 21, 22, 23, 24};
    public static final int[] STAKES_ULTRA = {21, 22, 23};
    public static final int[] STAKES_SP = {21, 22, 23, 30, 31, 32};

    private static final List<List<String>> SP_PAIRS = List.of(
        List.of("§r §r§33.5k-50k Enchanting Exp", "§r §r§f10-30 Experience Bottles"),
        List.of("§r §r§35k-60k Enchanting Exp", "§r §r§f10-30 Experience Bottles", "§r §r§a5-20 Grand Experience Bottles", "§r §r§9Titanic Experience Bottle"),
        List.of("§r §r§35k-90k Enchanting Exp", "§r §r§a5-20 Grand Experience Bottles", "§r §r§9Titanic Experience Bottle", "§r §r§6III-VI Enchanted Books", "§r §r§51 §r§5Metaphysical Serum"),
        List.of("§r §r§35k-120k Enchanting Exp", "§r §r§a5-20 Grand Experience Bottles", "§r §r§9Titanic Experience Bottle", "§r §r§6III-VI Enchanted Books", "§r §r§51 §r§5Metaphysical Serum"),
        List.of("§r §r§325k-130k Enchanting Exp", "§r §r§a5-20 Grand Experience Bottles", "§r §r§91-2 Titanic Experience Bottles", "§r §r§6III-VI Enchanted Books", "§r §r§51 §r§5Metaphysical Serum"),
        List.of("§r §r§325k-150k Enchanting Exp", "§r §r§a5-20 Grand Experience Bottles", "§r §r§91-2 Titanic Experience Bottles", "§r §r§6III-VI Enchanted Books", "§r §r§51 §r§5Metaphysical Serum"));
    private static final List<List<String>> SP_RARE = List.of(List.of(), List.of(), List.of(),
        List.of("§r§dTiny chance to find VII books!", "§r§6Minuscule chance to find Nadeshiko Dye!"),
        List.of("§r§dVery rare chance to find VII books!", "§r§6Incredibly rare chance to find Nadeshiko Dye!"),
        List.of("§r§dRare chance to find VII books!", "§r§6Very rare chance to find Nadeshiko Dye!"));
    /** Superpairs stakes XP on the Rewards head: Transcendent 300,000 captured, the rest ASSUMED. */
    private static final int[] SP_STAKES_XP = {25_000, 50_000, 100_000, 200_000, 300_000, 400_000};

    public static Tier tier(String game, String name) {
        for (Tier t : tiers(game)) if (t.name.equalsIgnoreCase(name)) return t;
        throw new IllegalArgumentException("unknown " + game + " tier " + name + " (" + String.join(", ", tiers(game).stream().map(Tier::name).toList()) + ")");
    }

    public static List<Tier> tiers(String game) {
        return switch (game) {
            case "chrono" -> CHRONO;
            case "ultra" -> ULTRA;
            default -> SP;
        };
    }

    public static int[] stakesSlots(String game) {
        return switch (game) {
            case "chrono" -> STAKES_CHRONO;
            case "ultra" -> STAKES_ULTRA;
            default -> STAKES_SP;
        };
    }

    public static String gameName(String game) {
        return switch (game) {
            case "chrono" -> "Chronomatron";
            case "ultra" -> "Ultrasequencer";
            default -> "Superpairs";
        };
    }

    public static int spStakesXp(Tier t) {
        return SP_STAKES_XP[SP.indexOf(t)];
    }

    // ---- fillers ----

    /** Purple panes on the border, black inside (table and stakes). */
    static Item[] framed(int rows) {
        Item[] s = new Item[rows * 9];
        for (int i = 0; i < s.length; i++) {
            int r = i / 9, c = i % 9;
            s[i] = Item.pane(r == 0 || r == rows - 1 || c == 0 || c == 8 ? "purple" : "black");
        }
        return s;
    }

    static Item[] black(int rows) {
        Item[] s = new Item[rows * 9];
        Arrays.fill(s, Item.pane("black"));
        return s;
    }

    // ---- Experimentation Table ----

    public static Item[] table(Daily d) {
        Item[] s = framed(6);
        Item chronoStatus = status(d.chrono), ultraStatus = status(d.ultra);
        s[20] = s[21] = chronoStatus;
        s[23] = s[24] = ultraStatus;
        String spLine = d.charges == 0 ? "§r§cExperiments on cooldown!" : !(d.chrono.done && d.ultra.done) ? "§r§dPlay add-ons first!" : "§r§eClick to play!";
        s[TABLE_SP] = Item.head(TEX_SUPERPAIRS, "§r§dSuperpairs", List.of("§r§8Main Experiment", "", "§r§7Find §r§bpairs §r§7of items on the grid to", "§r§7unlock them.", "",
            "§r§7Earn §r§3Enchanting Exp §r§7and §r§cpowerful", "§r§7enchanted books every day!", "", "§r§7Charges: §r§d" + d.charges + "§r§5/§r§d3",
            "§r§7Next charge in: §r§a12h", "", spLine));
        List<String> chrono = new ArrayList<>(List.of("§r§8Add-on Experiment", "", "§r§aRepeat §r§7the musical §r§dpattern §r§7to form the", "§r§7longest chain of notes.", "",
            "§r§7Earn extra §r§3Enchanting Exp §r§7and", "§r§7extra clicks on your next", "§r§dSuperpairs§r§7!", ""));
        chrono.addAll(addonTail(d, d.chrono));
        s[TABLE_CHRONO] = Item.head(TEX_CHRONO, "§r§dChronomatron", chrono);
        List<String> ultra = new ArrayList<>(List.of("§r§8Add-on Experiment", "", "§r§a1. §r§7Number(s) appear for 2 seconds.", "§r§e2. §r§7They all disappear!",
            "§r§c3. §r§7Click them in order from memory.", "", "§r§7Earn extra §r§3Enchanting Exp §r§7and", "§r§7extra clicks on your next", "§r§dSuperpairs§r§7!", ""));
        ultra.addAll(addonTail(d, d.ultra));
        s[TABLE_ULTRA] = Item.head(TEX_ULTRA, "§r§dUltrasequencer", ultra);
        if (d.charges == 0 && d.renewed < 3) {
            // k = 0 and 1 captured; k = 2 (150 levels, 450 bits) ASSUMED
            int levels = 50 * (d.renewed + 1), bits = 150 * (d.renewed + 1);
            s[TABLE_RENEW] = Item.of("blaze_powder", "§r§aRenew Experiments", "§r§7Purchase §r§a1 §r§7additional charge for", "§r§7the Experimentation Table.",
                "§r§8You've renewed " + d.renewed + "/3 charges today!", "", "§r§7Costs", "§r§b" + levels + " XP Levels", "§r§b" + bits + " Bits", "", "§r§eClick to purchase!").withGlint(true);
        }
        s[TABLE_RNG] = Item.head(TEX_SUPERPAIRS, "§r§dExperimentation Table RNG Meter", List.of("§r§7Your §r§dExperimentation Table RNG", "§r§dMeter §r§7fills with §r§3Enchanting XP §r§7every",
            "§r§7time you complete your §r§dSuperpairs§r§7!", "", "§r§7Your selected §r§dRNG Drop §r§7will be", "§r§7guaranteed to appear in your next",
            "§r§dSuperpairs§r§7 once your RNG Meter is", "§r§7full!", "", "§r§7Selected Drop", "§r§aLooting V", "", "§r§7Progress: §r§d5.3§r§5%",
            "§r§d§l§m  §r§f§l§m                       §r §r§d26,545§r§5/§r§d500k", "", "§r§eClick to view!"));
        if (!d.rngSelected.equals(Captured.RECORDED_SELECTED)) {
            // ASSUMED: the table's meter head mirrors the RNG pages' head for another selected drop
            List<String> lore = new ArrayList<>(Captured.get().rngPage(1, d.rngSelected)[4].lore());
            lore.addAll(List.of("", "§r§eClick to view!"));
            s[TABLE_RNG] = s[TABLE_RNG].withLore(lore);
        }
        s[TABLE_CLOSE] = Item.of("barrier", "§r§cClose");
        // experience_bottle glints by default: the captured item has no glint override in its patch
        s[TABLE_BOTTLES] = Item.of("experience_bottle", "§r§3Experience Bottles", "§r§7Missing experience?", "§r§7Simple! Just consume the §r§3Experience",
            "§r§3Bottles §r§7from your inventories", "§r§7directly.", "", "§r§eClick to view!");
        return s;
    }

    private static Item status(Daily.Addon a) {
        return a.done ? Item.of("pink_stained_glass_pane", "§r§aExperiment completed!", "§r§7Bonus: §r§d+" + a.bonus + " clicks").withGlint(true)
            : Item.of("pink_stained_glass_pane", "§r§7Pending experiment...");
    }

    /** On cooldown when claimed this charge or with no charge left (captured at 0 charges with pending panes). */
    public static boolean onCooldown(Daily d, Daily.Addon a) {
        return a.done || d.charges == 0;
    }

    private static List<String> addonTail(Daily d, Daily.Addon a) {
        if (!onCooldown(d, a)) return List.of("§r§eClick to play!");
        return d.practice ? List.of("§r§bRight-Click to practice!", "§r§cOn cooldown!") : List.of("§r§cOn cooldown!");
    }

    // ---- Stakes ----

    public static int stakesRows(String game) {
        return game.equals("sp") ? 6 : 5;
    }

    public static int stakesBack(String game) {
        return game.equals("sp") ? 48 : 39;
    }

    public static Item[] stakes(Daily d, String game) {
        int rows = stakesRows(game);
        Item[] s = framed(rows);
        s[stakesBack(game)] = Item.of("arrow", "§r§aGo Back", "§r§7To Experimentation Table");
        s[stakesBack(game) + 1] = Item.of("barrier", "§r§cClose");
        List<Tier> tiers = tiers(game);
        int[] slots = stakesSlots(game);
        for (int i = 0; i < tiers.size(); i++) s[slots[i]] = stakesTier(d, tiers.get(i));
        return s;
    }

    static Item stakesTier(Daily d, Tier t) {
        List<String> lore = new ArrayList<>();
        boolean meta = t.name.equals("Metaphysical");
        switch (t.game) {
            case "chrono" -> {
                lore.addAll(List.of("§r§8Chronomatron", "", "§r§7Colors on board: §r§d" + t.board, "", "§r§7XP Reward: §r§3" + t.xpLabel + " Enchanting Exp",
                    "§r §r§7per §r§enote in longest chain§r§7!", "", "§r§7Superpairs Rewards:", "§r §r§7Chain of §r§e2§r§7: §r§a+1 Click", "§r §r§7Chain of §r§e6§r§7: §r§a+2 Clicks"));
                if (meta) lore.add("§r §r§7Chain of §r§e9§r§7: §r§a+3 Clicks");
                lore.addAll(List.of("", "§r§7Requires: §r§3Enchanting " + t.req));
                if (d.chrono.declined) lore.add("§r§7Starting cost: §r§b" + t.cost + " XP Levels");
            }
            case "ultra" -> {
                lore.addAll(List.of("§r§8Ultrasequencer", "", "§r§7Grid size: §r§d" + t.board, "", "§r§7XP Reward: §r§3" + t.xpLabel + " Enchanting Exp",
                    "§r §r§7per §r§6number in highest series§r§7!", "", "§r§7Superpairs Rewards:", "§r §r§7Series of §r§e2§r§7: §r§a+1 Click", "§r §r§7Series of §r§e4§r§7: §r§a+2 Clicks"));
                if (meta) lore.add("§r §r§7Series of §r§e6§r§7: §r§a+3 Clicks");
                lore.addAll(List.of("", "§r§7Requires: §r§3Enchanting " + t.req));
                if (d.ultra.declined) lore.add("§r§7Starting cost: §r§b" + t.cost + " XP Levels");
            }
            default -> {
                int i = SP.indexOf(t);
                int addons = (d.chrono.bonus + d.ultra.bonus) * (meta ? 2 : 1);
                lore.addAll(List.of("§r§8Superpairs", "", "§r§7Grid Size: §r§d" + t.board, "", "§r§7Possible pairs:"));
                lore.addAll(SP_PAIRS.get(i));
                lore.add("");
                if (!SP_RARE.get(i).isEmpty()) {
                    lore.addAll(SP_RARE.get(i));
                    lore.add("");
                }
                lore.addAll(List.of("§r§7Clicks: §r§d§l" + (t.xp + addons + d.serum), "§r §r§d+" + t.xp + " §r§7default",
                    "§r §r§d+" + addons + " §r§5add-ons" + (meta ? " §r§8(doubled!)" : ""), "§r §r§d+" + d.serum + " §r§eserum", "",
                    "§r§7Requires: §r§3Enchanting " + t.req, "§r§7Starting cost: §r§b" + t.cost + " XP Levels"));
            }
        }
        lore.add("");
        lore.add(statusLine(d.status(t.game, t.name)));
        return Item.of(t.dye, "§r§" + t.code + t.name + " Experiment", lore.toArray(String[]::new));
    }

    public static String statusLine(String status) {
        return switch (status) {
            case "low" -> "§r§cEnchanting level too low!";
            case "xp" -> "§r§cNot enough experience!";
            default -> "§r§eClick to play!";
        };
    }

    // ---- Experiment Over / Superpairs Rewards ----

    public static Item[] over(String game, Tier tier, String reason, int chain, int xp, int clicks) {
        Item[] s = black(3);
        List<String> lore = new ArrayList<>();
        if (game.equals("chrono")) {
            lore.addAll(List.of("§r§8Claim", "", reason, "", "§r§7Stakes: " + tier.colored(), "", "§r§7Longest Chain: §r§a" + chain + (chain == 1 ? " note" : " notes"), ""));
        } else {
            lore.addAll(List.of(reason, "", "§r§7Stakes: " + tier.colored(), "§r§7Highest series: §r§a" + chain + (chain == 1 ? " number" : " numbers"), ""));
        }
        lore.add("§r§7Rewards:");
        lore.add("§r§8 +§r§3" + thousands(xp) + " Enchanting Exp");
        if (clicks > 0) lore.add("§r§8 +§r§d" + clicks + " Superpairs click" + (clicks == 1 ? "" : "s"));
        lore.addAll(List.of("", "§r§eClick to claim rewards!"));
        s[OVER_HEAD] = Item.head(game.equals("chrono") ? TEX_CHRONO : TEX_ULTRA, "§r§d" + gameName(game), lore);
        s[OVER_DECLINE] = Item.of("redstone_block", "§r§cDecline", "§r§7Do you think you can do better", "§r§7on this experiment?", "", "§r§7Decline your rewards to play one",
            "§r§7more time today!", "", "§r§cNote: Subsequent attempts today", "§r§cwill cost XP levels.", "", "§r§eClick to decline rewards!");
        return s;
    }

    public static List<String> overChat(String game, int xp, int clicks) {
        List<String> chat = new ArrayList<>(List.of("§r§eYou claimed the §r§d" + gameName(game) + " §r§erewards!", "§r §r§8+§r§3" + thousands(xp) + " Enchanting Exp"));
        if (clicks > 0) chat.add("§r§8 +§r§d" + clicks + " Superpairs click" + (clicks == 1 ? "" : "s"));
        return chat;
    }

    /** {@code items} are reward names as colored strings (a book's enchant line, a card's name). */
    public static Item[] rewards(Tier tier, int used, int pairs, int pairsXp, List<String> items) {
        Item[] s = black(3);
        List<String> lore = new ArrayList<>(List.of("§r§8Experiment over", "", "§r§7Stakes: " + tier.colored(), "", "§r§7Clicks used: §r§a" + used, "§r§7Pairs found: §r§e" + pairs, "",
            "§r§7Rewards:", "§r§8 +§r§3" + thousands(spStakesXp(tier)) + " Enchanting Exp (Stakes)", "§r§8 +§r§3" + thousands(pairsXp) + " Enchanting Exp (Pairs)"));
        for (String item : items) lore.add("§r§8 +§r§8 " + item);
        lore.addAll(List.of("", "§r§eClick to claim rewards!"));
        s[REWARDS_HEAD] = Item.head(TEX_SUPERPAIRS, "§r§dSuperpairs", lore);
        return s;
    }

    /** Claim chat (captured order: stakes XP, then one line per pair reward; the skill level-up block is left out). */
    public static List<String> rewardsChat(Tier tier, List<String> items, List<Integer> xpPairs) {
        List<String> chat = new ArrayList<>(List.of("§r§eYou claimed the §r§" + tier.code + "Superpairs §r§erewards!", "§r§8 +§r§3" + kilo(spStakesXp(tier)) + " Enchanting Exp"));
        for (String item : items) chat.add("§r §r§8+" + item);
        for (int k : xpPairs) chat.add("§r §r§8+§r§3" + kilo(k) + " Enchanting Exp");
        return chat;
    }

    public static String thousands(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    static String kilo(int n) {
        return n % 1000 == 0 ? n / 1000 + "k" : thousands(n);
    }

    // ---- Renew ----

    public static String renewChat(int k) {
        return "§r§d☕ §r§eYou bought a bonus charge for the Experimentation Table! §r§b(" + k + "/3)";
    }

    public static final String MAX_CLICKS_CHAT = "§r§eYou have reached the maximum extra Superpairs clicks from this add-on!";
}
