package com.soulreturns.testkit.experiments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ExperimentModelTest {
    /** The mod's recorded fixtures (format: tools/devbridge/exp-fixtures), relative to the plugin project dir. */
    private static final Path FIXTURES = Path.of("../../../src/test/resources/experiments");

    private static SelfTest.Driver direct(Session s) {
        return new SelfTest.Driver() {
            public Session session() {
                return s;
            }

            public void click(int slot, Click click) {
                s.click(s.view.cid, slot, click);
            }

            public void close() {
                s.clientClose(s.view.cid);
            }
        };
    }

    private static List<JsonObject> events(String file) throws IOException {
        Path path = FIXTURES.resolve(file);
        Assumptions.assumeTrue(Files.exists(path), "fixture " + path + " missing");
        List<JsonObject> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(path)), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.isBlank()) out.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return out;
    }

    /** The container part of the first set_content of [cid]. */
    private static Item[] recorded(List<JsonObject> events, int cid) {
        int size = -1;
        for (JsonObject ev : events) {
            if (ev.get("cid") == null || ev.get("cid").getAsInt() != cid) continue;
            String k = ev.get("k").getAsString();
            if (k.equals("open")) size = ev.get("size").getAsInt();
            if (k.equals("content")) {
                Item[] slots = new Item[size];
                for (Map.Entry<String, JsonElement> e : ev.getAsJsonObject("items").entrySet()) {
                    int slot = Integer.parseInt(e.getKey());
                    if (slot < size) slots[slot] = Item.fromBridge(e.getValue().getAsJsonObject());
                }
                return slots;
            }
        }
        throw new IllegalStateException("no set_content for container " + cid);
    }

    private static String title(List<JsonObject> events, int cid) {
        for (JsonObject ev : events) {
            if (ev.get("k").getAsString().equals("open") && ev.get("cid").getAsInt() == cid) return ev.get("title").getAsString();
        }
        throw new IllegalStateException("no open for container " + cid);
    }

    private static void assertMenu(List<JsonObject> events, int cid, ClientView v) {
        Item[] want = recorded(events, cid);
        List<String> diffs = new ArrayList<>();
        if (!v.title.equals(title(events, cid))) diffs.add("title " + v.title + " != " + title(events, cid));
        if (v.slots.length != want.length) diffs.add("size " + v.slots.length + " != " + want.length);
        for (int i = 0; i < Math.min(want.length, v.slots.length); i++) {
            Item got = norm(v.slots[i]);
            Item exp = norm(want[i]);
            if (got == null ? exp != null : !got.equals(exp)) diffs.add("slot " + i + ":\n  want " + (exp == null ? null : exp.toBridge()) + "\n  got  " + (got == null ? null : got.toBridge()));
        }
        assertEquals("", String.join("\n", diffs), "container " + cid);
    }

    /** Namespaced id; experience_bottle's glint_override is its vanilla default (the bridge reports it, the sim sends no patch). */
    private static Item norm(Item item) {
        if (item == null) return null;
        String id = item.id().contains(":") ? item.id() : "minecraft:" + item.id();
        Boolean glint = item.glint() == null && id.equals("minecraft:experience_bottle") ? Boolean.TRUE : item.glint();
        return new Item(id, item.count(), item.name(), item.lore(), item.texture(), item.dye(), glint, item.tooltipStyle(), item.model(), item.customData());
    }

    /** The recorded click as the plugin's tap maps it. */
    static Click click(String mode, int button) {
        return switch (mode) {
            case "PICKUP" -> button == 1 ? Click.RIGHT : Click.LEFT;
            case "CLONE" -> Click.MIDDLE;
            case "PICKUP_ALL" -> Click.DOUBLE;
            case "QUICK_CRAFT" -> (button & 3) == 1 ? Click.DRAG : Click.DRAG_EDGE;
            case "QUICK_MOVE" -> Click.SHIFT;
            default -> Click.OTHER;
        };
    }

    private static Session session() {
        Session s = new Session(1, null);
        s.wire.lagMs = 0;
        s.engine.daily.reset();
        return s;
    }

    @Test
    void tableMatchesTheRecording() throws IOException {
        Session s = session();
        s.engine.daily.charges = 0;
        s.engine.openTable();
        s.flush();
        assertMenu(events("menus.ndjson.gz"), 3, s.view);
    }

    @Test
    void stakesMatchTheRecording() throws IOException {
        List<JsonObject> menus = events("menus.ndjson.gz");
        Session s = session();
        s.engine.openStakes("chrono", false);
        s.flush();
        assertMenu(menus, 5, s.view);
        s.engine.daily.addons("chrono");
        s.engine.openStakes("ultra", false);
        s.flush();
        assertMenu(menus, 9, s.view);
        s.engine.daily.addons("done");
        s.engine.openStakes("sp", false);
        s.flush();
        assertMenu(menus, 13, s.view);
    }

    @Test
    void overMenusMatchTheRecordings() throws IOException {
        record Over(String file, int cid, String game, String reason, int chain, int rewardRounds) {}
        for (Over o : List.of(new Over("exp-6.ndjson.gz", 7, "chrono", "§r§cYou closed the game!", 6, 6),
            new Over("exp-10.ndjson.gz", 11, "ultra", "§r§8Game closed", 5, 6),
            new Over("exp-26.ndjson.gz", 27, "chrono", "§r§cYou closed the game!", 9, 9),
            new Over("exp-30.ndjson.gz", 31, "ultra", "§r§8Clicked wrong number", 7, 8))) {
            Session s = session();
            List<MenuItems.Tier> tiers = o.game().equals("chrono") ? MenuItems.CHRONO : MenuItems.ULTRA;
            s.engine.openOver(o.game(), tiers.get(tiers.size() - 1), o.reason(), o.chain(), o.rewardRounds(), false);
            s.flush();
            assertMenu(events(o.file()), o.cid(), s.view);
        }
    }

    /** Replays the recorded Superpairs clicks on the recorded boards; the Rewards menu must come out as recorded. */
    @Test
    void superpairsReplaysToTheRecordedRewards() throws IOException {
        for (int cid : new int[] {17, 36}) {
            List<JsonObject> events = events("exp-" + cid + ".ndjson.gz");
            Session s = session();
            long open = -1;
            for (JsonObject ev : events) if (ev.get("k").getAsString().equals("open") && ev.get("cid").getAsInt() == cid) open = ev.get("tick").getAsLong();
            s.engine.start("sp", "Transcendent", "c" + cid, cid == 17 ? 23 : 24, false, null);
            s.flush();
            long base = s.engine.tick;
            for (JsonObject ev : events) {
                if (!ev.has("cid") || ev.get("cid").getAsInt() != cid) continue;
                String k = ev.get("k").getAsString();
                if (!k.equals("click") && !k.equals("close")) continue;
                long at = base + ev.get("tick").getAsLong() - open + 1;
                while (s.engine.tick < at) s.tick();
                if (k.equals("close")) s.clientClose(s.view.cid);
                else s.click(s.view.cid, ev.get("slot").getAsInt(), click(ev.get("mode").getAsString(), ev.get("button").getAsInt()));
            }
            for (int i = 0; i < 10; i++) s.tick();
            assertMenu(events, cid + 1, s.view);
        }
    }

    @Test
    void selfTestPassesForEverySeed() {
        for (long seed = 1; seed <= 5; seed++) {
            SelfTest t = new SelfTest(direct(new Session(seed, null)));
            String result = t.run();
            long s = seed;
            t.failures().forEach(f -> System.out.println("seed " + s + " " + f));
            assertEquals(List.of(), t.failures(), "seed " + seed + ": " + result);
        }
    }
}
