package com.soulreturns.testkit;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * SkyBlock sidebar built like Hypixel's: one team per line, the score holder an invisible colour-code
 * key, the visible text in the team prefix and (past 16 characters, the 1.8 limit) the suffix, which
 * continues with the prefix's last colour codes. Readers must join prefix + holder + suffix.
 */
public final class Sidebar {
    private static final String KEYS = "0123456789abcdef";

    private Sidebar() {}

    public static void show(Player player, String title, List<String> lines) {
        boolean alpha = JavaPlugin.getPlugin(SoulTestKit.class).state(player).alpha;
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("SBScoreboard", Criteria.DUMMY, Txt.of(title));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        int count = Math.min(lines.size(), 32);
        for (int i = 0; i < count; i++) {
            String key = "§" + KEYS.charAt(i % 16) + (i >= 16 ? "§" + KEYS.charAt(i / 16) : "") + "§r";
            Team team = board.registerNewTeam("team_" + (count - i));
            team.addEntry(key);
            String text = Txt.legacy(lines.get(i));
            if (alpha) text = text.replace("www.hypixel.net", "alpha.hypixel.net");
            int cut = splitPoint(text);
            team.prefix(Txt.of(text.substring(0, cut)));
            if (cut < text.length()) team.suffix(Txt.of(lastCodes(text.substring(0, cut)) + text.substring(cut)));
            objective.getScore(key).setScore(count - i);
        }
        player.setScoreboard(board);
    }

    public static void clear(Player player) {
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
    }

    private static int splitPoint(String text) {
        if (text.length() <= 16) return text.length();
        return text.charAt(15) == '§' ? 15 : 16;
    }

    /** Colour plus formatting codes in effect at the end of the string (Bukkit's getLastColors). */
    static String lastCodes(String text) {
        StringBuilder codes = new StringBuilder();
        for (int i = text.length() - 2; i >= 0; i--) {
            if (text.charAt(i) != '§') continue;
            char c = Character.toLowerCase(text.charAt(i + 1));
            if ("klmno".indexOf(c) >= 0) codes.insert(0, "§" + c);
            else if (KEYS.indexOf(c) >= 0 || c == 'r') {
                codes.insert(0, "§" + c);
                break;
            }
        }
        return codes.toString();
    }
}
