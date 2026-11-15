package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Goldor's phase start T, when terminals, levers and devices first take clicks (docs/research/m7-boss-2026-11-13.md,
 * "Terminal phase start"). Unknown (the default, or {@code hx goldor reset}) takes every click as before.
 * {@code hx goldor before}: a Storm line, nothing completes. {@code hx goldor start}: Storm's death as Hypixel plays it:
 * the death strike (thunder at volume 10000 over his arena) and his end line now, the floor collapse (falling blocks at
 * the Storm pad, sent straight to the player) at +59, T at +61, {@code Who dares} at +62 (M7 on the lone-ping clock: a
 * player already in section 1, the line at D + 62 in 6/6). {@code hx goldor active} jumps to T with {@code Who dares}
 * now.
 *
 * <p>Before T the device sims rotate and light but never complete (Arrow Align aligned in Storm's phase, Lights lit in
 * it, recordings 2026-11-12), and a terminal answers {@link #REFUSED} instead of opening. That answer is Hypixel's to a
 * terminal of a section that is not open (2/2 recorded); before T it is assumed, no recording has a click there.
 */
final class GoldorStartSim {
    enum Phase { UNKNOWN, BEFORE, ACTIVE }

    static final String STORM_LINE = "§r§4[BOSS] Storm§r§c: §r§cPathetic Maxor, just like expected.";
    static final String STORM_END = "§r§4[BOSS] Storm§r§c: §r§cI should have known that I stood no chance.";
    static final String WHO_DARES = "§r§4[BOSS] Goldor§r§c: §r§cWho dares trespass into my domain?";
    static final String REFUSED = "§r§cThis Terminal doesn't seem to be responsive at the moment.";
    /** From Storm's death strike on the lone-ping clock (M7 2026-11-13, 6/6): collapse +59…60, T +61, {@code Who dares} +62. */
    static final int TO_COLLAPSE = 59, TO_START = 61, TO_WHO_DARES = 62;
    /** Hypixel answers a refused terminal 3 ticks after the click (ping-clock run, WD+631). */
    static final int REFUSE_DELAY = 3;
    private static final int FAKE_ID = 1_900_000_000;

    private final SoulTestKit plugin;
    private final List<BukkitTask> pending = new ArrayList<>();
    private Phase phase = Phase.UNKNOWN;
    private long death = -1;

    GoldorStartSim(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    /** Terminals, levers and devices take clicks: T has come, or nothing says it has not. */
    boolean accepts() {
        return phase != Phase.BEFORE;
    }

    Phase phase() {
        return phase;
    }

    /** For the self tests: the phase without lines or sounds. */
    void set(Phase p) {
        cancel();
        phase = p;
        plugin.arrows().lanterns(p != Phase.BEFORE);
    }

    void stop() {
        cancel();
        phase = Phase.UNKNOWN;
    }

    List<String> command(Player target, String[] rest) {
        String sub = rest.length == 0 ? "" : rest[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "before" -> {
                set(Phase.BEFORE);
                chat(target, STORM_LINE);
                return List.of("BEFORE: sent " + Txt.strip(STORM_LINE) + "; devices do not complete, terminals answer 'not responsive'");
            }
            case "start" -> {
                set(Phase.BEFORE);
                death = target.getWorld().getGameTime();
                // On time both come in one tick (F7 11/11); the line first so a client that saw no Storm line yet takes the strike.
                chat(target, STORM_END);
                strike(target);
                later(TO_COLLAPSE, () -> collapse(target));
                later(TO_START, () -> {
                    phase = Phase.ACTIVE;
                    plugin.arrows().lanterns(true);
                    log(target, "T: terminals, levers and devices take clicks");
                });
                later(TO_WHO_DARES, () -> chat(target, WHO_DARES));
                return List.of("Storm's death strike and end line now, the floor collapse at +" + TO_COLLAPSE + ", T at +" + TO_START
                    + ", Who dares at +" + TO_WHO_DARES);
            }
            case "active" -> {
                set(Phase.ACTIVE);
                chat(target, WHO_DARES);
                return List.of("ACTIVE: sent " + Txt.strip(WHO_DARES));
            }
            case "reset" -> {
                set(Phase.UNKNOWN);
                return List.of("UNKNOWN: everything takes clicks");
            }
            case "status" -> {
                long now = target.getWorld().getGameTime();
                return List.of("phase " + phase + (death < 0 ? "" : ", T at D+" + TO_START + ", now D+" + (now - death)));
            }
            default -> throw new IllegalArgumentException("goldor <before|start|active|reset|status>");
        }
    }

    /** A terminal click before T: Hypixel's answer instead of the menu. */
    void refuse(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) chat(player, REFUSED);
        }, REFUSE_DELAY);
    }

    private void later(int ticks, Runnable r) {
        pending.add(Bukkit.getScheduler().runTaskLater(plugin, r, ticks));
    }

    private void cancel() {
        pending.forEach(BukkitTask::cancel);
        pending.clear();
        death = -1;
    }

    private void chat(Player p, String line) {
        Nms.sendSystemChat(p, Txt.of(Terminals.wire(line)), false);
        log(p, "chat: " + Txt.strip(line));
    }

    /** Storm's death strike: a lightning thunder at volume 10000 over his arena (Giga strikes use 2–50). */
    private void strike(Player p) {
        var event = Holder.direct(SoundEvent.createVariableRangeEvent(Identifier.parse("minecraft:entity.lightning_bolt.thunder")));
        ((CraftPlayer) p).getHandle().connection.send(new ClientboundSoundPacket(
            event, SoundSource.WEATHER, 45.5, 170.0, 42.5, 10000f, 0.8f, ThreadLocalRandom.current().nextLong()));
        log(p, "Storm's death strike: thunder at 45.5 170 42.5 vol 10000");
    }

    /** A few falling blocks of the burst at the Storm pad (Hypixel: ~2000 over x 90–112, z 30–52, y 161–232), gone 3 ticks later. */
    private void collapse(Player p) {
        var conn = ((CraftPlayer) p).getHandle().connection;
        int data = Block.getId(Blocks.STONE_BRICKS.defaultBlockState());
        int[] ids = new int[9];
        for (int i = 0; i < 9; i++) {
            ids[i] = FAKE_ID + i;
            conn.send(new ClientboundAddEntityPacket(ids[i], UUID.randomUUID(), 98.5 + i % 3 * 3, 221.0, 38.5 + i / 3 * 3, 0f, 0f,
                EntityTypes.FALLING_BLOCK, data, Vec3.ZERO, 0.0));
        }
        later(3, () -> conn.send(new ClientboundRemoveEntitiesPacket(ids)));
        log(p, "floor collapse: 9 falling blocks at the Storm pad");
    }

    private void log(Player p, String what) {
        long now = p.getWorld().getGameTime();
        plugin.getLogger().info("[goldor] tick=" + now + (death < 0 ? "" : " D+" + (now - death)) + " " + what);
    }
}
