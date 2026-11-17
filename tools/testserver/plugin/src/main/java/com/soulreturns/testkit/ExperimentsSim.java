package com.soulreturns.testkit;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.soulreturns.testkit.experiments.Click;
import com.soulreturns.testkit.experiments.Item;
import com.soulreturns.testkit.experiments.MenuItems;
import com.soulreturns.testkit.experiments.Packet;
import com.soulreturns.testkit.experiments.SelfTest;
import com.soulreturns.testkit.experiments.Session;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.key.Key;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.component.CustomData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

/**
 * The Experimentation Table as Hypixel sends it (docs/research/experiments-2026-11-17.md): table, stakes, the three
 * games, Experiment Over / Superpairs Rewards and the RNG Meter pages. The rules live in the pure
 * {@code testkit/experiments} package; this class only puts its packets on the wire. There is no server-side menu:
 * screens are raw open_screen / set_slot / set_content / container_close packets with Hypixel's own container ids,
 * and a tap in the Netty pipeline takes the client's container clicks and closes for them off the wire.
 * Command: {@code /hx exp}.
 */
public final class ExperimentsSim implements Listener {
    private static final String TAP = "soul_exp_tap";

    /** One player's table: the pure session (engine, wire, what the client was sent) and its connection. */
    final class Sim {
        final Player player;
        final Session session;
        private final boolean live;
        /** Container ids this sim opened: their clicks and closes are taken off the wire. */
        final ArrayDeque<Integer> cids = new ArrayDeque<>();
        private int stateId;

        Sim(Player player, long seed, boolean live) {
            this.player = player;
            this.live = live;
            this.session = new Session(seed, this::deliver);
            session.view.logging = !live;
        }

        boolean owns(int cid) {
            return cids.contains(cid);
        }

        /** Builds the packet always (the self-test covers the conversion), sends it only for a live sim. */
        private void deliver(Packet p) {
            if (p instanceof Packet.Open o) {
                cids.remove((Integer) o.cid());
                cids.addLast(o.cid());
                while (cids.size() > 8) cids.removeFirst();
            }
            boolean send = live && player != null && player.isOnline();
            switch (p) {
                case Packet.Sound s -> {
                    if (send) Terminals.sound(player, s.id(), SoundSource.valueOf(s.source()), s.volume(), s.pitch());
                }
                case Packet.Chat c -> {
                    var text = Txt.of(Terminals.wire(c.text()));
                    if (send) Nms.sendSystemChat(player, text, false);
                }
                default -> {
                    net.minecraft.network.protocol.Packet<?> nms = toNms(p);
                    if (send) ((CraftPlayer) player).getHandle().connection.send(nms);
                }
            }
        }

        private net.minecraft.network.protocol.Packet<?> toNms(Packet p) {
            return switch (p) {
                case Packet.Open o -> new ClientboundOpenScreenPacket(o.cid(), menuType(o.rows()), PaperAdventure.asVanilla(Txt.of(Terminals.wire(o.title()))));
                case Packet.Slot s -> new ClientboundContainerSetSlotPacket(s.cid(), ++stateId, s.slot(), nms(s.item()));
                case Packet.PlayerSlots ps -> playerSlots(ps);
                case Packet.Content c -> {
                    NonNullList<net.minecraft.world.item.ItemStack> items = NonNullList.create();
                    for (Item item : c.items()) items.add(nms(item));
                    for (int i = 0; i < 36; i++) items.add(inventory(i));
                    yield new ClientboundContainerSetContentPacket(c.cid(), ++stateId, items, net.minecraft.world.item.ItemStack.EMPTY);
                }
                case Packet.Close c -> new ClientboundContainerClosePacket(c.cid());
                default -> throw new IllegalArgumentException(p.toString());
            };
        }

        /** The open burst's player inventory part, as vanilla sends it: one set_slot per slot after the container's. */
        private net.minecraft.network.protocol.Packet<?> playerSlots(Packet.PlayerSlots ps) {
            List<net.minecraft.network.protocol.Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> out = new ArrayList<>();
            for (int i = 0; i < 36; i++) out.add(new ClientboundContainerSetSlotPacket(ps.cid(), ++stateId, ps.containerSize() + i, inventory(i)));
            return new net.minecraft.network.protocol.game.ClientboundBundlePacket(out);
        }

        /** Menu-order player slot i (0-26 main inventory, 27-35 hotbar). */
        private net.minecraft.world.item.ItemStack inventory(int i) {
            if (player == null) return net.minecraft.world.item.ItemStack.EMPTY;
            return ((CraftPlayer) player).getHandle().getInventory().getItem(i < 27 ? 9 + i : i - 27).copy();
        }
    }

    private final SoulTestKit plugin;
    private final Map<UUID, Sim> sims = new ConcurrentHashMap<>();
    private final Map<Item, net.minecraft.world.item.ItemStack> stacks = new HashMap<>();
    private BukkitTask ticker;

    public ExperimentsSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, () -> sims.values().forEach(s -> s.session.tick()), 1, 1);
        for (Player player : Bukkit.getOnlinePlayers()) tap(player);
    }

    public void disable() {
        if (ticker != null) ticker.cancel();
        for (Sim sim : sims.values()) {
            sim.session.engine.close();
            sim.session.flush();
        }
        sims.clear();
    }

    private Sim sim(Player player) {
        return sims.computeIfAbsent(player.getUniqueId(), id -> new Sim(player, ThreadLocalRandom.current().nextLong(), true));
    }

    // ---- items ----

    private static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /** The bridge-form item as a stack: names and lore as Hypixel's text, head textures, dye, glint, tooltip style, model, custom_data. */
    private net.minecraft.world.item.ItemStack nms(Item item) {
        if (item == null) return net.minecraft.world.item.ItemStack.EMPTY;
        if (stacks.size() > 4000) stacks.clear();
        return stacks.computeIfAbsent(item, ExperimentsSim::build).copy();
    }

    private static net.minecraft.world.item.ItemStack build(Item item) {
        Material material = Material.matchMaterial(item.id());
        if (material == null || !material.isItem()) throw new IllegalArgumentException("no item " + item.id());
        ItemStack stack = new ItemStack(material, Math.max(1, item.count()));
        ItemMeta meta = stack.getItemMeta();
        meta.customName(Txt.item(Terminals.wire(item.name())));
        if (!item.lore().isEmpty()) meta.lore(item.lore().stream().map(line -> Txt.item(Terminals.wire(line))).toList());
        if (item.glint() != null) meta.setEnchantmentGlintOverride(item.glint());
        if (item.dye() != null && meta instanceof LeatherArmorMeta leather) leather.setColor(Color.fromRGB(Integer.parseInt(item.dye().replace("#", ""), 16)));
        if (item.texture() != null && meta instanceof SkullMeta skull) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + item.texture() + "\"}}}";
            var profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(item.texture().getBytes(StandardCharsets.UTF_8)));
            profile.setProperty(new ProfileProperty("textures", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))));
            skull.setPlayerProfile(profile);
        }
        stack.setItemMeta(meta);
        // Hypixel shows its own stat lines; vanilla's attribute and dye tooltips never appear there.
        stack.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay()
            .addHiddenComponents(DataComponentTypes.ATTRIBUTE_MODIFIERS, DataComponentTypes.DYED_COLOR).build());
        if (item.tooltipStyle() != null) stack.setData(DataComponentTypes.TOOLTIP_STYLE, Key.key(item.tooltipStyle()));
        if (item.model() != null) stack.setData(DataComponentTypes.ITEM_MODEL, Key.key(item.model()));
        var nms = CraftItemStack.asNMSCopy(stack);
        if (item.customData() != null) {
            try {
                nms.set(DataComponents.CUSTOM_DATA, CustomData.of(TagParser.parseCompoundFully(item.customData())));
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                throw new IllegalArgumentException("custom_data " + item.customData() + ": " + e.getMessage());
            }
        }
        return nms;
    }

    // ---- input ----

    private void tap(Player player) {
        var channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        if (channel.pipeline().get(TAP) != null) return;
        channel.pipeline().addBefore("packet_handler", TAP, new Tap(player));
    }

    /** Takes the sim's container clicks and closes off the wire and, while frozen, holds every packet to the client. */
    private final class Tap extends ChannelDuplexHandler {
        private final Player player;
        private final ArrayDeque<Object[]> held = new ArrayDeque<>();
        private ChannelHandlerContext context;
        private long frozenUntil;

        Tap(Player player) {
            this.player = player;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            Sim sim = sims.get(player.getUniqueId());
            if (sim != null && msg instanceof ServerboundContainerClickPacket click && sim.owns(click.containerId())) {
                Bukkit.getScheduler().runTask(plugin, () -> onClickPacket(sim, click));
                return;
            }
            if (sim != null && msg instanceof ServerboundContainerClosePacket close && sim.owns(close.getContainerId())) {
                Bukkit.getScheduler().runTask(plugin, () -> onClosePacket(sim, close));
                return;
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
            if (System.nanoTime() < frozenUntil) {
                held.addLast(new Object[] {msg, promise});
                return;
            }
            super.write(ctx, msg, promise);
        }

        /** A lag spike as the client sees it: nothing arrives for [ms], then everything at once. */
        void freeze(long ms) {
            context.executor().execute(() -> {
                frozenUntil = System.nanoTime() + ms * 1_000_000L;
                context.executor().schedule(this::release, ms, java.util.concurrent.TimeUnit.MILLISECONDS);
            });
        }

        private void release() {
            if (System.nanoTime() < frozenUntil) return;
            while (!held.isEmpty()) {
                Object[] h = held.removeFirst();
                context.write(h[0], (io.netty.channel.ChannelPromise) h[1]);
            }
            context.flush();
        }
    }

    /** A container_click for one of the sim's containers, on the main thread. */
    void onClickPacket(Sim sim, ServerboundContainerClickPacket packet) {
        int button = packet.buttonNum();
        Click click = switch (packet.containerInput()) {
            case PICKUP -> button == 0 ? Click.LEFT : button == 1 ? Click.RIGHT : Click.OTHER;
            case CLONE -> Click.MIDDLE;
            case PICKUP_ALL -> Click.DOUBLE;
            // phases: 0 start and 2 end carry slot -999, header 1 adds one slot
            case QUICK_CRAFT -> (button & 3) == 1 ? Click.DRAG : Click.DRAG_EDGE;
            case QUICK_MOVE -> Click.SHIFT;
            default -> Click.OTHER;
        };
        sim.session.click(packet.containerId(), packet.slotNum(), click);
    }

    void onClosePacket(Sim sim, ServerboundContainerClosePacket packet) {
        sim.session.clientClose(packet.getContainerId());
    }

    /** What a client sends for [click] on slot [slot] of container [cid]. */
    static ServerboundContainerClickPacket clickPacket(int cid, int slot, Click click) {
        ContainerInput input = switch (click) {
            case LEFT, RIGHT -> ContainerInput.PICKUP;
            case MIDDLE -> ContainerInput.CLONE;
            case DOUBLE -> ContainerInput.PICKUP_ALL;
            case DRAG, DRAG_EDGE -> ContainerInput.QUICK_CRAFT;
            case SHIFT -> ContainerInput.QUICK_MOVE;
            case OTHER -> ContainerInput.SWAP;
        };
        int button = switch (click) {
            case RIGHT, DRAG -> 1;
            case MIDDLE -> 2;
            default -> 0;
        };
        return new ServerboundContainerClickPacket(cid, 0, (short) (click == Click.DRAG_EDGE ? -999 : slot), (byte) button, input,
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), HashedStack.EMPTY);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tap(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sims.remove(event.getPlayer().getUniqueId());
    }

    /** A real server menu replaces the sim's screen on the client. */
    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        Sim sim = sims.get(event.getPlayer().getUniqueId());
        if (sim == null) return;
        sim.session.engine.forget();
        sim.session.wire.clear();
        sim.cids.clear();
    }

    // ---- command ----

    static final String USAGE = "exp table|stakes|chrono|ultra|sp|over|rewards|rng|lag|freeze|split|resync|reopen|lateclick|status|close|selftest";

    List<String> command(Player player, String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("usage: /hx " + USAGE);
        Sim sim = sim(player);
        Session s = sim.session;
        var e = s.engine;
        String sub = args[0].toLowerCase(Locale.ROOT);
        Map<String, String> opt = options(args, 1);
        List<String> pos = positional(args, 1);
        List<String> out = new ArrayList<>();
        switch (sub) {
            case "table" -> {
                if (opt.containsKey("charges")) e.daily.charges = range(opt.get("charges"), 0, 3, "charges");
                if (opt.containsKey("renewed")) e.daily.renewed = range(opt.get("renewed"), 0, 3, "renewed");
                if (opt.containsKey("addons")) e.daily.addons(opt.get("addons"));
                if (opt.containsKey("practice")) e.daily.practice = onOff(opt.get("practice"));
                e.openTable();
                out.add("Experimentation Table: " + e.knobs());
            }
            case "stakes" -> {
                String game = game(pos, 0, "stakes chrono|ultra|sp [status=<Tier>:<play|low|xp>]");
                for (Map.Entry<String, String> o : opt.entrySet()) {
                    if (!o.getKey().startsWith("status")) continue;
                    String[] ts = o.getValue().split(":", 2);
                    if (ts.length != 2) throw new IllegalArgumentException("status=<Tier>:<play|low|xp>");
                    e.stakesStatus(game, ts[0], ts[1]);
                }
                e.openStakes(game, false);
                out.add(MenuItems.gameName(game) + " ➜ Stakes");
            }
            case "chrono", "ultra", "sp" -> {
                if (pos.isEmpty()) throw new IllegalArgumentException(sub + " <tier> [seed|recorded] [" + (sub.equals("sp") ? "clicks=N] [rare=none|meter|header|both|selected]" : "round=N]"));
                String tier = pos.get(0);
                String seed = pos.size() > 1 ? pos.get(1) : String.valueOf(ThreadLocalRandom.current().nextInt(1_000_000));
                int extra = sub.equals("sp") ? number(opt.getOrDefault("clicks", "0"), "clicks") : number(opt.getOrDefault("round", "1"), "round");
                String rare = opt.get("rare");
                if (rare != null && !List.of("none", "meter", "header", "both", "selected").contains(rare)) throw new IllegalArgumentException("rare=none|meter|header|both|selected");
                e.start(sub, tier, seed, extra, false, rare);
                out.add(e.describe() + " (seed " + seed + ")");
            }
            case "over" -> {
                String game = game(pos, 0, "over chrono|ultra <closed|wrong|time> <n> [tier=<Tier>]");
                if (game.equals("sp") || pos.size() < 3) throw new IllegalArgumentException("over chrono|ultra <closed|wrong|time> <n> [tier=<Tier>]");
                int n = number(pos.get(2), "n");
                String reason = switch (pos.get(1).toLowerCase(Locale.ROOT)) {
                    case "closed" -> game.equals("chrono") ? "§r§cYou closed the game!" : "§r§8Game closed";
                    case "wrong" -> game.equals("chrono") ? "§r§cYou picked the wrong color!" : "§r§8Clicked wrong number";
                    case "time" -> game.equals("chrono") ? "§r§cTime ran out!" : "§r§8Time ran out";
                    default -> throw new IllegalArgumentException("over reason closed|wrong|time");
                };
                // Ultrasequencer pays the round whose input had begun (every capture ended during an input: n + 1)
                e.openOver(game, MenuItems.tier(game, opt.getOrDefault("tier", "Metaphysical")), reason, n, game.equals("ultra") ? n + 1 : n, false);
                out.add("Experiment Over");
            }
            case "rewards" -> {
                // container 17's end: 25 clicks used, Titanic + 41k + 124k + 120k + 30k
                e.openRewards(MenuItems.tier("sp", opt.getOrDefault("tier", "Transcendent")), 25, 5, 315_000, List.of("§r§9Titanic Experience Bottle"),
                    List.of(41_000, 124_000, 120_000, 30_000), false);
                out.add("Superpairs Rewards (container 17's)");
            }
            case "rng" -> {
                if (opt.containsKey("selected")) {
                    String name = opt.get("selected").replace('_', ' ');
                    com.soulreturns.testkit.experiments.Captured.get().rngEntry(name);
                    e.daily.rngSelected = name;
                }
                e.openRng(pos.isEmpty() ? 1 : range(pos.get(0), 1, 3, "page"));
                out.add("RNG Meter, selected " + e.daily.rngSelected);
            }
            case "lag" -> {
                if (pos.isEmpty()) throw new IllegalArgumentException("lag <ms>");
                s.wire.lagMs = number(pos.get(0), "ms");
                out.add("lag " + s.wire.lagMs + " ms (" + s.wire.lagTicks() + " ticks round trip)");
            }
            case "freeze" -> {
                if (pos.isEmpty()) throw new IllegalArgumentException("freeze <ms>");
                var channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
                if (!(channel.pipeline().get(TAP) instanceof Tap tap)) throw new IllegalArgumentException("no tap on this connection");
                tap.freeze(number(pos.get(0), "ms"));
                out.add("frozen for " + pos.get(0) + " ms");
            }
            case "split" -> {
                s.wire.split = onOff(pos.isEmpty() ? "" : pos.get(0));
                out.add("split " + (s.wire.split ? "on" : "off"));
            }
            case "resync" -> {
                e.content();
                out.add("set_content sent");
            }
            case "reopen" -> out.add(e.reopen() ? "re-sent the open screen" : "nothing open");
            case "lateclick" -> {
                e.lateClick = onOff(pos.isEmpty() ? "" : pos.get(0));
                out.add("lateclick " + (e.lateClick ? "on" : "off"));
            }
            case "status" -> {
                out.add(e.describe());
                out.add(e.knobs() + ", lag " + s.wire.lagMs + " ms, split " + (s.wire.split ? "on" : "off") + ", rng " + e.daily.rngSelected);
                List<String> clicks = e.clicks();
                out.addAll(clicks.subList(Math.max(0, clicks.size() - 10), clicks.size()));
            }
            case "close" -> {
                e.close();
                out.add("closed");
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
        s.flush();
        return out;
    }

    /**
     * {@code /hx exp selftest}: the pure scenario suite on its own session, every click and close built as the
     * client's packet and taken through {@link #onClickPacket} / {@link #onClosePacket}, every answer converted to its
     * NMS packet (not sent: the player's screen stays as it is).
     */
    String selftest(Player player) {
        Sim sim = new Sim(player, 1, false);
        SelfTest test = new SelfTest(new SelfTest.Driver() {
            @Override
            public Session session() {
                return sim.session;
            }

            @Override
            public void click(int slot, Click click) {
                onClickPacket(sim, clickPacket(sim.session.view.cid, slot, click));
            }

            @Override
            public void close() {
                onClosePacket(sim, new ServerboundContainerClosePacket(sim.session.view.cid));
            }
        });
        String result = "experiments selftest: " + test.run();
        plugin.getLogger().info(result);
        for (String failure : test.failures()) plugin.getLogger().warning("experiments selftest " + failure);
        return result;
    }

    List<String> completions(int pos, String[] args) {
        if (pos == 1) return List.of("table", "stakes", "chrono", "ultra", "sp", "over", "rewards", "rng", "lag", "freeze", "split", "resync", "reopen", "lateclick", "status", "close", "selftest");
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (pos == 2) {
            return switch (sub) {
                case "table" -> List.of("charges=", "renewed=", "addons=pending", "addons=chrono", "addons=done", "practice=on", "practice=off");
                case "stakes" -> List.of("chrono", "ultra", "sp");
                case "chrono", "ultra", "sp" -> MenuItems.tiers(sub).stream().map(MenuItems.Tier::name).toList();
                case "over" -> List.of("chrono", "ultra");
                case "rng" -> List.of("1", "2", "3", "selected=Growth_VI", "selected=Looting_V");
                case "split", "lateclick" -> List.of("on", "off");
                default -> List.of();
            };
        }
        if (pos == 3) {
            return switch (sub) {
                case "chrono" -> List.of("c6", "c22", "c26", "round=");
                case "ultra" -> List.of("c10", "c30", "round=");
                case "sp" -> List.of("c17", "c36", "books", "instant", "plus3", "clicks=", "rare=meter", "rare=header", "rare=both", "rare=selected");
                case "over" -> List.of("closed", "wrong", "time");
                case "stakes" -> List.of("status=");
                default -> List.of();
            };
        }
        if (sub.equals("sp")) return List.of("clicks=", "rare=meter", "rare=header", "rare=both", "rare=selected", "rare=none");
        return List.of();
    }

    private static Map<String, String> options(String[] args, int from) {
        Map<String, String> out = new LinkedHashMap<>();
        int n = 0;
        for (int i = from; i < args.length; i++) {
            int eq = args[i].indexOf('=');
            if (eq <= 0) continue;
            String key = args[i].substring(0, eq).toLowerCase(Locale.ROOT);
            // repeated keys (status=…) stay distinct
            out.put(out.containsKey(key) ? key + "#" + n++ : key, args[i].substring(eq + 1));
        }
        return out;
    }

    private static List<String> positional(String[] args, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < args.length; i++) if (args[i].indexOf('=') <= 0) out.add(args[i]);
        return out;
    }

    private static String game(List<String> pos, int i, String usage) {
        String game = pos.size() > i ? pos.get(i).toLowerCase(Locale.ROOT) : "";
        if (!List.of("chrono", "ultra", "sp").contains(game)) throw new IllegalArgumentException(usage);
        return game;
    }

    private static int number(String raw, String what) {
        if (!raw.matches("\\d{1,7}")) throw new IllegalArgumentException(what + " must be a number");
        return Integer.parseInt(raw);
    }

    private static int range(String raw, int min, int max, String what) {
        int n = number(raw, what);
        if (n < min || n > max) throw new IllegalArgumentException(what + " " + min + ".." + max);
        return n;
    }

    private static boolean onOff(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("on|off");
        };
    }
}
