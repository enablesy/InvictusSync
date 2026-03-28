package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TicketListener implements Listener {

    private final InvictusSync plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 60000; // 1 minuto entre tickets

    public TicketListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (!msg.toLowerCase().startsWith("/ticket ") && !msg.equalsIgnoreCase("/ticket")) return;
        event.setCancelled(true);

        Player player = event.getPlayer();
        String[] parts = msg.split(" ", 2);

        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            player.sendMessage(plugin.getMsg("ticket-usage"));
            return;
        }

        // Cooldown
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(uuid);
        if (last != null && now - last < COOLDOWN_MS) {
            player.sendMessage(plugin.getMsg("ticket-cooldown"));
            return;
        }
        cooldowns.put(uuid, now);

        String description = parts[1].trim();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String json = String.format(
                    "{\"reporter\":\"%s\",\"reporterUuid\":\"%s\",\"reported\":\"SERVIDOR\",\"reason\":\"[TICKET] %s\"}",
                    WorkerClient.esc(player.getName()),
                    player.getUniqueId().toString(),
                    WorkerClient.esc(description)
                );
                plugin.getWorkerClient().post("/mc/report", json);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(plugin.getMsg("ticket-sent"));
                    // Notificar al staff online
                    plugin.getServer().getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("invictussync.link") && !p.equals(player))
                        .forEach(p -> p.sendMessage("§b§l[TICKET] §r§7" + player.getName() + "§7: " + description));
                });
            } catch (Exception e) {
                plugin.getServer().getScheduler().runTask(plugin, () ->
                    player.sendMessage(plugin.getMsg("ticket-error"))
                );
            }
        });
    }
}
