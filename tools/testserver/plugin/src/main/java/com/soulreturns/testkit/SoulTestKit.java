package com.soulreturns.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.soulreturns.testkit.modapi.ModApiBridge;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** SoulTestKit: makes a local Paper server look like Hypixel SkyBlock to a SoulMod client. */
public final class SoulTestKit extends JavaPlugin implements Listener {
    public static final String TESTER = "SoulTester";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final List<String> GAMERULES = List.of(
        "advance_time false", "advance_weather false", "keep_inventory true", "spawn_phantoms false",
        "spawn_patrols false", "spawn_wandering_traders false", "fall_damage false", "show_advancement_messages false");

    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Presets presets = new Presets();
    private ModApiBridge modApi;
    private Menus menus;
    private Sacks sacks;
    private final Mobs mobs = new Mobs();
    private final MetalDetectorSim metalDetector = new MetalDetectorSim(this);
    private final NucleusSim nucleus = new NucleusSim(this);
    private final StormSim storm = new StormSim(this);
    private final LateBossSim late = new LateBossSim(this);
    private final Terminals terminals = new Terminals(this);
    private final ExperimentsSim exp = new ExperimentsSim(this);
    private final SimonSaysSim simonSays = new SimonSaysSim(this);
    private final LightsSim lights = new LightsSim(this);
    private final ArrowAlignSim arrows = new ArrowAlignSim(this);
    private final GoldorStartSim goldor = new GoldorStartSim(this);
    private final SharpshooterSim sharp = new SharpshooterSim(this);
    private final LeapSim leap = new LeapSim(this);
    private final StorageSim storage = new StorageSim(this);
    private final ForgeSim forge = new ForgeSim(this);
    private final DevBridgeSim devBridge = new DevBridgeSim(this);
    private final CorpseSim corpses = new CorpseSim(this);
    private final PangolinSim pangolins = new PangolinSim(this);
    private final WorldLoader worlds = new WorldLoader(this);
    private final MarkMarkers marks = new MarkMarkers(this);

    @Override
    public void onEnable() {
        reloadPresets();
        modApi = new ModApiBridge(this);
        modApi.register();
        menus = new Menus(this);
        sacks = new Sacks(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(sacks, this);
        Bukkit.getPluginManager().registerEvents(storm, this);
        Bukkit.getPluginManager().registerEvents(mobs, this);
        Bukkit.getPluginManager().registerEvents(terminals, this);
        Bukkit.getPluginManager().registerEvents(exp, this);
        Bukkit.getPluginManager().registerEvents(simonSays, this);
        Bukkit.getPluginManager().registerEvents(lights, this);
        Bukkit.getPluginManager().registerEvents(arrows, this);
        Bukkit.getPluginManager().registerEvents(sharp, this);
        Bukkit.getPluginManager().registerEvents(leap, this);
        Bukkit.getPluginManager().registerEvents(storage, this);
        Bukkit.getPluginManager().registerEvents(forge, this);
        forge.enable();
        terminals.enable();
        exp.enable();
        var command = getCommand("hx");
        var hx = new HxCommand(this);
        command.setExecutor(hx);
        command.setTabCompleter(hx);
        var party = new PartyCommand(this);
        getCommand("party").setExecutor(party);
        getCommand("pc").setExecutor(party);
        for (String rule : GAMERULES) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule " + rule);
        for (World world : Bukkit.getWorlds()) {
            // Only the overworld has a world clock in 26.x; setTime throws for the others.
            if (world.getEnvironment() == World.Environment.NORMAL) world.setTime(6000);
            world.setStorm(false);
            world.setThundering(false);
        }
        for (Player player : Bukkit.getOnlinePlayers()) onJoin(player);
    }

    @Override
    public void onDisable() {
        storm.shutdown();
        late.stop();
        simonSays.stop();
        lights.stop();
        arrows.stop();
        goldor.stop();
        sharp.stop();
        states.values().forEach(s -> { if (s.actionbarTask != null) s.actionbarTask.cancel(); });
        terminals.disable();
        exp.disable();
        forge.disable();
        corpses.disable();
        pangolins.disable();
        mobs.clear();
        worlds.disable();
        marks.clear();
    }

    public String reloadPresets() {
        try {
            presets.load(getDataFolder().toPath().resolve("presets.json"));
            return null;
        } catch (IOException | RuntimeException e) {
            getLogger().severe("presets.json: " + e.getMessage());
            return e.getMessage();
        }
    }

    public Presets presets() {
        return presets;
    }

    public ModApiBridge modApi() {
        return modApi;
    }

    public Menus menus() {
        return menus;
    }

    public StorageSim storage() {
        return storage;
    }

    public ForgeSim forge() {
        return forge;
    }

    public Sacks sacks() {
        return sacks;
    }

    public Mobs mobs() {
        return mobs;
    }

    public MetalDetectorSim metalDetector() {
        return metalDetector;
    }

    public WorldLoader worlds() {
        return worlds;
    }

    public MarkMarkers marks() {
        return marks;
    }

    public CorpseSim corpses() {
        return corpses;
    }

    public PangolinSim pangolins() {
        return pangolins;
    }

    public DevBridgeSim devBridge() {
        return devBridge;
    }

    public NucleusSim nucleus() {
        return nucleus;
    }

    public StormSim storm() {
        return storm;
    }

    public LateBossSim late() {
        return late;
    }

    public Terminals terminals() {
        return terminals;
    }

    public ExperimentsSim exp() {
        return exp;
    }

    public SimonSaysSim simonSays() {
        return simonSays;
    }

    public LightsSim lights() {
        return lights;
    }

    public ArrowAlignSim arrows() {
        return arrows;
    }

    GoldorStartSim goldor() {
        return goldor;
    }

    public SharpshooterSim sharp() {
        return sharp;
    }

    public LeapSim leap() {
        return leap;
    }

    public PlayerState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new PlayerState());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
        onJoin(event.getPlayer());
    }

    private void onJoin(Player player) {
        if (player.getName().equals(TESTER) && !player.isOp()) player.setOp(true);
        // Hypixel lists only its fake rows; hide the newcomer from everyone already showing a tab preset.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer != player && state(viewer).tab != null) Nms.setListed(viewer, player.getUniqueId(), false);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            PlayerState state = state(player);
            modApi.sendHello(player);
            if (!state.initialised) {
                state.initialised = true;
                applyPreset(player, presets.string("defaultPreset", "hub"));
            } else {
                restore(player, state);
            }
        }, 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        event.quitMessage(null);
        PlayerState state = state(event.getPlayer());
        if (state.actionbarTask != null) state.actionbarTask.cancel();
        state.actionbarTask = null;
        state.tabEntries = new ArrayList<>();
        state.subscriptions.clear();
        state.helloSent = false;
    }

    @EventHandler
    public void onFood(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }

    /** Re-sends the display state a rejoining player had (the location goes out when it re-registers). */
    private void restore(Player player, PlayerState state) {
        if (state.sidebar != null) applySidebar(player, state.sidebar);
        if (state.tab != null) applyTab(player, state.tab);
        if (state.actionbar != null) applyActionbar(player, state.actionbar);
        // Hypixel prints its join lines (profile name, profile id) on every SkyBlock server join.
        JsonElement preset = state.preset == null ? null : presets.get("presets", state.preset);
        if (preset != null && preset.getAsJsonObject().has("chat")) applyChat(player, preset.getAsJsonObject().get("chat").getAsString());
    }

    // ---------------------------------------------------------------- appliers; null = unknown name

    public String applyPreset(Player player, String name) {
        JsonElement element = presets.get("presets", name);
        if (element == null) return "unknown preset " + name + " (" + String.join(", ", presets.keys("presets")) + ")";
        JsonObject preset = element.getAsJsonObject();
        PlayerState state = state(player);
        state.preset = name;
        List<String> problems = new ArrayList<>();
        if (preset.has("location") && !applyLocation(player, preset.get("location").getAsString())) problems.add("location");
        if (preset.has("sidebar") && !applySidebar(player, preset.get("sidebar").getAsString())) problems.add("sidebar");
        if (preset.has("tab") && !applyTab(player, preset.get("tab").getAsString())) problems.add("tab");
        if (preset.has("actionbar")) applyActionbar(player, preset.get("actionbar").getAsString());
        if (preset.has("chat") && !applyChat(player, preset.get("chat").getAsString())) problems.add("chat");
        return problems.isEmpty() ? null : "preset " + name + " names unknown " + problems;
    }

    /** System chat lines from "chats", sent in order like Hypixel's join block. */
    public boolean applyChat(Player player, String name) {
        JsonElement element = presets.get("chats", name);
        if (element == null) return false;
        element.getAsJsonArray().forEach(l -> Nms.sendSystemChat(player, Txt.of(fill(player, l.getAsString())), false));
        return true;
    }

    public void reset(Player player) {
        PlayerState state = state(player);
        state.preset = null;
        clearActionbar(player);
        if (state.tab != null) TabList.clear(player, state);
        state.tab = null;
        Sidebar.clear(player);
        state.sidebar = null;
        applyLocation(player, presets.string("resetLocation", "lobby"));
    }

    /** Named location from presets.json; sends hyevent:location when the client subscribed. */
    public boolean applyLocation(Player player, String name) {
        JsonElement element = presets.get("locations", name);
        if (element == null) return false;
        setLocation(player, element.getAsJsonObject().deepCopy());
        return true;
    }

    public boolean setLocation(Player player, JsonObject location) {
        state(player).location = location;
        // Hypixel switches servers for every location change; the sidebar's server line follows it.
        PlayerState state = state(player);
        if (state.sidebar != null) applySidebar(player, state.sidebar);
        if (state.tab != null) applyTab(player, state.tab);
        return modApi.sendLocation(player);
    }

    public boolean applySidebar(Player player, String name) {
        JsonElement element = presets.get("sidebars", name);
        if (element == null) return false;
        JsonObject sidebar = element.getAsJsonObject();
        List<String> lines = new ArrayList<>();
        sidebar.getAsJsonArray("lines").forEach(l -> lines.add(fill(player, l.getAsString())));
        Sidebar.show(player, fill(player, sidebar.get("title").getAsString()), lines);
        state(player).sidebar = name;
        return true;
    }

    public void clearSidebar(Player player) {
        Sidebar.clear(player);
        state(player).sidebar = null;
    }

    public boolean applyTab(Player player, String name) {
        JsonElement element = presets.get("tabs", name);
        if (element == null) return false;
        JsonObject tab = element.getAsJsonObject();
        List<List<String>> columns = new ArrayList<>();
        tab.getAsJsonArray("columns").forEach(c -> {
            List<String> column = new ArrayList<>();
            c.getAsJsonArray().forEach(l -> column.add(l.getAsString()));
            columns.add(column);
        });
        PlayerState state = state(player);
        if (state.petLines != null) replacePetLines(columns, state.petLines);
        TabList.show(player, state, str(tab, "header"), str(tab, "footer"), columns, s -> fill(player, s));
        state.tab = name;
        return true;
    }

    /** Swap the body of the first {@code Pet:} widget (lines up to the next blank) for {@code lines}. */
    private static void replacePetLines(List<List<String>> columns, List<String> lines) {
        for (List<String> column : columns) {
            for (int i = 0; i < column.size(); i++) {
                if (!column.get(i).replaceAll("§.", "").startsWith("Pet:")) continue;
                int end = i + 1;
                while (end < column.size() && !column.get(end).isEmpty()) end++;
                column.subList(i + 1, end).clear();
                column.addAll(i + 1, lines);
                return;
            }
        }
    }

    public void clearTab(Player player) {
        PlayerState state = state(player);
        TabList.clear(player, state);
        state.tab = null;
    }

    /** A preset name from "actionbars" or literal text; repeated every second like Hypixel's stat bar. */
    public void applyActionbar(Player player, String presetOrText) {
        clearActionbar(player);
        JsonElement preset = presets.get("actionbars", presetOrText);
        String text = preset != null ? preset.getAsString() : presetOrText;
        PlayerState state = state(player);
        state.actionbar = presetOrText;
        state.actionbarTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (player.isOnline()) Nms.sendSystemChat(player, Txt.of(fill(player, text)), true);
        }, 0L, 20L);
    }

    public void clearActionbar(Player player) {
        PlayerState state = state(player);
        if (state.actionbarTask != null) {
            state.actionbarTask.cancel();
            Nms.sendSystemChat(player, Txt.of(""), true);
        }
        state.actionbarTask = null;
        state.actionbar = null;
    }

    /** {player} {date} {server} {serverShort} */
    public String fill(Player player, String text) {
        String server = state(player).serverName();
        return text.replace("{player}", player.getName())
            .replace("{date}", LocalDate.now().format(DATE))
            .replace("{serverShort}", shortServer(server))
            .replace("{server}", server);
    }

    /** Sidebar form of a server name: mini12AB -> m12AB, mega5 -> M5. */
    static String shortServer(String server) {
        if (server.startsWith("mini")) return "m" + server.substring(4);
        if (server.startsWith("mega")) return "M" + server.substring(4);
        return server;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsString() : "";
    }
}
