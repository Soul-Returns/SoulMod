package com.soulreturns.testkit;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * SkyBlock tab list: 4 columns x 20 rows of fake entries named {@code !A-a} .. {@code !D-t} (the client
 * sorts by name, so columns fill in order), the widget text in each entry's display name. Real players
 * are unlisted for the viewer while it shows, as on Hypixel where every row is a fake entry.
 */
public final class TabList {
    public static final int COLUMNS = 4;
    public static final int ROWS = 20;

    private TabList() {}

    public static void show(Player player, PlayerState state, String header, String footer,
                            List<List<String>> columns, UnaryOperator<String> fill) {
        clearEntries(player, state);
        List<Nms.TabEntry> entries = new ArrayList<>();
        for (int c = 0; c < COLUMNS; c++) {
            List<String> column = c < columns.size() ? columns.get(c) : List.of();
            for (int r = 0; r < ROWS; r++) {
                String text = r < column.size() ? fill.apply(column.get(r)) : "";
                UUID id = UUID.randomUUID();
                entries.add(new Nms.TabEntry(id, "!" + (char) ('A' + c) + "-" + (char) ('a' + r), Txt.of(text)));
                state.tabEntries.add(id);
            }
        }
        Nms.addTabEntries(player, entries);
        for (Player other : Bukkit.getOnlinePlayers()) Nms.setListed(player, other.getUniqueId(), false);
        player.sendPlayerListHeaderAndFooter(Txt.of(fill.apply(header)), Txt.of(fill.apply(footer)));
    }

    public static void clear(Player player, PlayerState state) {
        clearEntries(player, state);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (player.canSee(other)) Nms.setListed(player, other.getUniqueId(), true);
        }
        player.sendPlayerListHeaderAndFooter(Txt.of(""), Txt.of(""));
    }

    private static void clearEntries(Player player, PlayerState state) {
        Nms.removeTabEntries(player, state.tabEntries);
        state.tabEntries = new ArrayList<>();
    }
}
