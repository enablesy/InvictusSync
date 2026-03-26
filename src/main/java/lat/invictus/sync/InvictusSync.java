package lat.invictus.sync;

import lat.invictus.sync.listeners.RyzenStaffListener;
import lat.invictus.sync.tasks.StatusTask;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class InvictusSync extends JavaPlugin {

    private static InvictusSync instance;
    private WorkerClient workerClient;
    private StatusTask statusTask;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        workerClient = new WorkerClient(this);
        getServer().getPluginManager().registerEvents(new RyzenStaffListener(this), this);
        if (getConfig().getBoolean("sync.status", true)) {
            int interval = getConfig().getInt("status-interval", 30) * 20;
            statusTask = new StatusTask(this);
            statusTask.runTaskTimerAsynchronously(this, 100L, interval);
        }
        getLogger().info("InvictusSync habilitado. Conectado a: " + getConfig().getString("worker-url"));
    }

    @Override
    public void onDisable() {
        if (statusTask != null) statusTask.cancel();
        if (workerClient != null) workerClient.shutdown();
        getLogger().info("InvictusSync deshabilitado.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("invictussync")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("invictussync.admin")) {
                    sender.sendMessage("§cNo tenés permiso para hacer esto.");
                    return true;
                }
                reloadConfig();
                workerClient = new WorkerClient(this);
                sender.sendMessage("§aInvictusSync recargado.");
                return true;
            }
            sender.sendMessage("§eUso: /invictussync reload");
            return true;
        }

        if (command.getName().equalsIgnoreCase("link")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cEste comando solo puede usarlo un jugador.");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("invictussync.link")) {
                player.sendMessage("§cNo tenés permiso para usar este comando.");
                return true;
            }
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    // Verificar si ya está vinculado
                    String checkResponse = workerClient.getAndRead(
                        "/mc/link/check?uuid=" + player.getUniqueId().toString()
                    );
                    if (checkResponse != null && checkResponse.contains("\"linked\":true")) {
                        String discordUser = checkResponse.replaceAll(".*\"discordUsername\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage("§6§l⚔ INVICTUS §r§7— Tu cuenta ya está vinculada a §e" + discordUser + "§7 en el portal.");
                        player.sendMessage("§7Si querés cambiarla, desvinculá desde tu perfil en el §6Staff Portal§7.");
                        return;
                    }

                    // Generar código nuevo
                    String json = String.format(
                        "{\"nick\":\"%s\",\"uuid\":\"%s\"}",
                        WorkerClient.esc(player.getName()),
                        player.getUniqueId().toString()
                    );
                    String response = workerClient.postAndRead("/mc/link/generate", json);
                    if (response != null && response.contains("\"code\"")) {
                        String code = response.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage("§6§l⚔ INVICTUS §r§7— Vinculación de cuenta");
                        player.sendMessage("§7Tu código de vinculación es:");
                        player.sendMessage("§e§l  " + code);
                        player.sendMessage("§7Ingresalo en tu perfil del §6Staff Portal§7 en los próximos §e5 minutos§7.");
                    } else {
                        player.sendMessage("§cError al generar el código. Intentá de nuevo.");
                    }
                } catch (Exception e) {
                    player.sendMessage("§cError al contactar el servidor. Intentá de nuevo.");
                    getLogger().warning("Error en /link: " + e.getMessage());
                }
            });
            return true;
        }

        return false;
    }

    public static InvictusSync getInstance() { return instance; }
    public WorkerClient getWorkerClient() { return workerClient; }
}
