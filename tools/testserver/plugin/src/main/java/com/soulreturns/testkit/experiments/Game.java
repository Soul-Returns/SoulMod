package com.soulreturns.testkit.experiments;

import java.util.Random;

/** One running experiment. Its screen's token: scheduled steps die with the game, not with a re-sent screen. */
abstract class Game {
    final Engine e;
    final MenuItems.Tier tier;
    final String seed;
    final Random rng;
    final boolean practice;
    boolean ended;

    Game(Engine e, MenuItems.Tier tier, String seed, long rngSeed, boolean practice) {
        this.e = e;
        this.tier = tier;
        this.seed = seed;
        this.rng = new Random(rngSeed);
        this.practice = practice;
    }

    abstract String title();

    /** @return what happened, for the click log. */
    abstract String click(int slot, Click click);

    /** Called once per tick after the scheduled steps (timers). */
    abstract void tick();

    /** container_close from the client while this game was open (Esc). */
    abstract void clientClosed();

    abstract String status();

    boolean meta() {
        return tier.name().equals("Metaphysical");
    }

    static Item clock(int seconds) {
        return Item.of("clock", Math.max(1, Math.min(64, seconds)), "§r§7Timer: §r§a" + seconds + "s");
    }

    static Item glowstone() {
        return Item.of("glowstone", "§r§aRemember the pattern!");
    }

    /** Hypixel's pitch reaches the client as n/63 (its 1.8 protocol sends pitch as a byte of 63ths). */
    static float pitch63(double pitch) {
        return (int) (pitch * 63 + 1e-6) / 63f;
    }
}
