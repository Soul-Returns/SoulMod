package com.soulreturns.testkit;

import com.soulreturns.testkit.terminal.Cell;
import com.soulreturns.testkit.terminal.Fixtures;
import com.soulreturns.testkit.terminal.MelodyTerminal;
import com.soulreturns.testkit.terminal.OrderTerminal;
import com.soulreturns.testkit.terminal.PanesTerminal;
import com.soulreturns.testkit.terminal.Pools;
import com.soulreturns.testkit.terminal.RubixTerminal;
import com.soulreturns.testkit.terminal.SelectTerminal;
import com.soulreturns.testkit.terminal.StartsWithTerminal;
import com.soulreturns.testkit.terminal.Terminal;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.ContainerInput;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;

/**
 * The six Floor 7 terminals as real container menus, behaving like the native capture
 * (docs/research/f7-native-terminals-2026-11-12.md; the rules live in {@link Terminal} and its subclasses).
 * A real client sees exactly what Hypixel sends: window title, size, items, the success pling plus slot echo
 * after the server's response delay, the error sound and chat line on wrong input, server close and the
 * completion line with a running counter. Command: {@code /hx terminal}.
 */
public final class Terminals implements Listener {
    static final String PLING = "block.note_block.pling";
    static final String ERROR = "entity.enderman.teleport";
    private static final String TAP = "soul_terminal_tap";

    /** One open terminal. Also the inventory holder, so clicks find their terminal. */
    static final class Session implements InventoryHolder {
        final Player player;
        final Terminal terminal;
        final long seed;
        final List<String> events = new ArrayList<>();
        Inventory inventory;
        volatile int containerId = -1;
        volatile boolean open = true;

        Session(Player player, Terminal terminal, long seed) {
            this.player = player;
            this.terminal = terminal;
            this.seed = seed;
        }

        void log(String event) {
            if (events.size() < 400) events.add(event);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final SoulTestKit plugin;
    private final Map<UUID, Session> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, int[]> counters = new HashMap<>();
    /**
     * Round trip like a real connection: 150 ms is the captured 82-202 ms (median 164) from click to pling and echo.
     * Half of it delays the click's arrival (a melody lock is judged against the lane at arrival, as on Hypixel), the
     * other half every answer and animation step on its way to the client; 0 answers in the same tick.
     */
    int lagMs = 150;

    private long lagTicks() {
        return lagMs <= 0 ? 0 : Math.max(1, (lagMs + 49) / 50);
    }

    private long upTicks() {
        return lagTicks() / 2;
    }

    private long downTicks() {
        return lagTicks() - upTicks();
    }

    /** Runs [action] once the click has travelled to the server. */
    private void arrive(Runnable action) {
        long up = upTicks();
        if (sync || up == 0) action.run();
        else Bukkit.getScheduler().runTaskLater(plugin, action, up);
    }
    int total = 7;
    private boolean worldClicks = true;
    /** Self-test mode: effects, including the close, run inline instead of after the lag or on the next tick. */
    boolean sync;
    private BukkitTask ticker;

    public Terminals(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAll, 1, 1);
        for (Player player : Bukkit.getOnlinePlayers()) tap(player);
    }

    public void disable() {
        if (ticker != null) ticker.cancel();
        for (Session s : List.copyOf(sessions.values())) {
            s.open = false;
            if (s.player.getOpenInventory().getTopInventory().getHolder(false) == s) s.player.closeInventory();
        }
        sessions.clear();
    }

    // ---- opening ----

    Session open(Player player, Terminal terminal, long seed) {
        Session session = new Session(player, terminal, seed);
        session.inventory = Bukkit.createInventory(session, terminal.size(), Txt.of(wire(terminal.title())));
        for (int slot = 0; slot < terminal.size(); slot++) session.inventory.setItem(slot, TerminalItems.stack(terminal.cell(slot)));
        Session previous = sessions.put(player.getUniqueId(), session);
        if (previous != null) previous.open = false;
        player.openInventory(session.inventory);
        session.containerId = ((CraftPlayer) player).getHandle().containerMenu.containerId;
        sound(player, "block.lever.click", SoundSource.BLOCKS, 0.5f, 1.0f);
        return session;
    }

    /** A fresh random board; the same kind, argument and seed give the same board. */
    Session openRandom(Player player, Terminal.Kind kind, String argument, long seed) {
        Random rng = new Random(seed);
        Terminal terminal = switch (kind.key) {
            case "startswith" -> StartsWithTerminal.random(rng, argument == null || argument.isEmpty() ? 0 : argument.charAt(0));
            case "select" -> SelectTerminal.random(rng, argument == null ? null : Pools.hueByTitle(argument));
            case "panes" -> PanesTerminal.random(rng);
            case "order" -> OrderTerminal.random(rng);
            case "rubix" -> RubixTerminal.random(rng);
            default -> MelodyTerminal.random(rng);
        };
        return open(player, terminal, seed);
    }

    Session openCaptured(Player player, int cid) {
        return open(player, Fixtures.build(cid, new Random(cid)), cid);
    }

    Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    // ---- input ----

    /**
     * Container clicks of an open terminal never reach vanilla or Bukkit: a tap in the player's Netty pipeline takes
     * them off the wire, so the server's answer comes only after the response delay and the client's own prediction
     * (picked-up stack on the cursor, emptied slot) lasts that long, as against a remote server. That also lets the
     * client's real QUICK_CRAFT drag phases through to {@link #onClickPacket}, which Bukkit would swallow (the
     * server-side cursor is empty): Hypixel accepts the header-1 phase on a board slot as a forward action (captured).
     */
    private void tap(Player player) {
        var channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
        if (channel.pipeline().get(TAP) != null) return;
        channel.pipeline().addBefore("packet_handler", TAP, new Tap(player));
    }

    /** Takes terminal clicks off the wire and, while frozen, holds every packet to the client. */
    private final class Tap extends ChannelDuplexHandler {
        private final Player player;
        private final java.util.ArrayDeque<Object[]> held = new java.util.ArrayDeque<>();
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
            if (msg instanceof ServerboundContainerClickPacket packet) {
                Session session = sessions.get(player.getUniqueId());
                if (session != null && session.open && session.containerId == packet.containerId()) {
                    Bukkit.getScheduler().runTask(plugin, () -> onClickPacket(player, packet));
                    return;
                }
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

        /** A server-wide lag spike as the client sees it: nothing at all arrives for [ms], then everything at once. */
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

    /** A container click packet of the player's open terminal, on the main thread. */
    void onClickPacket(Player player, ServerboundContainerClickPacket packet) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.open || session.containerId != packet.containerId()) return;
        int slot = packet.slotNum();
        ContainerInput kind = packet.containerInput();
        if (kind == ContainerInput.QUICK_CRAFT) {
            // Phases: 0 start and 2 end carry slot -999; header 1 adds one slot.
            if (((packet.buttonNum() >> 2) & 3) != 1) return;
            session.log("drag " + slot);
            arrive(() -> handle(session, slot, Terminal.Input.DRAG));
            return;
        }
        Terminal.Input input;
        if (kind == ContainerInput.PICKUP) input = packet.buttonNum() == 0 ? Terminal.Input.LEFT : packet.buttonNum() == 1 ? Terminal.Input.RIGHT : Terminal.Input.OTHER;
        else if (kind == ContainerInput.PICKUP_ALL) input = Terminal.Input.DOUBLE;
        else if (kind == ContainerInput.CLONE) input = Terminal.Input.MIDDLE;
        else input = Terminal.Input.OTHER;
        arrive(() -> handle(session, slot, input));
    }

    /** A click packet for the open terminal, for the self-test to drive {@link #onClickPacket} like a client. */
    ServerboundContainerClickPacket packet(Session session, int slot, ContainerInput kind, int button) {
        return new ServerboundContainerClickPacket(session.containerId, 0, (short) slot, (byte) button, kind,
            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY);
    }

    /** Bukkit never sees a terminal click (the tap takes them), but should one arrive anyway nothing may move. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Session) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Session) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder(false) instanceof Session session)) return;
        session.open = false;
        sessions.remove(session.player.getUniqueId(), session);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        tap(event.getPlayer());
    }

    // ---- model to effects ----

    void handle(Session session, int slot, Terminal.Input input) {
        if (!session.open) return;
        // Clicks on the player's own inventory or outside the window: no answer from the terminal, the client is only put back in sync.
        if (slot < 0 || slot >= session.terminal.size()) {
            answer(session, Terminal.Reply.IGNORED, slot, null);
            return;
        }
        Terminal.Reply reply = session.terminal.click(slot, input);
        answer(session, reply, slot, session.terminal.cell(slot));
    }

    private void tickAll() {
        for (Session session : List.copyOf(sessions.values())) tick(session);
    }

    void tick(Session session) {
        if (!session.open) return;
        Terminal.Reply reply = session.terminal.tick();
        if (reply.outcome() == Terminal.Outcome.UPDATE) respond(session, reply, true, -1, null);
    }

    private void answer(Session session, Terminal.Reply reply, int slot, Cell clicked) {
        respond(session, reply, false, slot, clicked);
    }

    /**
     * Applies a model reply. Every reply, animation steps included, reaches the client after the down half of the
     * round trip. A click's answer ends with the client's cursor put back to empty and, when the clicked slot did
     * not change, that slot re-sent (it was emptied by the client's own prediction).
     */
    private void respond(Session session, Terminal.Reply reply, boolean animation, int slot, Cell clicked) {
        boolean closes = reply.outcome() == Terminal.Outcome.COMPLETED || reply.outcome() == Terminal.Outcome.FAIL_CLOSE;
        Runnable effects = () -> {
            Player player = session.player;
            if (!player.isOnline()) return;
            switch (reply.outcome()) {
                case UPDATE -> setSlots(session, reply.changes());
                case ACCEPTED -> {
                    for (int i = 0; i < reply.plings(); i++) pling(session);
                    setSlots(session, reply.changes());
                }
                case COMPLETED -> {
                    for (int i = 0; i < reply.plings(); i++) pling(session);
                    complete(session);
                }
                case FAIL_CLOSE, FAIL_STAY -> {
                    session.log("error");
                    sound(player, ERROR, SoundSource.HOSTILE, 8.0f, 0.0f);
                    if (reply.chat() != null) chat(session, reply.chat());
                    if (reply.outcome() == Terminal.Outcome.FAIL_CLOSE) close(session);
                }
                default -> {
                }
            }
            if (!animation && !closes && session.open) resync(session, slot, clicked, reply.changes().containsKey(slot));
        };
        long ticks = downTicks();
        if (sync || ticks == 0) effects.run();
        else Bukkit.getScheduler().runTaskLater(plugin, effects, ticks);
    }

    private void resync(Session session, int slot, Cell clicked, boolean echoed) {
        var connection = ((CraftPlayer) session.player).getHandle().connection;
        connection.send(new ClientboundSetCursorItemPacket(net.minecraft.world.item.ItemStack.EMPTY));
        if (echoed || clicked == null || slot < 0 || slot >= session.terminal.size()) return;
        var menu = ((CraftPlayer) session.player).getHandle().containerMenu;
        connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
            menu.containerId, menu.incrementStateId(), slot, org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(TerminalItems.stack(clicked))));
    }

    private void setSlots(Session session, Map<Integer, Cell> changes) {
        if (!session.open) return;
        changes.forEach((slot, cell) -> {
            session.inventory.setItem(slot, TerminalItems.stack(cell));
            session.log("slot " + slot + " " + cell.id() + (cell.selected() ? " selected" : ""));
        });
    }

    private void pling(Session session) {
        session.log("pling");
        sound(session.player, PLING, SoundSource.BLOCKS, 8.0f, 4.047619f);
    }

    private void chat(Session session, String line) {
        session.log("chat " + Txt.strip(line));
        Nms.sendSystemChat(session.player, Txt.of(wire(line)), false);
    }

    private void close(Session session) {
        session.log("close");
        if (session.open && session.player.getOpenInventory().getTopInventory().getHolder(false) == session) session.player.closeInventory();
    }

    /** Completion as captured: server close, chat line with the running counter, empty title with the same as subtitle. */
    private void complete(Session session) {
        Player player = session.player;
        int[] counter = counters.computeIfAbsent(player.getUniqueId(), id -> new int[] {0, total});
        counter[1] = total;
        counter[0] = counter[0] >= counter[1] ? 1 : counter[0] + 1;
        session.log("complete " + counter[0] + "/" + counter[1]);
        close(session);
        chat(session, "§r§6" + player.getName() + "§r§a activated a terminal! (§r§c" + counter[0] + "§r§a/" + counter[1] + ")");
        player.showTitle(Title.title(Component.empty(),
            Txt.of(wire("§r§6" + player.getName() + "§a activated a terminal! (§c" + counter[0] + "§a/" + counter[1] + ")"))));
    }

    /**
     * The terminal strings (docs/research/f7-native-terminals-2026-11-12.md) are the dev bridge's rendering, which
     * puts one {@code §r} in front of every styled run. The text Hypixel sends does not carry that first {@code §r},
     * so it is dropped here and a client's bridge shows the doc's string again.
     */
    static String wire(String bridgeText) {
        return bridgeText.startsWith("§r") ? bridgeText.substring(2) : bridgeText;
    }

    int count(Player player) {
        int[] counter = counters.get(player.getUniqueId());
        return counter == null ? 0 : counter[0];
    }

    void setCounter(Player player, int count, int total) {
        counters.put(player.getUniqueId(), new int[] {count, total});
        this.total = total;
    }

    /** A sound packet exactly as Hypixel sends them: at the player, with the given source, volume and pitch (no clamping). */
    static void sound(Player player, String id, SoundSource source, float volume, float pitch) {
        var at = player.getLocation();
        var event = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse(id)));
        ((CraftPlayer) player).getHandle().connection.send(new ClientboundSoundPacket(
            event, source, at.getX(), at.getY(), at.getZ(), volume, pitch, ThreadLocalRandom.current().nextLong()));
    }

    // ---- in the f7-boss world ----

    /** Right-click on a terminal's command block (or the head on it) in a loaded capture opens a random terminal. */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (!worldClicks || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || !block.getWorld().getName().startsWith("soulcap_")) return;
        boolean head = block.getType() == Material.PLAYER_HEAD || block.getType() == Material.PLAYER_WALL_HEAD;
        boolean terminal = block.getType() == Material.COMMAND_BLOCK
            || head && block.getRelative(0, -1, 0).getType() == Material.COMMAND_BLOCK;
        if (!terminal) return;
        event.setCancelled(true);
        openAt(event.getPlayer());
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractAtEntityEvent event) {
        if (!worldClicks || !event.getPlayer().getWorld().getName().startsWith("soulcap_")) return;
        if (!(event.getRightClicked() instanceof ArmorStand stand) || stand.customName() == null) return;
        String name = Txt.strip(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(stand.customName()));
        if (!name.contains("Terminal") && !name.contains("CLICK HERE")) return;
        event.setCancelled(true);
        openAt(event.getPlayer());
    }

    /**
     * One tick later: a window opened inside the interact event is closed again by the server's own interaction handling.
     * Before Goldor's phase start ({@link GoldorStartSim}) the terminal does not open and answers it is not responsive.
     */
    private void openAt(Player player) {
        if (!plugin.goldor().accepts()) {
            plugin.goldor().refuse(player);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> openNow(player));
    }

    private void openNow(Player player) {
        Terminal.Kind[] kinds = Terminal.Kind.values();
        openRandom(player, kinds[ThreadLocalRandom.current().nextInt(kinds.length)], null, newSeed());
    }

    static long newSeed() {
        return ThreadLocalRandom.current().nextLong(1, 1_000_000);
    }

    // ---- /hx terminal ----

    static final String USAGE = "terminal <open <type> [letter|COLOR] [seed]|board <cid>|list|lag <ms>|freeze <ms>|counter <n> [total]|melody <pauseTicks>|aligned <on|off>|world <on|off>|status|close|selftest>";

    /** @return one line for the sender; throws IllegalArgumentException with a usage message. */
    String command(Player player, String[] args, CommandSender sender) {
        if (args.length == 0) throw new IllegalArgumentException("usage: /hx " + USAGE);
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> {
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx terminal open <" + String.join("|", Terminal.Kind.keys()) + "> [letter|COLOR] [seed]");
                Terminal.Kind kind = Terminal.Kind.of(args[1]);
                long seed = -1;
                List<String> words = new ArrayList<>();
                for (int i = 2; i < args.length; i++) {
                    if (args[i].matches("\\d+")) seed = Long.parseLong(args[i]);
                    else words.add(args[i]);
                }
                String argument = words.isEmpty() ? null : String.join(" ", words).replace('_', ' ');
                if (argument != null && kind != Terminal.Kind.STARTSWITH && kind != Terminal.Kind.SELECT) throw new IllegalArgumentException(kind.key + " takes only a seed");
                if (argument != null && kind == Terminal.Kind.STARTSWITH && !argument.matches("[A-Za-z]")) throw new IllegalArgumentException("startswith takes one letter");
                if (seed < 0) seed = newSeed();
                Session session = openRandom(player, kind, argument, seed);
                return describe(session) + " (repeat: /hx terminal open " + kind.key + (argument == null ? "" : " " + argument.replace(' ', '_')) + " " + seed + ")";
            }
            case "board" -> {
                if (args.length < 2 || !args[1].matches("\\d+")) throw new IllegalArgumentException("usage: /hx terminal board <cid> (" + Fixtures.all().keySet() + ")");
                return "captured board " + describe(openCaptured(player, Integer.parseInt(args[1])));
            }
            case "list" -> {
                StringBuilder sb = new StringBuilder("types: " + String.join(", ", Terminal.Kind.keys()) + "\ncaptured boards (cid: title):");
                Fixtures.all().forEach((cid, board) -> sb.append("\n  ").append(cid).append(": ").append(Txt.strip(board.title())));
                return sb.toString();
            }
            case "lag" -> {
                if (args.length < 2 || !args[1].matches("\\d+")) throw new IllegalArgumentException("usage: /hx terminal lag <ms> (0 = answer in the same tick; now " + lagMs + ")");
                lagMs = Integer.parseInt(args[1]);
                return "response delay " + lagMs + " ms (" + (lagMs <= 0 ? "same tick" : Math.max(1, (lagMs + 49) / 50) + " ticks") + ")";
            }
            case "counter" -> {
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx terminal counter <n done> [total] (now " + count(player) + "/" + total + ")");
                int n = Integer.parseInt(args[1]);
                int t = args.length > 2 ? Integer.parseInt(args[2]) : total;
                setCounter(player, n, t);
                return "counter at " + n + "/" + t + " (the next completion prints " + (n >= t ? 1 : n + 1) + "/" + t + ")";
            }
            case "melody" -> {
                if (args.length > 1 && args[1].equalsIgnoreCase("aligned")) {
                    if (args.length < 3 || !List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("usage: /hx terminal melody aligned <on|off>");
                    MelodyTerminal.alignNext = args[2].equalsIgnoreCase("on");
                    return "melody: the next lane's target " + (MelodyTerminal.alignNext ? "is the locked column (a lock right after the previous one skips it)" : "is random");
                }
                if (args.length < 2) throw new IllegalArgumentException("usage: /hx terminal melody <pauseTicks>|aligned <on|off> (now " + MelodyTerminal.pauseTicks + " ticks, aligned " + (MelodyTerminal.alignNext ? "on" : "off") + ")");
                MelodyTerminal.pauseTicks = Integer.parseInt(args[1]);
                return "melody: pause after a wrong lock " + MelodyTerminal.pauseTicks + " ticks";
            }
            case "world" -> {
                if (args.length < 2 || !List.of("on", "off").contains(args[1].toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("usage: /hx terminal world <on|off>");
                worldClicks = args[1].equalsIgnoreCase("on");
                return "right-click on terminal command blocks in loaded captures " + (worldClicks ? "opens a random terminal" : "does nothing");
            }
            case "status" -> {
                Session session = session(player);
                return "lag " + lagMs + " ms, counter " + count(player) + "/" + total + ", melody pause " + MelodyTerminal.pauseTicks + " ticks" + (MelodyTerminal.alignNext ? ", next lane aligned" : "") + ", world clicks "
                    + (worldClicks ? "on" : "off") + (session == null ? ", no terminal open" : ", open: " + describe(session));
            }
            case "freeze" -> {
                if (args.length < 2 || !args[1].matches("\\d+")) throw new IllegalArgumentException("usage: /hx terminal freeze <ms>");
                long ms = Long.parseLong(args[1]);
                var channel = ((CraftPlayer) player).getHandle().connection.connection.channel;
                if (!(channel.pipeline().get(TAP) instanceof Tap tap)) throw new IllegalArgumentException("no tap on this connection");
                tap.freeze(ms);
                return "server silent for " + ms + " ms";
            }
            case "close" -> {
                player.closeInventory();
                return "closed";
            }
            case "selftest" -> {
                new TerminalSelfTest(plugin, this, player, sender).run();
                return "self-test started";
            }
            default -> throw new IllegalArgumentException("usage: /hx " + USAGE);
        }
    }

    static String describe(Session session) {
        return Txt.strip(session.terminal.title()) + " [" + session.terminal.kind().key + ", " + session.terminal.rows() + " rows, seed " + session.seed + "]";
    }

    List<String> completions(int pos, String[] args) {
        if (pos == 1) return List.of("open", "board", "list", "lag", "freeze", "counter", "melody", "world", "status", "close", "selftest");
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (pos == 2 && sub.equals("open")) return List.of(Terminal.Kind.keys());
        if (pos == 2 && sub.equals("board")) return Fixtures.all().keySet().stream().map(String::valueOf).toList();
        if (pos == 2 && sub.equals("world")) return List.of("on", "off");
        if (pos == 3 && sub.equals("open") && args[1].equalsIgnoreCase("select")) return Pools.HUES.stream().map(h -> h.title().replace(' ', '_')).toList();
        return List.of();
    }
}
