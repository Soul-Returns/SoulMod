package com.soulreturns.testkit;

import java.util.Locale;

/** Hypixel mob nametag text; no Bukkit types, so the plugin tests can load it. */
public final class MobNames {
    /** Hypixel's combat icon for End mobs (resource-pack glyph); exercises the client's icon stripping. */
    private static final String ICON = "§5\uE078 ";

    private MobNames() {}

    /** {@code §8[§7Lv51§8] §5<icon> §cEnderman§r §a6,000§f/§a6,000§c❤}, verbatim shape from a packet log. */
    public static String nametag(String name, int level, long hp, long max) {
        String hpColor = hp * 2 > max ? "§a" : hp * 4 > max ? "§e" : "§c";
        return "§r§8[§r§7Lv" + level + "§r§8] §r" + ICON + "§r§c" + name + "§r §r" + hpColor + amount(hp)
            + "§r§f/§r§a" + amount(max) + "§r§c❤";
    }

    /** Hypixel's health numbers: commas below 100k, then one-decimal {@code k}/{@code M}/{@code B}, ".0" dropped. */
    public static String amount(long v) {
        if (v < 100_000) return String.format(Locale.ROOT, "%,d", v);
        if (v < 1_000_000) return compact(v / 1_000.0, "k");
        if (v < 1_000_000_000) return compact(v / 1_000_000.0, "M");
        return compact(v / 1_000_000_000.0, "B");
    }

    private static String compact(double v, String suffix) {
        String s = String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10);
        return (s.endsWith(".0") ? s.substring(0, s.length() - 2) : s) + suffix;
    }

    /** Parses {@code 6000}, {@code 6,000}, {@code 750k}, {@code 1.5M}, {@code 2B}. */
    public static long parseAmount(String raw) {
        String s = raw.replace(",", "").trim();
        if (s.isEmpty()) throw new NumberFormatException("empty amount");
        char last = Character.toLowerCase(s.charAt(s.length() - 1));
        double mul = last == 'k' ? 1e3 : last == 'm' ? 1e6 : last == 'b' ? 1e9 : 1;
        if (mul != 1) s = s.substring(0, s.length() - 1);
        long v = Math.round(Double.parseDouble(s) * mul);
        if (v < 0) throw new NumberFormatException("negative amount");
        return v;
    }
}
