package com.soulreturns.testkit.terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Item pools: Hypixel's legacy display names with registry ids (Appendix B) and the select-color palette. */
public final class Pools {
    private static final Map<String, String> ITEMS = load("/terminals/items.txt");

    private Pools() {}

    /** A select-terminal color: block names use {@code blockName}, dyes {@code dyeName} (Ink Sack, Light Gray Dye). */
    public record Hue(String key, String title, String blockName, String dyeName, String dyeId) {
        public Cell item(int form) {
            return switch (form) {
                case 0 -> Cell.of(key + "_wool", "§r§a" + blockName + " Wool");
                case 1 -> Cell.of(key + "_terracotta", "§r§a" + blockName + " Stained Clay");
                case 2 -> Cell.of(key + "_stained_glass", "§r§a" + blockName + " Stained Glass");
                default -> Cell.of(dyeId, "§r§a" + dyeName);
            };
        }
    }

    public static final int FORMS = 4;

    /**
     * Colors seen in the captured boards. Observed targets: LIME, GRAY (and CYAN in the earlier run); the other
     * titles are assumptions. Light gray shows as "Silver" on blocks and "Light Gray Dye" on the dye (captured);
     * its title word is an assumption (LIGHT GRAY, as reference mods map "light gray" to "silver").
     */
    public static final List<Hue> HUES = List.of(
        new Hue("lime", "LIME", "Lime", "Lime Dye", "lime_dye"),
        new Hue("gray", "GRAY", "Gray", "Gray Dye", "gray_dye"),
        new Hue("cyan", "CYAN", "Cyan", "Cyan Dye", "cyan_dye"),
        new Hue("purple", "PURPLE", "Purple", "Purple Dye", "purple_dye"),
        new Hue("pink", "PINK", "Pink", "Pink Dye", "pink_dye"),
        new Hue("black", "BLACK", "Black", "Ink Sack", "ink_sac"),
        new Hue("light_blue", "LIGHT BLUE", "Light Blue", "Light Blue Dye", "light_blue_dye"),
        new Hue("light_gray", "LIGHT GRAY", "Silver", "Light Gray Dye", "light_gray_dye"));

    /** Targets picked when none is given: the colors actually observed as a target. */
    public static final List<Hue> OBSERVED_TARGETS = List.of(HUES.get(0), HUES.get(1), HUES.get(2));

    public static Hue hueByTitle(String title) {
        for (Hue hue : HUES) if (hue.title.equalsIgnoreCase(title.trim())) return hue;
        throw new IllegalArgumentException("unknown color " + title + " (" + String.join(", ", HUES.stream().map(Hue::title).toList()) + ")");
    }

    /** The hue of a select-terminal item name, or null. */
    public static Hue hueOfName(String plainName) {
        for (Hue hue : HUES) {
            for (int form = 0; form < FORMS; form++) {
                if (hue.item(form).plainName().equals(plainName)) return hue;
            }
        }
        return null;
    }

    public static Map<String, String> items() {
        return ITEMS;
    }

    /** Puzzle item for a display name: id from the table, name {@code §r§a<name>}, count 1, no lore. */
    public static Cell item(String displayName) {
        String id = ITEMS.get(displayName);
        if (id == null) {
            Hue hue = hueOfName(displayName);
            if (hue != null) {
                for (int form = 0; form < FORMS; form++) if (hue.item(form).plainName().equals(displayName)) return hue.item(form);
            }
            throw new IllegalArgumentException("no registry id for item name '" + displayName + "'");
        }
        return Cell.of(id, "§r§a" + displayName);
    }

    /** Names that start with the letter (case-insensitive), in table order. */
    public static List<String> startingWith(char letter) {
        List<String> out = new ArrayList<>();
        for (String name : ITEMS.keySet()) if (Character.toUpperCase(name.charAt(0)) == Character.toUpperCase(letter)) out.add(name);
        return out;
    }

    private static Map<String, String> load(String resource) {
        try (InputStream in = Pools.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + resource);
            Map<String, String> map = new LinkedHashMap<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.isBlank() || line.startsWith("#")) continue;
                int eq = line.lastIndexOf('=');
                map.put(line.substring(0, eq), line.substring(eq + 1).trim());
            }
            return map;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
