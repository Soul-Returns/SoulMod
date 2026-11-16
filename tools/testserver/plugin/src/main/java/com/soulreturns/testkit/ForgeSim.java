package com.soulreturns.testkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

/**
 * Hypixel's Dwarven Mines forge as captured on 2026-11-16 (docs/research/forge-2026-11-16.md §1, §4a, §4b):
 * {@code The Forge}, {@code Select Process (Slot #N)}, the nine category pages, {@code Confirm Process} and the
 * {@code Heart of the Mountain} Quick Forge perk, all real container menus whose items carry their SkyBlock
 * {@code custom_data} id. The 123 recipes, ingredients and items come from {@code forge-data.json}
 * ({@code tools/testserver/gen-forge-data.py}). State per player: Quick Forge level, the seven slots, locked recipes.
 *
 * <p>Not captured, so not simulated: a finished (claimable) process, a locked forge slot, the Forge Recipes book, the
 * search sign, Claim All with something to claim, a lime Confirm that starts a process (use {@code hx forge start}).
 */
public final class ForgeSim implements Listener {
    static final int FORGE_SLOTS = 7;
    private static final int[] CONFIRM_INPUTS = {10, 11, 19, 20, 28, 29, 37, 38};
    private static final boolean[] GRID = new boolean[54];
    private static final String DEFAULT_REQUIRES = "Requires Heart of the Mountain Tier 8";
    private static final String USAGE = "forge open | hotm | qf <0-20> | slots <free|three|chamber> | lock <OUTPUT_ID…|none|default>"
        + " | start <slot 1-7> <OUTPUT_ID> | status | pack <on|off>";

    static {
        for (int r = 1; r <= 4; r++) for (int c = 1; c <= 7; c++) GRID[r * 9 + c] = true;
    }

    record Entry(String sb, String material, String name, List<String> lore, List<String> confirm, String texture,
                 boolean glint, String tooltip, String petType, String petTier) {}

    record Ing(String key, String name, int amount, long coins) {}

    record Recipe(String id, String category, int page, int slot, List<Ing> ing, long base) {}

    record Cat(String name, int slot, Entry item) {}

    record Data(List<Cat> categories, Entry search, String coinTexture, Map<String, Entry> items, List<Recipe> recipes,
                Map<String, Recipe> byId, Map<String, String> locked, Map<Integer, String> qfCosts) {}

    /** A running process: Hypixel keeps the start, the end follows the current Quick Forge level. */
    record Process(String recipe, long startMillis) {}

    private static final class State {
        int qf = 20;
        final Process[] slots = new Process[FORGE_SLOTS];
        final Map<String, String> locked = new LinkedHashMap<>();
    }

    private enum Kind { MAIN, PICKER, CATEGORY, CONFIRM, HOTM }

    private static final class Holder implements InventoryHolder {
        final Kind kind;
        final int forgeSlot;
        final String category;
        final int page;
        final Recipe recipe;
        Inventory inventory;

        Holder(Kind kind, int forgeSlot, String category, int page, Recipe recipe) {
            this.kind = kind;
            this.forgeSlot = forgeSlot;
            this.category = category;
            this.page = page;
            this.recipe = recipe;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SoulTestKit plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final ModelPack pack = new ModelPack();
    private Data data;
    private ItemStack pane;
    private ItemStack redPane;
    private BukkitTask ticker;

    ForgeSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    /** Time Remaining counts down once per second, as the real menu's slot updates do. */
    void enable() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void disable() {
        if (ticker != null) ticker.cancel();
        ticker = null;
        pack.stop();
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder h && h.kind == Kind.MAIN) {
                renderBusy(player, h);
            }
        }
    }

    // ---- data ----

    private Data data() {
        if (data == null) data = load();
        return data;
    }

    private static Data load() {
        JsonObject root;
        try (InputStream in = ForgeSim.class.getResourceAsStream("/forge-data.json")) {
            if (in == null) throw new IllegalStateException("forge-data.json missing from the jar (python3 tools/testserver/gen-forge-data.py)");
            root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException("forge-data.json: " + e.getMessage(), e);
        }
        List<Cat> categories = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("categories")) {
            JsonObject o = e.getAsJsonObject();
            Entry item = entry(o);
            categories.add(new Cat(Txt.strip(item.name()), o.get("slot").getAsInt(), item));
        }
        Map<String, Entry> items = new LinkedHashMap<>();
        for (var e : root.getAsJsonObject("items").entrySet()) items.put(e.getKey(), entry(e.getValue().getAsJsonObject()));
        List<Recipe> recipes = new ArrayList<>();
        Map<String, Recipe> byId = new LinkedHashMap<>();
        for (JsonElement e : root.getAsJsonArray("recipes")) {
            JsonObject o = e.getAsJsonObject();
            List<Ing> ing = new ArrayList<>();
            for (JsonElement i : o.getAsJsonArray("ing")) {
                JsonObject io = i.getAsJsonObject();
                ing.add(io.has("coins") ? new Ing(null, null, 1, io.get("coins").getAsLong())
                    : new Ing(io.get("k").getAsString(), io.get("n").getAsString(), io.get("a").getAsInt(), 0));
            }
            Recipe recipe = new Recipe(o.get("id").getAsString(), o.get("cat").getAsString(), o.get("page").getAsInt(),
                o.get("slot").getAsInt(), List.copyOf(ing), o.get("base").getAsLong());
            recipes.add(recipe);
            byId.put(recipe.id(), recipe);
        }
        Map<String, String> locked = new LinkedHashMap<>();
        for (var e : root.getAsJsonObject("locked").entrySet()) locked.put(e.getKey(), e.getValue().getAsString());
        Map<Integer, String> costs = new HashMap<>();
        for (var e : root.getAsJsonObject("quickForgeCosts").entrySet()) costs.put(Integer.parseInt(e.getKey()), e.getValue().getAsString());
        return new Data(List.copyOf(categories), entry(root.getAsJsonObject("search")), root.get("coinTexture").getAsString(),
            items, List.copyOf(recipes), byId, locked, costs);
    }

    private static Entry entry(JsonObject o) {
        JsonObject pet = o.has("pet") ? o.getAsJsonObject("pet") : null;
        return new Entry(str(o, "sb"), o.get("material").getAsString(), o.get("name").getAsString(), strings(o.getAsJsonArray("lore")),
            o.has("confirm") ? strings(o.getAsJsonArray("confirm")) : null, str(o, "texture"),
            o.has("glint") && o.get("glint").getAsBoolean(), str(o, "tooltip"),
            pet == null ? null : pet.get("type").getAsString(), pet == null ? null : pet.get("tier").getAsString());
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : null;
    }

    private static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        array.forEach(e -> out.add(e.getAsString()));
        return out;
    }

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> {
            State state = new State();
            state.locked.putAll(data().locked());
            return state;
        });
    }

    // ---- items ----

    private static String skull(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ItemStack ui(String material, String name, String... lore) {
        JsonObject def = new JsonObject();
        def.addProperty("material", material);
        def.addProperty("name", name);
        if (lore.length > 0) {
            JsonArray lines = new JsonArray();
            for (String line : lore) lines.add(line);
            def.add("lore", lines);
        }
        return Items.build(null, def, 1);
    }

    /** An item of the table with the given lore (the captured one unless replaced) and stack size. */
    private static ItemStack build(Entry e, int count, List<String> lore) {
        JsonObject def = new JsonObject();
        def.addProperty("material", e.material());
        def.addProperty("name", e.name());
        JsonArray lines = new JsonArray();
        lore.forEach(lines::add);
        def.add("lore", lines);
        if (e.glint()) def.addProperty("glint", true);
        if (e.texture() != null) def.addProperty("skullTexture", skull(e.texture()));
        if (e.sb() != null) def.addProperty("id", e.sb());
        if (e.petType() != null) {
            JsonObject pet = new JsonObject();
            pet.addProperty("type", e.petType());
            pet.addProperty("tier", e.petTier());
            def.add("petInfo", pet);
        }
        ItemStack item = Items.build(null, def, count);
        if (e.tooltip() != null) item.setData(DataComponentTypes.TOOLTIP_STYLE, Key.key("hypixel_skyblock", e.tooltip()));
        return item;
    }

    private ItemStack pane() {
        if (pane == null) pane = ui("black_stained_glass_pane", " ");
        return pane;
    }

    private ItemStack redPane() {
        if (redPane == null) redPane = ui("red_stained_glass_pane", " ");
        return redPane;
    }

    private static String plainName(String name) {
        return Txt.strip(name).replaceFirst("^\\[Lvl \\d+\\] ", "");
    }

    private static String coins(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    private Entry entry(String key) {
        Entry e = data().items().get(key);
        if (e == null) throw new IllegalStateException("forge-data.json has no item " + key);
        return e;
    }

    // ---- opening ----

    private Holder holder(Kind kind, int forgeSlot, String category, int page, Recipe recipe, String title, Player player) {
        Holder h = new Holder(kind, forgeSlot, category, page, recipe);
        h.inventory = Bukkit.createInventory(h, 54, Txt.of(title));
        render(player, h);
        player.openInventory(h.inventory);
        return h;
    }

    /** {@code hx menu forge}: The Forge. */
    public void open(Player player) {
        holder(Kind.MAIN, 0, null, 0, null, "The Forge", player);
    }

    /** {@code hx menu hotm}: Heart of the Mountain with Quick Forge at slot 24. */
    public void openHotm(Player player) {
        holder(Kind.HOTM, 0, null, 0, null, "Heart of the Mountain", player);
    }

    private void openPicker(Player player, int forgeSlot) {
        holder(Kind.PICKER, forgeSlot, null, 0, null, "Select Process (Slot #" + forgeSlot + ")", player);
    }

    private int pages(String category) {
        return data().recipes().stream().filter(r -> r.category().equals(category)).mapToInt(Recipe::page).max().orElse(1);
    }

    private void openCategory(Player player, int forgeSlot, String category, int page) {
        int pages = pages(category);
        holder(Kind.CATEGORY, forgeSlot, category, page, null, pages > 1 ? "(" + page + "/" + pages + ") " + category : category, player);
    }

    private void openConfirm(Player player, int forgeSlot, Recipe recipe) {
        holder(Kind.CONFIRM, forgeSlot, recipe.category(), recipe.page(), recipe, "Confirm Process", player);
    }

    // ---- rendering ----

    private void render(Player player, Holder h) {
        Inventory inv = h.inventory;
        State st = state(player);
        inv.clear();
        switch (h.kind) {
            case MAIN -> renderMain(inv, st);
            case PICKER -> renderPicker(inv, h);
            case CATEGORY -> renderCategory(inv, st, h);
            case CONFIRM -> renderConfirm(inv, h);
            case HOTM -> renderHotm(inv, st);
        }
    }

    private void fill(Inventory inv) {
        for (int i = 0; i < 54; i++) inv.setItem(i, pane());
    }

    private void renderMain(Inventory inv, State st) {
        fill(inv);
        for (int row : new int[] {19, 28, 37}) {
            for (int c = 0; c < 7; c++) inv.setItem(row + c, redPane());
        }
        for (int n = 1; n <= FORGE_SLOTS; n++) inv.setItem(9 + n, forgeSlot(st, n));
        inv.setItem(48, ui("chest", "§aClaim All", "§7Claims all of your items that have", "§7finished forging.", "", "§cNothing to claim!"));
        inv.setItem(49, ui("barrier", "§cClose"));
        inv.setItem(50, book(st));
    }

    private ItemStack forgeSlot(State st, int n) {
        Process p = st.slots[n - 1];
        if (p == null) {
            return ui("furnace", "§aSlot #" + n, "§7View and start forge processes", "§7using the materials that you have.", "",
                "§eClick to select a process!");
        }
        Recipe r = data().byId().get(p.recipe());
        Entry e = entry(r.id());
        long left = ForgeModel.remainingSeconds(r.base(), st.qf, System.currentTimeMillis() - p.startMillis());
        return build(e, 1, List.of("§7Currently making: §r" + e.name(), "§7Time Remaining: §r§a" + ForgeModel.duration(left)));
    }

    private void renderBusy(Player player, Holder h) {
        State st = state(player);
        for (int n = 1; n <= FORGE_SLOTS; n++) {
            if (st.slots[n - 1] != null) h.inventory.setItem(9 + n, forgeSlot(st, n));
        }
    }

    private ItemStack book(State st) {
        int total = data().recipes().size();
        int unlocked = total - st.locked.size();
        int percent = ForgeModel.bookPercent(unlocked, total);
        int green = ForgeModel.barGreen(percent);
        StringBuilder bar = new StringBuilder();
        for (int i = 0; i < ForgeModel.BAR_SEGMENTS; i++) {
            if (i > 0) bar.append("§r");
            bar.append(i < green ? "§2§m " : "§f§m ");
        }
        bar.append("§r §r§e").append(unlocked).append("§r§6/§r§e").append(total);
        return ui("book", "§6Forge Recipes", "§7View all §r§6Forge Recipes §r§7and their", "§7requirements to unlock.", "",
            "§7Recipe Book Unlocked: §r§" + (percent == 100 ? "a" : "e") + percent + "§r§6%", bar.toString(), "", "§eClick to view!");
    }

    private void renderPicker(Inventory inv, Holder h) {
        fill(inv);
        for (Cat c : data().categories()) inv.setItem(c.slot(), build(c.item(), 1, c.item().lore()));
        inv.setItem(48, ui("arrow", "§aGo Back", "§7To The Forge"));
        inv.setItem(49, ui("barrier", "§cClose"));
        inv.setItem(50, build(data().search(), 1, data().search().lore()));
    }

    private void renderCategory(Inventory inv, State st, Holder h) {
        for (int i = 0; i < 54; i++) if (!GRID[i]) inv.setItem(i, pane());
        for (Recipe r : data().recipes()) {
            if (r.category().equals(h.category) && r.page() == h.page) inv.setItem(r.slot(), recipeItem(st, r));
        }
        inv.setItem(48, ui("arrow", "§aGo Back", "§7To Select Process (Slot #" + h.forgeSlot + ")"));
        inv.setItem(49, ui("barrier", "§cClose"));
        if (h.page > 1) inv.setItem(45, ui("arrow", "§aPrevious Page", "§ePage " + (h.page - 1)));
        if (h.page < pages(h.category)) inv.setItem(53, ui("arrow", "§aNext Page", "§ePage " + (h.page + 1)));
    }

    private ItemStack recipeItem(State st, Recipe r) {
        Entry e = entry(r.id());
        String requires = st.locked.get(r.id());
        if (requires != null) return ui("gray_dye", "§c" + plainName(e.name()), "§c" + requires);
        List<String> lore = new ArrayList<>(e.lore());
        lore.add("");
        lore.add("§eItems Required");
        for (Ing i : r.ing()) lore.add(ingredientLine(i));
        lore.add("");
        lore.add("§7Duration: §r§b" + ForgeModel.duration(ForgeModel.shownSeconds(r.base(), st.qf)));
        lore.add("");
        lore.add("§eClick to select!");
        return build(e, 1, lore);
    }

    private static String ingredientLine(Ing i) {
        if (i.key() == null) return "§6" + coins(i.coins()) + " Coins";
        return i.name() + (i.amount() > 1 ? " §r§8x" + i.amount() : "");
    }

    private void renderConfirm(Inventory inv, Holder h) {
        fill(inv);
        Recipe r = h.recipe;
        int next = 0;
        for (Ing i : r.ing()) {
            int slot = CONFIRM_INPUTS[next++];
            if (i.key() == null) {
                JsonObject def = new JsonObject();
                def.addProperty("material", "PLAYER_HEAD");
                def.addProperty("name", "§6" + coins(i.coins()) + " Coins");
                def.addProperty("skullTexture", skull(data().coinTexture()));
                inv.setItem(slot, Items.build(null, def, 1));
            } else {
                Entry e = entry(i.key());
                inv.setItem(slot, build(e, i.amount(), e.confirm() != null ? e.confirm() : e.lore()));
            }
        }
        inv.setItem(13, ui("furnace", "§aForge Slot #" + h.forgeSlot, "§f<§r§f§m §r§f§m §r§e Required Items",
            "     §r§eResult Item §r§f§m §r§f§m §r§f>"));
        Entry out = entry(r.id());
        inv.setItem(16, build(out, 1, out.lore()));
        inv.setItem(31, ui("red_terracotta", "§aConfirm", "§cYou don't have the required items!"));
        inv.setItem(48, ui("arrow", "§aGo Back", "§7To " + r.category()));
        inv.setItem(49, ui("barrier", "§cClose"));
    }

    private void renderHotm(Inventory inv, State st) {
        fill(inv);
        inv.setItem(24, quickForge(st.qf));
        inv.setItem(45, ui("barrier", "§cClose"));
        inv.setItem(48, ui("arrow", "§aGo Back", "§7To SkyBlock Menu"));
    }

    /** The perk in its three captured shapes: maxed (diamond), levelling (emerald), not unlocked (coal). */
    private ItemStack quickForge(int level) {
        String description1 = "§7Decreases the time it takes to forge";
        if (level <= 0) {
            return ui("coal", "§cQuick Forge", "§7Level 1§r§8/20", "", description1,
                "§7by §r§a" + ForgeModel.percentText(ForgeModel.percent(1)) + "%§r§7.", "", "§7Cost", "§51 §r§5Token of the Mountain", "",
                "§cRequires Pickobulus");
        }
        if (level >= ForgeModel.MAX_LEVEL) {
            return ui("diamond", "§aQuick Forge", "§7Level 20", "", description1,
                "§7by §r§a" + ForgeModel.percentText(ForgeModel.percent(20)) + "%§r§7.", "", "§a§lENABLED", "",
                "§eRight-click to §r§cdisable§r§e!");
        }
        int up = level + 1;
        return ui("emerald", "§eQuick Forge",
            "§7Level " + level + "§r§8/20", "", description1, "§7by §r§a" + ForgeModel.percentText(ForgeModel.percent(level)) + "%§r§7.", "",
            "§a=====[ §r§a§lUPGRADE §r§a]=====",
            up == ForgeModel.MAX_LEVEL ? "§7Level 20" : "§7Level " + up + "§r§8/20", "", description1,
            "§7by §r§a" + ForgeModel.percentText(ForgeModel.percent(up)) + "%§r§7.", "", "§7Cost",
            "§2" + data().qfCosts().get(up) + " Mithril Powder", "", "§a§lENABLED", "", "§eRight-click to §r§cdisable§r§e!",
            "§eLeft-click to upgrade!", "§eShift Left-click to upgrade 10 levels!");
    }

    /** Re-renders the open forge menu of a player after a state change. */
    private void refresh(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof Holder h) render(player, h);
    }

    // ---- clicks ----

    @EventHandler(ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Holder h)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        Bukkit.getScheduler().runTask(plugin, () -> clicked(player, h, slot));
    }

    @EventHandler(ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Holder) event.setCancelled(true);
    }

    private void clicked(Player player, Holder h, int slot) {
        State st = state(player);
        if (slot == 49 && h.kind != Kind.HOTM) {
            player.closeInventory();
            return;
        }
        switch (h.kind) {
            case MAIN -> {
                if (slot >= 10 && slot <= 16 && st.slots[slot - 10] == null) openPicker(player, slot - 9);
            }
            case PICKER -> {
                if (slot == 48) open(player);
                else if (slot == 50) chat(player, "§cThe forge search sign is not simulated.");
                else data().categories().stream().filter(c -> c.slot() == slot).findFirst()
                    .ifPresent(c -> openCategory(player, h.forgeSlot, c.name(), 1));
            }
            case CATEGORY -> {
                if (slot == 48) openPicker(player, h.forgeSlot);
                else if (slot == 45 && h.page > 1) openCategory(player, h.forgeSlot, h.category, h.page - 1);
                else if (slot == 53 && h.page < pages(h.category)) openCategory(player, h.forgeSlot, h.category, h.page + 1);
                else {
                    for (Recipe r : data().recipes()) {
                        if (!r.category().equals(h.category) || r.page() != h.page || r.slot() != slot) continue;
                        String requires = st.locked.get(r.id());
                        if (requires != null) chat(player, "§c" + requires);
                        else openConfirm(player, h.forgeSlot, r);
                    }
                }
            }
            case CONFIRM -> {
                if (slot == 48) openCategory(player, h.forgeSlot, h.category, h.page);
            }
            case HOTM -> {
                if (slot == 45) player.closeInventory();
                else if (slot == 48) plugin.menus().open(player, "skyblock_menu");
            }
        }
    }

    private static void chat(Player player, String line) {
        Nms.sendSystemChat(player, Txt.of(line), false);
    }

    // ---- /hx forge ----

    List<String> command(Player player, String[] args, CommandSender sender) {
        if (args.length == 0) throw new IllegalArgumentException("usage: /hx " + USAGE);
        State st = state(player);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> {
                open(player);
                return List.of("The Forge opened");
            }
            case "hotm" -> {
                openHotm(player);
                return List.of("Heart of the Mountain opened");
            }
            case "qf" -> {
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx forge qf <0-20>");
                int level = integer(args[1]);
                if (level < 0 || level > ForgeModel.MAX_LEVEL) throw new IllegalArgumentException("Quick Forge level 0-20");
                st.qf = level;
                refresh(player);
                return List.of("Quick Forge level " + level + " = " + ForgeModel.percentText(ForgeModel.percent(level)) + "% (durations and running processes follow it)");
            }
            case "slots" -> {
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx forge slots <free|three|chamber>");
                long now = System.currentTimeMillis();
                Arrays.fill(st.slots, null);
                switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "free" -> {}
                    case "three" -> {
                        // Different remaining times: elapsed 30 min, 4 h 20 min and 8 h 40 min.
                        st.slots[0] = new Process("REFINED_TITANIUM", now - 1_800_000L);
                        st.slots[1] = new Process("REFINED_TITANIUM", now - 15_600_000L);
                        st.slots[2] = new Process("MITHRIL_PLATE", now - 31_200_000L);
                    }
                    case "chamber" -> {
                        // 35m 5s at 30 %, whatever the level is now: the start lies 5 s ahead of the shown duration.
                        Recipe chamber = recipe("GEMSTONE_CHAMBER");
                        long elapsed = ForgeModel.shownSeconds(chamber.base(), 20) - (35 * 60 + 5);
                        for (int i = 0; i < FORGE_SLOTS; i++) st.slots[i] = new Process(chamber.id(), now - elapsed * 1_000);
                    }
                    default -> throw new IllegalArgumentException("usage: /hx forge slots <free|three|chamber>");
                }
                refresh(player);
                return List.of("slots " + args[1].toLowerCase(Locale.ROOT));
            }
            case "lock" -> {
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx forge lock <OUTPUT_ID…|none|default>");
                st.locked.clear();
                if (args.length == 2 && args[1].equalsIgnoreCase("default")) st.locked.putAll(data().locked());
                else if (!(args.length == 2 && args[1].equalsIgnoreCase("none"))) {
                    for (int i = 1; i < args.length; i++) {
                        String id = recipe(args[i]).id();
                        st.locked.put(id, data().locked().getOrDefault(id, DEFAULT_REQUIRES));
                    }
                }
                refresh(player);
                return List.of(st.locked.size() + " locked recipe(s)" + (st.locked.isEmpty() ? "" : ": " + String.join(", ", st.locked.keySet())));
            }
            case "start" -> {
                if (args.length < 3) throw new IllegalArgumentException("usage: /hx forge start <slot 1-7> <OUTPUT_ID>");
                int slot = integer(args[1]);
                if (slot < 1 || slot > FORGE_SLOTS) throw new IllegalArgumentException("forge slot 1-7");
                Recipe r = recipe(args[2]);
                st.slots[slot - 1] = new Process(r.id(), System.currentTimeMillis());
                refresh(player);
                return List.of("slot " + slot + " busy with " + r.id() + ", " + ForgeModel.duration(ForgeModel.shownSeconds(r.base(), st.qf)));
            }
            case "status" -> {
                List<String> out = new ArrayList<>();
                out.add("Quick Forge level " + st.qf + " = " + ForgeModel.percentText(ForgeModel.percent(st.qf)) + "%, recipe book "
                    + (data().recipes().size() - st.locked.size()) + "/" + data().recipes().size() + ", locked: "
                    + (st.locked.isEmpty() ? "none" : String.join(", ", st.locked.keySet())));
                for (int n = 1; n <= FORGE_SLOTS; n++) {
                    Process p = st.slots[n - 1];
                    if (p == null) {
                        out.add("#" + n + " idle");
                        continue;
                    }
                    Recipe r = data().byId().get(p.recipe());
                    long left = ForgeModel.remainingSeconds(r.base(), st.qf, System.currentTimeMillis() - p.startMillis());
                    out.add("#" + n + " " + r.id() + " " + ForgeModel.duration(left) + " left (base " + ForgeModel.duration(r.base()) + ")");
                }
                return out;
            }
            case "pack" -> {
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx forge pack <on|off>");
                return switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "on" -> List.of(pack.send(player));
                    case "off" -> List.of(pack.remove(player));
                    default -> throw new IllegalArgumentException("usage: /hx forge pack <on|off>");
                };
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
    }

    private Recipe recipe(String id) {
        Recipe r = data().byId().get(id.toUpperCase(Locale.ROOT));
        if (r == null) throw new IllegalArgumentException("unknown forge output id " + id + " (e.g. REFINED_DIAMOND, TITANIUM_DRILL_4, MOLE;4)");
        return r;
    }

    private static int integer(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a number: " + s);
        }
    }

    /** Tab completion for the words after {@code hx forge}; {@code pos} 1 = the subcommand. */
    List<String> completions(int pos, String[] args) {
        if (pos == 1) return List.of("open", "hotm", "qf", "slots", "lock", "start", "status", "pack");
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        return switch (sub) {
            case "qf" -> pos == 2 ? IntStream.rangeClosed(0, ForgeModel.MAX_LEVEL).mapToObj(String::valueOf).toList() : List.of();
            case "slots" -> pos == 2 ? List.of("free", "three", "chamber") : List.of();
            case "pack" -> pos == 2 ? List.of("on", "off") : List.of();
            case "lock" -> {
                List<String> ids = new ArrayList<>(Arrays.asList("none", "default"));
                ids.addAll(data().byId().keySet());
                yield ids;
            }
            case "start" -> pos == 2 ? IntStream.rangeClosed(1, FORGE_SLOTS).mapToObj(String::valueOf).toList()
                : pos == 3 ? List.copyOf(data().byId().keySet()) : List.of();
            default -> List.of();
        };
    }
}
