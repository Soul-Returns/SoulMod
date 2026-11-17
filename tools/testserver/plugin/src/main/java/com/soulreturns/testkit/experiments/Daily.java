package com.soulreturns.testkit.experiments;

import java.util.HashMap;
import java.util.Map;

/** The player's Experimentation Table state behind the menus' lore (research §1.5). */
public final class Daily {
    public static final class Addon {
        /** Rewards claimed this charge: status panes "Experiment completed!", head "On cooldown!". */
        public boolean done;
        public int bonus;
        /** Declined once: every tier of this add-on shows "Starting cost: N XP Levels". */
        public boolean declined;
    }

    public int charges = 1;
    public int renewed;
    /** The add-on heads' "Right-Click to practice!" line while on cooldown (captured); off = only "On cooldown!" (ASSUMED). */
    public boolean practice = true;
    public int serum = 2;
    public final Addon chrono = new Addon();
    public final Addon ultra = new Addon();
    /** Stakes status overrides, key {@code <game>:<Tier>}, value play | low | xp. Default: Superpairs Metaphysical low (captured). */
    public final Map<String, String> stakesStatus = new HashMap<>();
    /** The RNG Meter's selected drop (captured: Looting V); {@code rng selected=Growth_VI} makes a 5.3 % book the selected one. */
    public String rngSelected = "Looting V";

    public Addon addon(String game) {
        return game.equals("chrono") ? chrono : ultra;
    }

    public String status(String game, String tier) {
        String s = stakesStatus.get(game + ":" + tier);
        if (s != null) return s;
        return game.equals("sp") && tier.equals("Metaphysical") ? "low" : "play";
    }

    /** A fresh day: one charge, add-ons pending, no overrides. */
    public void reset() {
        charges = 1;
        renewed = 0;
        practice = true;
        serum = 2;
        addons("pending");
        stakesStatus.clear();
        rngSelected = "Looting V";
    }

    /** {@code addons=pending|chrono|done}: done = the captured bonuses (+2 Chronomatron, +3 Ultrasequencer). */
    public void addons(String state) {
        chrono.done = !state.equals("pending");
        chrono.bonus = chrono.done ? 2 : 0;
        ultra.done = state.equals("done");
        ultra.bonus = ultra.done ? 3 : 0;
        chrono.declined = ultra.declined = false;
    }
}
