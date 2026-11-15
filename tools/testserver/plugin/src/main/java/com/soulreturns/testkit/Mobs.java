package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.Vector;

/**
 * SkyBlock-style mobs: a silent, AI-less mob plus a separate invisible marker armor stand above it
 * that carries the {@code [Lv#] Name cur/max❤} nametag, spawned right after the mob so its entity id
 * is the mob's + 1, as on Hypixel. Hits and {@code hx mob damage} rewrite the stand's name; at 0 both
 * go away. Real mob health never changes (the hit is cancelled), as Hypixel tracks it server-side.
 */
public final class Mobs implements Listener {
    public static final class Mob {
        final LivingEntity body;
        final ArmorStand tag;
        final String name;
        final int level;
        final long max;
        long hp;

        Mob(LivingEntity body, ArmorStand tag, String name, int level, long hp, long max) {
            this.body = body;
            this.tag = tag;
            this.name = name;
            this.level = level;
            this.hp = hp;
            this.max = max;
        }
    }

    private final List<Mob> mobs = new ArrayList<>();

    /** Spawns [type] three blocks in front of [player], facing them; returns the spawned mob. */
    public Mob spawn(Player player, EntityType type, String name, int level, long hp, long max) {
        if (!type.isAlive() || !type.isSpawnable() || type == EntityType.ARMOR_STAND) {
            throw new IllegalArgumentException(type.name().toLowerCase(Locale.ROOT) + " is not a spawnable mob");
        }
        if (Monster.class.isAssignableFrom(type.getEntityClass()) && player.getWorld().getDifficulty() == Difficulty.PEACEFUL) {
            // Peaceful despawns monsters at once; player damage is cancelled anyway.
            player.getWorld().setDifficulty(Difficulty.EASY);
        }
        Vector look = player.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-6) look = new Vector(0, 0, 1);
        Location at = player.getLocation().add(look.normalize().multiply(3));
        at.setDirection(look.clone().multiply(-1));
        LivingEntity body = (LivingEntity) player.getWorld().spawnEntity(at, type);
        body.setAI(false);
        body.setSilent(true);
        body.setPersistent(true);
        body.setRemoveWhenFarAway(false);
        ArmorStand tag = player.getWorld().spawn(at.clone().add(0, body.getHeight() + 0.25, 0), ArmorStand.class, s -> {
            s.setInvisible(true);
            s.setMarker(true);
            s.setGravity(false);
            s.setCustomNameVisible(true);
            s.setPersistent(true);
        });
        Mob mob = new Mob(body, tag, name, level, Math.min(hp, max), max);
        mobs.add(mob);
        rename(mob);
        return mob;
    }

    /** Damages every tracked mob whose name contains [filter] (all when null); returns how many. */
    public int damage(long amount, String filter) {
        int hit = 0;
        for (Mob mob : List.copyOf(mobs)) {
            if (filter != null && !mob.name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT))) continue;
            hurt(mob, amount);
            hit++;
        }
        return hit;
    }

    public int clear() {
        int n = mobs.size();
        for (Mob mob : mobs) remove(mob);
        mobs.clear();
        return n;
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        for (Mob mob : mobs) out.add(Txt.strip(MobNames.nametag(mob.name, mob.level, mob.hp, mob.max)) + " (" + mob.body.getType().name().toLowerCase(Locale.ROOT) + " #" + mob.body.getEntityId() + ", tag #" + mob.tag.getEntityId() + ")");
        return out;
    }

    /** A player's melee hit takes a tenth of max health off the nametag, never the mob's real health. */
    @EventHandler
    public void onHit(EntityDamageByEntityEvent event) {
        Entity victim = event.getEntity();
        for (Mob mob : mobs) {
            if (mob.body != victim) continue;
            event.setCancelled(true);
            if (event.getDamager() instanceof Player) hurt(mob, Math.max(1, mob.max / 10));
            return;
        }
    }

    private void hurt(Mob mob, long amount) {
        mob.hp = Math.max(0, mob.hp - amount);
        rename(mob);
        if (mob.hp == 0) {
            mobs.remove(mob);
            remove(mob);
        }
    }

    private static void remove(Mob mob) {
        mob.body.remove();
        mob.tag.remove();
    }

    private static void rename(Mob mob) {
        mob.tag.customName(Txt.of(MobNames.nametag(mob.name, mob.level, mob.hp, mob.max)));
    }
}
