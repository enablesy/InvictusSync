package lat.invictus.sync;

import lat.invictus.sync.listeners.RyzenStaffListener;
import lat.invictus.sync.listeners.SpamListener;
import lat.invictus.sync.listeners.PlayerConnectionListener;
import lat.invictus.sync.tasks.StatusTask;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InvictusSync extends JavaPlugin {

    private static InvictusSync instance;
    private WorkerClient workerClient;
    private StatusTask statusTask;
    private Handler consoleHandler;
    private final CopyOnWriteArrayList<LogRecord> pendingLogs = new CopyOnWriteArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        workerClient = new WorkerClient(this);
        getServer().getPluginManager().registerEvents(new RyzenStaffListener(this), this);
        getServer().getPluginManager().registerEvents(new SpamListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        if (getConfig().getBoolean("sync.status", true)) {
            int interval = getConfig().getInt("status-interval", 30) * 20;
            statusTask = new StatusTask(this);
            statusTask.runTaskTimerAsynchronously(this, 100L, interval);
        }
        getLogger().info("InvictusSync habilitado. Conectado a: " + getConfig().getString("worker-url"));
        // Sincronizar plugins al arrancar (async, 5 segundos de delay)
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::syncPlugins, 100L);
        // Registrar handler de consola para capturar logs
        setupConsoleHandler();
        // Enviar logs acumulados cada 30 segundos
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::flushLogs, 600L, 600L);
    }

    @Override
    public void onDisable() {
        if (statusTask != null) statusTask.cancel();
        if (workerClient != null) workerClient.shutdown();
        if (consoleHandler != null) {
            Bukkit.getLogger().removeHandler(consoleHandler);
        }
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

        if (command.getName().equalsIgnoreCase("postular")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cEste comando solo puede usarlo un jugador.");
                return true;
            }
            Player player = (Player) sender;
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String json = String.format(
                        "{\"nick\":\"%s\",\"uuid\":\"%s\"}",
                        WorkerClient.esc(player.getName()),
                        player.getUniqueId().toString()
                    );
                    String response = workerClient.postAndRead("/mc/apply/generate", json);
                    if (response != null && response.contains("\"code\"")) {
                        String code = response.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage("§6§l⚔ INVICTUS §r§7— Postulación al equipo");
                        player.sendMessage("§7Visita §6https://invictus.lat/postular §7y usa este código:");
                        player.sendMessage("§e§l  " + code);
                        player.sendMessage("§7El código expira en §e10 minutos§7.");
                    } else {
                        player.sendMessage("§cError al generar el código. Inténtalo de nuevo.");
                    }
                } catch (Exception e) {
                    player.sendMessage("§cError al contactar el servidor. Inténtalo de nuevo.");
                    getLogger().warning("Error en /postular: " + e.getMessage());
                }
            });
            return true;
        }

        if (command.getName().equalsIgnoreCase("syncplugins")) {
            if (!sender.hasPermission("invictussync.admin")) {
                sender.sendMessage("§cNo tienes permiso para hacer esto.");
                return true;
            }
            sender.sendMessage("§eSincronizando plugins...");
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                syncPlugins();
                sender.sendMessage("§aPlugins sincronizados correctamente.");
            });
            return true;
        }

        if (command.getName().equalsIgnoreCase("staff")) {
            // Obtener staff online (excluye vanish)
            List<String> staffOnline = new ArrayList<>();
            for (Player p : getServer().getOnlinePlayers()) {
                if (!p.hasPermission("invictussync.link")) continue; // solo staff tiene este permiso
                // Verificar vanish — compatible con SuperVanish/PremiumVanish/CMI
                if (p.hasMetadata("vanished")) {
                    boolean vanished = p.getMetadata("vanished").stream()
                        .anyMatch(m -> m.asBoolean());
                    if (vanished) continue;
                }
                staffOnline.add(p.getName());
            }
            if (staffOnline.isEmpty()) {
                sender.sendMessage("§6§l⚔ INVICTUS §r§7— No hay staff conectado en este momento.");
            } else {
                sender.sendMessage("§6§l⚔ INVICTUS §r§7— Staff conectado §8(§e" + staffOnline.size() + "§8)§7:");
                for (String name : staffOnline) {
                    sender.sendMessage("§8  · §a" + name);
                }
            }
            return true;
        }

        return false;
    }

    private void syncPlugins() {
        try {
            Plugin[] plugins = getServer().getPluginManager().getPlugins();
            StringBuilder sb = new StringBuilder("{\"plugins\":[");
            for (int i = 0; i < plugins.length; i++) {
                Plugin p = plugins[i];
                if (i > 0) sb.append(",");
                sb.append("{")
                  .append("\"name\":\"").append(WorkerClient.esc(p.getName())).append("\",")
                  .append("\"version\":\"").append(WorkerClient.esc(p.getDescription().getVersion())).append("\",")
                  .append("\"enabled\":").append(p.isEnabled())
                  .append("}");
            }
            sb.append("]}");
            workerClient.post("/mc/plugins/sync", sb.toString());
        } catch (Exception e) {
            getLogger().warning("Error al sincronizar plugins: " + e.getMessage());
        }
    }

    private void setupConsoleHandler() {
        consoleHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "";
                boolean isInvictusSync = loggerName.contains("InvictusSync");
                boolean isWarningOrAbove = level.intValue() >= Level.WARNING.intValue();
                if (isWarningOrAbove || isInvictusSync) {
                    pendingLogs.add(record);
                }
            }
            @Override public void flush() {}
            @Override public void close() {}
        };
        Bukkit.getLogger().addHandler(consoleHandler);
    }

    private void flushLogs() {
        if (pendingLogs.isEmpty()) return;
        List<LogRecord> toSend = new ArrayList<>(pendingLogs);
        pendingLogs.clear();
        if (toSend.isEmpty()) return;
        StringBuilder sb = new StringBuilder("{\"logs\":[");
        for (int i = 0; i < toSend.size(); i++) {
            LogRecord r = toSend.get(i);
            if (i > 0) sb.append(",");
            sb.append("{")
              .append("\"level\":\"").append(r.getLevel().getName()).append("\",")
              .append("\"message\":\"").append(WorkerClient.esc(r.getMessage())).append("\",")
              .append("\"logger\":\"").append(WorkerClient.esc(r.getLoggerName() != null ? r.getLoggerName() : "Server")).append("\",")
              .append("\"ts\":").append(r.getMillis())
              .append("}");
        }
        sb.append("]}");
        workerClient.post("/mc/console/log", sb.toString());
    }

    public static InvictusSync getInstance() { return instance; }
    public WorkerClient getWorkerClient() { return workerClient; }
}
