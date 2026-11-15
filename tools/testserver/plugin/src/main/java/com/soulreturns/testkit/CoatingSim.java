package com.soulreturns.testkit;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Divan's Powder Coating dust as captured in the Dwarven Mines (2026-11-15): every 4 ticks 4 white and
 * 4 gold {@code dust} packets, scale 1, count 0, speed 1, override limiter + always show; spread 1/1/1 for
 * white, 1/0.6/0 for gold (the spread repeats the color); within about half a block of the wearer
 * horizontally and 0.25-1.25 above the feet. With {@code decoy}, the same
 * dust also bursts 4 blocks in front of the wearer, away from every player (must stay visible).
 */
public final class CoatingSim {
    private static final int WHITE = 0xFFFFFF;
    private static final int GOLD = 0xFF9900;

    private final Plugin plugin;
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    public CoatingSim(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start(Player wearer, boolean decoy) {
        stop(wearer);
        tasks.put(wearer.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> burst(wearer, decoy), 0L, 4L));
    }

    public boolean stop(Player wearer) {
        BukkitTask task = tasks.remove(wearer.getUniqueId());
        if (task != null) task.cancel();
        return task != null;
    }

    private void burst(Player wearer, boolean decoy) {
        if (!wearer.isOnline()) {
            stop(wearer);
            return;
        }
        Location feet = wearer.getLocation();
        Location ahead = feet.clone().add(feet.getDirection().setY(0).normalize().multiply(4));
        for (Player viewer : wearer.getWorld().getPlayers()) {
            var connection = ((CraftPlayer) viewer).getHandle().connection;
            if (connection == null) continue;
            for (int color : new int[] {WHITE, GOLD}) {
                for (int i = 0; i < 4; i++) {
                    connection.send(packet(feet, color));
                    if (decoy) connection.send(packet(ahead, color));
                }
            }
        }
    }

    private static ClientboundLevelParticlesPacket packet(Location feet, int color) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        boolean gold = color == GOLD;
        return new ClientboundLevelParticlesPacket(new DustParticleOptions(color, 1f), true, true,
            feet.getX() + r.nextDouble(-0.4, 0.4), feet.getY() + r.nextDouble(0.25, 1.25), feet.getZ() + r.nextDouble(-0.4, 0.4),
            1f, gold ? 0.6f : 1f, gold ? 0f : 1f, 1f, 0);
    }
}
