package com.soulreturns.testkit.modapi;

import com.google.gson.JsonObject;
import com.soulreturns.testkit.Nms;
import com.soulreturns.testkit.PlayerState;
import com.soulreturns.testkit.SoulTestKit;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Server half of the Hypixel Mod API handshake, as the client library expects it:
 * <ol>
 *   <li>server pushes {@code hypixel:hello} after join (this sets the client's "on Hypixel" flag);</li>
 *   <li>the client answers with {@code hypixel:register} listing the event packets it subscribed to;</li>
 *   <li>the server pushes {@code hyevent:location} to subscribers on every server switch;</li>
 *   <li>{@code hypixel:party_info}, {@code hypixel:player_info} and {@code hypixel:ping} are
 *       request/response, answered in the requested version or with INVALID_PACKET_VERSION.</li>
 * </ol>
 */
public final class ModApiBridge implements PluginMessageListener {
    private final SoulTestKit plugin;

    public ModApiBridge(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Messenger messenger = Bukkit.getMessenger();
        for (String channel : List.of(HypixelWire.REGISTER, HypixelWire.PARTY_INFO, HypixelWire.PLAYER_INFO, HypixelWire.PING)) {
            messenger.registerIncomingPluginChannel(plugin, channel, this);
        }
        for (String channel : List.of(HypixelWire.HELLO, HypixelWire.LOCATION, HypixelWire.PARTY_INFO, HypixelWire.PLAYER_INFO, HypixelWire.PING)) {
            messenger.registerOutgoingPluginChannel(plugin, channel);
        }
    }

    public void sendHello(Player player) {
        PlayerState state = plugin.state(player);
        state.subscriptions.clear();
        Nms.sendPayload(player, HypixelWire.HELLO, HypixelWire.hello(HypixelWire.ENV_PRODUCTION));
        state.helloSent = true;
        state.log("-> hypixel:hello PRODUCTION");
    }

    /** @return false when the client has not subscribed to hyevent:location v1 (then nothing is sent, like Hypixel). */
    public boolean sendLocation(Player player) {
        PlayerState state = plugin.state(player);
        Integer version = state.subscriptions.get(HypixelWire.LOCATION);
        JsonObject l = state.location;
        String summary = str(l, "serverName") + " " + str(l, "serverType") + " mode=" + str(l, "mode") + " map=" + str(l, "map");
        if (version == null || version != HypixelWire.LOCATION_VERSION) {
            state.log("(not sent, client not subscribed) hyevent:location " + summary);
            return false;
        }
        byte[] payload = HypixelWire.location(state.serverName(), str(l, "serverType"), str(l, "lobbyName"), str(l, "mode"), str(l, "map"));
        Nms.sendPayload(player, HypixelWire.LOCATION, payload);
        state.log("-> hyevent:location " + summary);
        return true;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        PlayerState state = plugin.state(player);
        try {
            switch (channel) {
                case HypixelWire.REGISTER -> {
                    HypixelWire.Register register = HypixelWire.register(message);
                    state.subscriptions = new java.util.LinkedHashMap<>(register.events());
                    state.log("<- hypixel:register v" + register.version() + " " + register.events());
                    sendLocation(player);
                }
                case HypixelWire.PARTY_INFO -> {
                    int version = HypixelWire.requestVersion(message);
                    state.log("<- hypixel:party_info v" + version);
                    // In the server log too, so request pacing can be read with timestamps.
                    plugin.getLogger().info(player.getName() + " requested hypixel:party_info v" + version);
                    if (version < 1 || version > HypixelWire.PARTY_INFO_VERSION) {
                        error(player, state, channel);
                        return;
                    }
                    List<HypixelWire.PartyMember> members = partyMembers(player, state);
                    Nms.sendPayload(player, channel, HypixelWire.partyInfo(version, !members.isEmpty(), members));
                    state.log("-> hypixel:party_info v" + version + " " + state.partyMode + " " + state.partyNames);
                }
                case HypixelWire.PLAYER_INFO -> {
                    int version = HypixelWire.requestVersion(message);
                    state.log("<- hypixel:player_info v" + version);
                    if (version != HypixelWire.PLAYER_INFO_VERSION) {
                        error(player, state, channel);
                        return;
                    }
                    Rank rank = Rank.byName(state.rank);
                    String prefix = state.rankPrefix != null ? state.rankPrefix : rank.prefix;
                    Nms.sendPayload(player, channel, HypixelWire.playerInfo(rank.playerRank, rank.packageRank, rank.monthlyRank, prefix));
                    state.log("-> hypixel:player_info " + state.rank + (prefix == null ? "" : " prefix=" + prefix));
                }
                case HypixelWire.PING -> {
                    int version = HypixelWire.requestVersion(message);
                    state.log("<- hypixel:ping v" + version);
                    if (version != HypixelWire.PING_VERSION) {
                        error(player, state, channel);
                        return;
                    }
                    Nms.sendPayload(player, channel, HypixelWire.ping("pong"));
                    state.log("-> hypixel:ping pong");
                }
                default -> state.log("<- unexpected " + channel + " " + new String(message, StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            state.log("<- malformed " + channel + ": " + e.getMessage());
            plugin.getLogger().warning("malformed " + channel + " from " + player.getName() + ": " + e);
        }
    }

    private void error(Player player, PlayerState state, String channel) {
        Nms.sendPayload(player, channel, HypixelWire.error(HypixelWire.ERROR_INVALID_PACKET_VERSION));
        state.log("-> " + channel + " error INVALID_PACKET_VERSION");
    }

    /** `hx party leader A B`: player leads A and B. `hx party member A B`: A leads, player and B are members. */
    private static List<HypixelWire.PartyMember> partyMembers(Player player, PlayerState state) {
        List<HypixelWire.PartyMember> members = new ArrayList<>();
        if (state.partyMode.equals("none")) return members;
        boolean leader = state.partyMode.equals("leader");
        members.add(new HypixelWire.PartyMember(player.getUniqueId(), leader ? HypixelWire.PartyRole.LEADER : HypixelWire.PartyRole.MEMBER));
        for (int i = 0; i < state.partyNames.size(); i++) {
            boolean isLeader = !leader && i == 0;
            members.add(new HypixelWire.PartyMember(uuidOf(state.partyNames.get(i)),
                isLeader ? HypixelWire.PartyRole.LEADER : HypixelWire.PartyRole.MEMBER));
        }
        return members;
    }

    /** Online players by their real UUID, everyone else by the offline-mode UUID of the name. */
    public static UUID uuidOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        return online != null ? online.getUniqueId()
            : UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
