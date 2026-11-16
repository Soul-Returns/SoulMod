package com.soulreturns.testkit;

import java.util.Locale;

/** The numbers of the forge simulation (no Bukkit): Quick Forge, shown durations, remaining time, the recipe book bar. */
final class ForgeModel {
    static final int MAX_LEVEL = 20;
    static final int BAR_SEGMENTS = 25;

    private ForgeModel() {}

    /** Quick Forge reduction in percent: 10 + 0.5 x level for 1-19, 30 at the maximum, 0 when not unlocked. */
    static double percent(int level) {
        if (level <= 0) return 0;
        if (level >= MAX_LEVEL) return 30;
        return 10 + 0.5 * level;
    }

    /** "10.5", "11", "30": the perk lore's number, no trailing ".0". */
    static String percentText(double percent) {
        return percent == Math.rint(percent) ? String.valueOf((long) percent) : String.format(Locale.ROOT, "%.1f", percent);
    }

    /** The recipe duration a player sees: base x (1 - reduction), rounded to whole seconds. */
    static long shownSeconds(long baseSeconds, int level) {
        return Math.round(baseSeconds * (100.0 - percent(level)) / 100.0);
    }

    /** "1d 1h 12m", "21s", "5h 36m": whole units, zero units left out. */
    static String duration(long seconds) {
        long d = seconds / 86_400;
        long h = seconds % 86_400 / 3_600;
        long m = seconds % 3_600 / 60;
        long s = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (d > 0) out.append(d).append("d ");
        if (h > 0) out.append(h).append("h ");
        if (m > 0) out.append(m).append("m ");
        if (s > 0 || out.isEmpty()) out.append(s).append('s');
        return out.toString().stripTrailing();
    }

    /**
     * Time Remaining of a running process: the shown duration at the current level minus the time since it started
     * (Hypixel stores the start only), rounded up to whole seconds, never below 1 s: a finished process is not simulated.
     */
    static long remainingSeconds(long baseSeconds, int level, long elapsedMillis) {
        long left = shownSeconds(baseSeconds, level) * 1_000 - elapsedMillis;
        return Math.max(1, Math.ceilDiv(left, 1_000));
    }

    /** Recipe Book Unlocked: floor of unlocked / total in percent (90/123 = 73, 98/123 = 79). */
    static int bookPercent(int unlocked, int total) {
        return unlocked * 100 / total;
    }

    /** Green segments of the 25-segment book bar. */
    static int barGreen(int percent) {
        return percent * BAR_SEGMENTS / 100;
    }
}
