package com.soulreturns.testkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * presets.json from the jar, overlaid entry by entry with plugins/SoulTestKit/presets.json when that
 * exists (same shape; an entry there replaces the bundled one of the same name).
 */
public final class Presets {
    public static final List<String> SECTIONS =
        List.of("presets", "locations", "sidebars", "tabs", "actionbars", "chats", "items", "menus");

    private JsonObject root = new JsonObject();

    public void load(Path override) throws IOException {
        JsonObject merged;
        try (InputStream in = Presets.class.getResourceAsStream("/presets.json")) {
            if (in == null) throw new IOException("presets.json missing from the jar");
            merged = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        if (Files.isRegularFile(override)) {
            JsonObject extra;
            try (Reader r = Files.newBufferedReader(override, StandardCharsets.UTF_8)) {
                extra = parse(r);
            }
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
                if (SECTIONS.contains(e.getKey()) && e.getValue().isJsonObject()) {
                    JsonObject target = merged.has(e.getKey()) ? merged.getAsJsonObject(e.getKey()) : new JsonObject();
                    e.getValue().getAsJsonObject().entrySet().forEach(x -> target.add(x.getKey(), x.getValue()));
                    merged.add(e.getKey(), target);
                } else {
                    merged.add(e.getKey(), e.getValue());
                }
            }
        }
        root = merged;
    }

    private static JsonObject parse(Reader reader) {
        return JsonParser.parseReader(reader).getAsJsonObject();
    }

    public JsonObject section(String name) {
        return root.has(name) ? root.getAsJsonObject(name) : new JsonObject();
    }

    public JsonElement get(String section, String key) {
        return section(section).get(key);
    }

    public List<String> keys(String section) {
        return section(section).keySet().stream().sorted().toList();
    }

    public String string(String key, String fallback) {
        return root.has(key) ? root.get(key).getAsString() : fallback;
    }
}
