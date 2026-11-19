package com.soulreturns.testkit;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Hypixel's answer to {@code /party list} for the party {@code hx party} set: dividers, count, leader and
 * members as separate lines, colored as on Hypixel. Every other party subcommand only says it is not
 * simulated, so the server log still shows what the client sent.
 */
final class PartyCommand implements CommandExecutor {
    private static final String DIVIDER = "§9§m-----------------------------------------------------";

    private final SoulTestKit plugin;

    PartyCommand(SoulTestKit plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;
        if (args.length == 0 || !args[0].equalsIgnoreCase("list")) {
            player.sendMessage("§7(test server: /party " + String.join(" ", args) + " is not simulated)");
            return true;
        }
        PlayerState state = plugin.state(player);
        List<String> lines = new ArrayList<>();
        lines.add(DIVIDER);
        if (state.partyMode.equals("none")) {
            lines.add("§cYou are not currently in a party.");
        } else {
            boolean leads = state.partyMode.equals("leader");
            String leader = leads ? player.getName() : state.partyNames.get(0);
            List<String> members = new ArrayList<>();
            if (!leads) members.add(player.getName());
            members.addAll(leads ? state.partyNames : state.partyNames.subList(1, state.partyNames.size()));
            lines.add("§6Party Members (" + (members.size() + 1) + ")");
            lines.add("");
            lines.add("§eParty Leader: §b[MVP§c+§b] " + leader + "§a ● ");
            if (!members.isEmpty()) {
                StringBuilder row = new StringBuilder("§eParty Members: ");
                for (String name : members) row.append("§b[MVP§c+§b] ").append(name).append("§a ● ");
                lines.add(row.toString());
            }
        }
        lines.add(DIVIDER);
        for (String line : lines) Nms.sendSystemChat(player, Txt.of(line), false);
        state.log("party list answered (" + state.partyMode + " " + state.partyNames + ")");
        return true;
    }
}
