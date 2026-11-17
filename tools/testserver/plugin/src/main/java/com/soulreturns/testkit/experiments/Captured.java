package com.soulreturns.testkit.experiments;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * experiments/captured.json, cut from the recordings' fixtures (src/test/resources/experiments): the three
 * {@code (n/3) Experimentation Table RNG} pages as recorded (every slot's item JSON, incl. custom_data and
 * components) and the Superpairs boards of containers 17 and 36 (grid order, the cards as revealed in play).
 */
public final class Captured {
    public record RngPage(String title, Item[] slots) {}

    private static Captured instance;

    public final List<RngPage> rng = new ArrayList<>();
    public final List<Item> board17 = new ArrayList<>();
    public final List<Item> board36 = new ArrayList<>();

    private Captured(JsonObject root) {
        for (JsonElement page : root.getAsJsonArray("rng")) {
            JsonObject p = page.getAsJsonObject();
            Item[] slots = new Item[54];
            for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("slots").entrySet()) slots[Integer.parseInt(e.getKey())] = Item.fromBridge(e.getValue().getAsJsonObject());
            rng.add(new RngPage(p.get("title").getAsString(), slots));
        }
        JsonObject boards = root.getAsJsonObject("boards");
        boards.getAsJsonArray("c17").forEach(e -> board17.add(Item.fromBridge(e.getAsJsonObject())));
        boards.getAsJsonArray("c36").forEach(e -> board36.add(Item.fromBridge(e.getAsJsonObject())));
    }

    public static synchronized Captured get() {
        if (instance == null) {
            try (InputStream in = Captured.class.getResourceAsStream("/experiments/captured.json")) {
                if (in == null) throw new IllegalStateException("experiments/captured.json missing from the plugin jar");
                instance = new Captured(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return instance;
    }

    /** An RNG meter entry by its plain display name, e.g. "Thunderbolt VII". */
    public Item rngEntry(String plainName) {
        for (RngPage page : rng) {
            for (Item item : page.slots) if (item != null && item.plainName().equals(plainName)) return item;
        }
        throw new IllegalArgumentException("no RNG meter entry " + plainName);
    }

    /** The recorded selected drop and the meter XP of the RNG recording. */
    public static final String RECORDED_SELECTED = "Looting V";
    static final int METER_XP = 28_775;
    private static final Pattern ODDS = Pattern.compile("^(§r§7Odds: .*? §r§7\\()§r§7([\\d.]+)%\\)$");
    private static final Pattern REQUIRED = Pattern.compile("/(?:§r§d)?([\\d,]+)$");

    /**
     * Page [page] (1-based) with [selected] as the selected drop. The recorded selection (Looting V) is the page as
     * captured; any other selection is ASSUMED: that entry gets the captured selected form (base odds struck through,
     * current odds = base x (1 + 2 x progress), fitted to Looting V's 0.0263 → 0.0293 % at 5.8 %), Looting V the
     * unselected form, and the meter head (4) / Reset (51) name the new drop.
     */
    public Item[] rngPage(int page, String selected) {
        Item[] slots = rng.get(page - 1).slots().clone();
        if (selected.equals(RECORDED_SELECTED)) return slots;
        Item entry = rngEntry(selected);
        int required = required(entry);
        double progress = (double) METER_XP / required;
        String pct = String.format(Locale.ROOT, "%.1f", progress * 100);
        for (int i = 0; i < slots.length; i++) {
            Item item = slots[i];
            if (item == null) continue;
            if (i != 4 && item.plainName().equals(RECORDED_SELECTED) && item.plainLore().contains("SELECTED")) {
                slots[i] = item.withLore(List.of("§r§7Odds: §r§cRNGesus Incarnate §r§7(§r§70.0263%)", "", "§r§7Experimental XP: §r§d28,775§r§5/§r§d500,000", "", "§r§eClick to select!"));
            } else if (i != 4 && item.plainName().equals(selected) && !item.lore().isEmpty()) {
                Matcher m = ODDS.matcher(item.lore().get(0));
                if (!m.matches()) throw new IllegalStateException("odds line " + item.lore().get(0));
                double base = Double.parseDouble(m.group(2));
                String now = new BigDecimal(base * (1 + 2 * progress)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
                slots[i] = item.withLore(List.of(m.group(1) + "§r§8§m" + m.group(2) + "%§r §r§7" + now + "%)", "", "§r§7Progress: §r§d" + pct + "§r§5%", bar(progress, required), "",
                    "§r§7Filling the meter increases the drop", "§r§7chance of this item. Reaching §r§a100%", "§r§7will guarantee it to drop!", "", "§r§a§lSELECTED"));
            } else if (i == 4 || i == 51) {
                List<String> lore = new ArrayList<>(item.lore());
                int at = lore.indexOf("§r§7Selected Drop");
                if (at >= 0) lore.set(at + 1, entry.name());
                if (i == 4) {
                    lore.set(lore.size() - 2, "§r§7Progress: §r§d" + pct + "§r§5%");
                    lore.set(lore.size() - 1, bar(progress, required));
                }
                slots[i] = item.withLore(lore);
            }
        }
        return slots;
    }

    /** The meter bar as captured (25 struck spaces, the filled part pink) with "xp/required" in k. */
    static String bar(double progress, int required) {
        int filled = Math.max(1, Math.min(25, (int) Math.ceil(25 * progress)));
        return "§r§d§l§m" + " ".repeat(filled) + "§r§f§l§m" + " ".repeat(25 - filled) + "§r §r§d28,775§r§5/§r§d" + (required % 1000 == 0 ? required / 1000 + "k" : String.valueOf(required));
    }

    /** An unselected entry's required XP ("Experimental XP: 28,775/150,000"). */
    static int required(Item entry) {
        for (String line : entry.lore()) {
            if (!line.contains("Experimental XP")) continue;
            Matcher m = REQUIRED.matcher(line);
            if (m.find()) return Integer.parseInt(m.group(1).replace(",", ""));
        }
        throw new IllegalArgumentException(entry.plainName() + " has no Experimental XP line (pick an unselected entry)");
    }
}
