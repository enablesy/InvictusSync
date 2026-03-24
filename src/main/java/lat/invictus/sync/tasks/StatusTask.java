package lat.invictus.sync.tasks;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;

public class StatusTask extends BukkitRunnable {

    private final InvictusSync plugin;

    public StatusTask(InvictusSync plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        try {
            // TPS — Paper API
            double tps = Math.min(20.0, Bukkit.getServer().getTPS()[0]);
            tps = Math.round(tps * 100.0) / 100.0;

            int players = Bukkit.getOnlinePlayers().size();
            int maxPlayers = Bukkit.getMaxPlayers();

            // Memory
            Runtime runtime = Runtime.getRuntime();
            long memUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long memMax = runtime.maxMemory() / 1024 / 1024;

            // Uptime approximation via system
            long uptime = System.currentTimeMillis();

            String version = Bukkit.getVersion();

            String json = String.format(
                "{\"players\":%d,\"maxPlayers\":%d,\"tps\":%.2f,\"memUsed\":%d,\"memMax\":%d,\"version\":\"%s\"}",
                players, maxPlayers, tps, memUsed, memMax, WorkerClient.esc(version)
            );

            plugin.getWorkerClient().post("/mc/status", json);

        } catch (Exception e) {
            plugin.getLogger().warning("Error en StatusTask: " + e.getMessage());
        }
    }
}
