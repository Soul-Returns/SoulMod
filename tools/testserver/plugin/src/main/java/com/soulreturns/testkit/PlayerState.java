package com.soulreturns.testkit;

import com.google.gson.JsonObject;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.scheduler.BukkitTask;

/** What the plugin pretends about one player; kept for the server's lifetime so it survives a rejoin. */
public final class PlayerState {
    /** Last applied `hx preset`, null after `hx reset`. */
    public String preset;
    public boolean initialised;

    /** Current hyevent:location fields (serverName, serverType, lobbyName, mode, map). */
    public JsonObject location = new JsonObject();

    /** hypixel:register subscriptions of the client: event identifier to version. */
    public Map<String, Integer> subscriptions = new LinkedHashMap<>();
    public boolean helloSent;

    public String partyMode = "none";
    public List<String> partyNames = new ArrayList<>();
    public String rank = "none";
    public String rankPrefix;

    public String sidebar;
    public String tab;
    public List<UUID> tabEntries = new ArrayList<>();
    /** Body lines replacing the tab's Pet widget ({@code hx pet}); null = the preset's own. */
    public List<String> petLines;
    public String actionbar;
    public BukkitTask actionbarTask;

    private final Deque<String> modApiLog = new ArrayDeque<>();

    public void log(String line) {
        modApiLog.addLast(LocalTime.now().withNano(0) + " " + line);
        while (modApiLog.size() > 15) modApiLog.removeFirst();
    }

    public List<String> modApiLog() {
        return List.copyOf(modApiLog);
    }

    public String serverName() {
        return location.has("serverName") ? location.get("serverName").getAsString() : "mini1A";
    }
}
