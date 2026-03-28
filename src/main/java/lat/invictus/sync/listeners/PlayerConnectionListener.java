package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public class PlayerConnectionListener implements Listener {

    private final InvictusSync plugin;

    public PlayerConnectionListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLogin(PlayerLoginEvent event) {
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) return;

        String playerName = event.getPlayer().getName();
        String uuid = event.getPlayer().getUniqueId().toString();
        String ip = event.getAddress().getHostAddress();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String json = String.format(
                "{\"nick\":\"%s\",\"uuid\":\"%s\",\"ip\":\"%s\",\"ts\":%d}",
                WorkerClient.esc(playerName),
                uuid,
                WorkerClient.esc(ip),
                System.currentTimeMillis()
            );
            plugin.getWorkerClient().post("/mc/player/login", json);
        });
    }
}
