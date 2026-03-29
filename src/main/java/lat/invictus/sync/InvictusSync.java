package lat.invictus.sync;

import lat.invictus.sync.commands.LookupCommand;
import lat.invictus.sync.filter.WordFilter;
import lat.invictus.sync.listeners.LookupListener;
import lat.invictus.sync.listeners.PlayerConnectionListener;
import lat.invictus.sync.listeners.RyzenStaffListener;
import lat.invictus.sync.listeners.SpamListener;
import lat.invictus.sync.listeners.TicketListener;
import lat.invictus.sync.tasks.AlertTask;
import lat.invictus.sync.tasks.StatusTask;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
    private AlertTask alertTask;
    private Handler consoleHandler;
    private WordFilter wordFilter;
    private LookupListener lookupListener; // ← referencia guardada
    private final CopyOnWriteArrayList<LogRecord> pendingLogs = new CopyOnWriteArrayList<>();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        workerClient = new WorkerClient(this);
        wordFilter = new WordFilter(this);

        getServer().getPluginManager().registerEvents(new RyzenStaffListener(this), this);
        getServer().getPluginManager().registerEvents(new SpamListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new TicketListener(this), this);

        // Registrar LookupListener y asignar executor dedicado al comando /lookup
        lookupListener = new LookupListener(this);
        getServer().getPluginManager().registerEvents(lookupListener, this);
        getCommand("lookup").setExecutor(new LookupCommand(this, lookupListener));

        if (getConfig().getBoolean("sync.status", true)) {
            int interval = getConfig().getInt("status-interval", 30) * 20;
            statusTask = new StatusTask(this);
            statusTask.runTaskTimerAsynchronously(this, 100L, interval);
        }

        int alertInterval = getConfig().getInt("alerts.check-interval", 100);
        alertTask = new AlertTask(this);
        alertTask.runTaskTimerAsynchronously(this, 200L, alertInterval);

        log("&aHabilitado &7— conectado a &e" + getConfig().getString("worker-url"));
        log("&7Comandos registrados: " +
            getDescription().getCommands().keySet().toString());
        // Log con color usando Bukkit console sender
        Bukkit.getConsoleSender().sendMessage(translateColors(
            "&#C8A44A&l[InvictusSync] &r&#C8A44AHabilitado &7— conectado a &e" + getConfig().getString("worker-url")));

        if (getConfig().getBoolean("sync.plugins", true))
            getServer().getScheduler().runTaskLaterAsynchronously(this, this::syncPlugins, 100L);

        setupConsoleHandler();
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::flushLogs, 600L, 600L);
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::syncWordFilter, 200L);
    }

    @Override
    public void onDisable() {
        if (statusTask != null) statusTask.cancel();
        if (alertTask != null) alertTask.cancel();
        if (workerClient != null) workerClient.shutdown();
        if (consoleHandler != null) Bukkit.getLogger().removeHandler(consoleHandler);
        log("&7Deshabilitado.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // debug log eliminado

        // ── /invictussync ─────────────────────────────────────
        if (command.getName().equalsIgnoreCase("invictussync")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("invictussync.admin")) { sender.sendMessage(getMsg("no-permission")); return true; }
                reloadConfig();
                workerClient = new WorkerClient(this);
                wordFilter.reload();
                getServer().getScheduler().runTaskAsynchronously(this, this::syncWordFilter);
                sender.sendMessage(getMsg("reload-done"));
                return true;
            }
            sender.sendMessage("§eUso: /invictussync reload");
            return true;
        }

        // ── /syncplugins ──────────────────────────────────────
        if (command.getName().equalsIgnoreCase("syncplugins")) {
            if (!sender.hasPermission("invictussync.admin")) { sender.sendMessage(getMsg("no-permission")); return true; }
            sender.sendMessage(getMsg("syncplugins-start"));
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                syncPlugins();
                sender.sendMessage(getMsg("syncplugins-done"));
            });
            return true;
        }

        // ── /link ─────────────────────────────────────────────
        if (command.getName().equalsIgnoreCase("link")) {
            if (!(sender instanceof Player)) { sender.sendMessage(getMsg("player-only")); return true; }
            Player player = (Player) sender;
            if (!player.hasPermission("invictussync.link")) { player.sendMessage(getMsg("no-permission")); return true; }
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String checkResponse = workerClient.getAndRead("/mc/link/check?uuid=" + player.getUniqueId());
                    if (checkResponse != null && checkResponse.contains("\"linked\":true")) {
                        String discordUser = checkResponse.replaceAll(".*\"discordUsername\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage(getMsg("link-already-linked").replace("{discord}", discordUser));
                        player.sendMessage(getMsg("link-already-linked-hint"));
                        return;
                    }
                    String json = String.format("{\"nick\":\"%s\",\"uuid\":\"%s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId().toString());
                    String response = workerClient.postAndRead("/mc/link/generate", json);
                    if (response != null && response.contains("\"code\"")) {
                        String code = response.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage(getMsg("link-header"));
                        player.sendMessage(getMsg("link-code-label"));
                        player.sendMessage("§e§l  " + code);
                        player.sendMessage(getMsg("link-code-hint"));
                    } else {
                        player.sendMessage(getMsg("link-error"));
                    }
                } catch (Exception e) {
                    player.sendMessage(getMsg("error-contact"));
                    getLogger().warning("Error en /link: " + e.getMessage());
                }
            });
            return true;
        }

        // ── /postular ─────────────────────────────────────────
        if (command.getName().equalsIgnoreCase("postular")) {
            if (!(sender instanceof Player)) { sender.sendMessage(getMsg("player-only")); return true; }
            Player player = (Player) sender;
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    String json = String.format("{\"nick\":\"%s\",\"uuid\":\"%s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId().toString());
                    String response = workerClient.postAndRead("/mc/apply/generate", json);
                    if (response != null && response.contains("\"code\"")) {
                        String code = response.replaceAll(".*\"code\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                        player.sendMessage(getMsg("postular-header"));
                        player.sendMessage(getMsg("postular-url"));
                        player.sendMessage("§e§l  " + code);
                        player.sendMessage(getMsg("postular-hint"));
                    } else {
                        player.sendMessage(getMsg("postular-error"));
                    }
                } catch (Exception e) {
                    player.sendMessage(getMsg("error-contact"));
                    getLogger().warning("Error en /postular: " + e.getMessage());
                }
            });
            return true;
        }

        // ── /staff ────────────────────────────────────────────
        if (command.getName().equalsIgnoreCase("staff")) {
            List<String> staffOnline = new ArrayList<>();
            for (Player p : getServer().getOnlinePlayers()) {
                if (!p.hasPermission("invictussync.link")) continue;
                if (p.hasMetadata("vanished") && p.getMetadata("vanished").stream().anyMatch(m -> m.asBoolean())) continue;
                staffOnline.add(p.getName());
            }
            if (staffOnline.isEmpty()) {
                sender.sendMessage(getMsg("staff-empty"));
            } else {
                sender.sendMessage(getMsg("staff-header").replace("{count}", String.valueOf(staffOnline.size())));
                for (String name : staffOnline) {
                    sender.sendMessage(getMsg("staff-entry").replace("{name}", name));
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

    private void syncWordFilter() {
        try {
            String response = workerClient.getAndRead("/auth/word-filter");
            if (response != null && response.contains("\"words\"")) {
                int start = response.indexOf("\"words\":[");
                if (start != -1) {
                    start = response.indexOf("[", start) + 1;
                    int end = response.indexOf("]", start);
                    String wordsArr = response.substring(start, end);
                    for (String w : wordsArr.split(",")) {
                        String word = w.trim().replace("\"", "");
                        if (!word.isEmpty()) wordFilter.addWord(word);
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("Error al sincronizar filtro de palabras: " + e.getMessage());
        }
    }

    private void setupConsoleHandler() {
        consoleHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                Level level = record.getLevel();
                String loggerName = record.getLoggerName() != null ? record.getLoggerName() : "";
                String message = record.getMessage() != null ? record.getMessage() : "";
                // Capturar: WARNING+, cualquier log del plugin (nombre o mensaje con InvictusSync)
                boolean isWarningOrAbove = level.intValue() >= Level.WARNING.intValue();
                boolean isPlugin = loggerName.contains("InvictusSync")
                    || loggerName.contains("lat.invictus")
                    || message.contains("[InvictusSync]");
                if (isWarningOrAbove || isPlugin) {
                    // Filtrar el log de debug de RyzenStaff que no queremos
                    if (message.contains("DEBUG estados:")) return;
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

    public String getMsg(String key) {
        String prefix = getConfig().getString("messages.prefix", "§6§l⚔ INVICTUS §r");
        String msg    = getConfig().getString("messages." + key, "§cMensaje no configurado: " + key);
        return translateColors(msg.replace("{prefix}", translateColors(prefix)));
    }

    /**
     * Traduce & → §, &#rrggbb → §x§r§r§g§g§b§b, y <#rrggbb> (MiniMessage básico)
     */
    public static String translateColors(String text) {
        if (text == null) return "";
        // Primero: &#rrggbb → §x§r§r§g§g§b§b (formato BungeeCord/Birdflop)
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("&#([A-Fa-f0-9]{6})").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append("§").append(c);
            m.appendReplacement(sb, replacement.toString());
        }
        m.appendTail(sb);
        text = sb.toString();

        // Segundo: <#rrggbb> → §x§r§r§g§g§b§b (MiniMessage básico)
        m = java.util.regex.Pattern.compile("<#([A-Fa-f0-9]{6})>").matcher(text);
        sb = new StringBuffer();
        while (m.find()) {
            String hex = m.group(1);
            StringBuilder replacement = new StringBuilder("§x");
            for (char c : hex.toCharArray()) replacement.append("§").append(c);
            m.appendReplacement(sb, replacement.toString());
        }
        m.appendTail(sb);
        text = sb.toString();

        // Tercero: & → §
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static InvictusSync getInstance() { return instance; }
    public WorkerClient getWorkerClient() { return workerClient; }
    public WordFilter getWordFilter() { return wordFilter; }
    public LookupListener getLookupListener() { return lookupListener; }

    /** Log informativo con color dorado en consola */
    public void log(String message) {
        Bukkit.getConsoleSender().sendMessage(
            translateColors("&#C8A44A[InvictusSync] &r" + message)
        );
    }

    /** Warning — va al logger de Bukkit Y a la consola en rojo */
    public void warn(String message) {
        getLogger().warning(message);
        Bukkit.getConsoleSender().sendMessage(
            translateColors("&c[InvictusSync] &7" + message)
        );
    }
}
