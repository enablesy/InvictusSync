package lat.invictus.sync.commands;

import lat.invictus.sync.listeners.LookupListener;
import lat.invictus.sync.InvictusSync;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class LookupCommand implements CommandExecutor {

    private final InvictusSync plugin;
    private final LookupListener listener;

    public LookupCommand(InvictusSync plugin, LookupListener listener) {
        this.plugin = plugin;
        this.listener = listener;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getMsg("player-only"));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("invictussync.link")) {
            player.sendMessage(plugin.getMsg("no-permission"));
            return true;
        }

        if (args.length == 0 || args[0].trim().isEmpty()) {
            player.sendMessage(plugin.getMsg("lookup-usage"));
            return true;
        }

        String target = args[0].trim();
        player.sendMessage(plugin.getMsg("lookup-loading").replace("{player}", target));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            listener.fetchAndOpen(player, target)
        );

        return true;
    }
}
