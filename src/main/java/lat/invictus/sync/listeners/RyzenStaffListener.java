package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;

import dev.imshadow.RyzenStaff;
import dev.imshadow.API.RyzenStaffApi;
import dev.imshadow.StaffSystem.StaffSystem;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RyzenStaffListener implements Listener {

    private final InvictusSync plugin;
    private final WorkerClient client;
    private RyzenStaff ryzen;
    private final Map<UUID, Boolean> staffModeState = new HashMap<>();
    private final Map<UUID, Boolean> freezeState = new HashMap<>();
    private final Map<UUID, Boolean> adminChatState = new HashMap<>();
    private final Map<UUID, Boolean> staffChatState = new HashMap<>();

    // Debug: un tick = 2s (40L). 45 ticks = ~90s
    private int debugTick = 0;

    public RyzenStaffListener(InvictusSync plugin) {
        this.plugin = plugin;
        this.client = plugin.getWorkerClient();
        try {
            ryzen = (RyzenStaff) Bukkit.getPluginManager().getPlugin("RyzenStaff");
            if (ryzen == null) plugin.getLogger().warning("RyzenStaff no encontrado.");
            else plugin.getLogger().info("RyzenStaff encontrado. Sincronización activa.");
        } catch (Exception e) {
            plugin.getLogger().warning("Error al cargar RyzenStaff: " + e.getMessage());
        }
        startPollingTask();
    }

    private void startPollingTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (ryzen == null || !plugin.getConfig().getBoolean("sync.activity", true)) return;
            debugTick++;
            boolean doDebug = (debugTick % 45 == 0); // ~90s
            StringBuilder debugSummary = doDebug ? new StringBuilder("[InvictusSync] DEBUG estados:") : null;
            try {
                RyzenStaffApi api = new RyzenStaffApi(ryzen);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();

                    // ── STAFF MODE ──
                    boolean inStaffMode;
                    try {
                        inStaffMode = StaffSystem.isInStaffMode(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[InvictusSync] Error isInStaffMode(" + player.getName() + "): " + e.getMessage());
                        continue;
                    }
                    boolean wasInStaffMode = staffModeState.getOrDefault(uuid, false);
                    if (inStaffMode && !wasInStaffMode) {
                        staffModeState.put(uuid, true);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " entró al modo staff.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"staffmode_on\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Entró al modo staff\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    } else if (!inStaffMode && wasInStaffMode) {
                        staffModeState.put(uuid, false);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " salió del modo staff.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"staffmode_off\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Salió del modo staff\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    }

                    // ── ADMIN CHAT ──
                    boolean inAdminChat;
                    try {
                        inAdminChat = api.isAdminChatMode(player);
                    } catch (Exception e) {
                        plugin.getLogger().warning("[InvictusSync] Error isAdminChatMode(" + player.getName() + "): " + e.getMessage());
                        continue;
                    }
                    boolean wasInAdminChat = adminChatState.getOrDefault(uuid, false);

                    // Acumular en resumen debug (una sola linea al final)
                    if (doDebug) {
                        debugSummary.append(" | ").append(player.getName())
                            .append("[sm=").append(inStaffMode ? "1" : "0")
                            .append(",ac=").append(inAdminChat ? "1" : "0")
                            .append("]");
                    }


                    if (inAdminChat && !wasInAdminChat) {
                        adminChatState.put(uuid, true);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " activó el admin chat.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"adminchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Activó el admin chat\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    } else if (!inAdminChat && wasInAdminChat) {
                        adminChatState.put(uuid, false);
                        plugin.getLogger().info("[InvictusSync] " + player.getName() + " desactivó el admin chat.");
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"adminchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Desactivó el admin chat\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    }
                }
                // Imprimir resumen debug si corresponde
                if (doDebug && debugSummary.length() > 28) plugin.getLogger().info(debugSummary.toString());
            } catch (Exception e) {
                plugin.getLogger().warning("[InvictusSync] Error en polling: " + e.getClass().getName() + ": " + e.getMessage());
                // Imprimir stack trace completo para diagnosticar
                for (StackTraceElement el : e.getStackTrace()) {
                    plugin.getLogger().warning("  at " + el.toString());
                }
            }
        }, 40L, 40L);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase();
        Player player = event.getPlayer();
        String[] parts = msg.split(" ");
        String cmd = parts[0].replace("/", "");

        switch (cmd) {
            // ── SANCTIONS ──
            case "ban": case "tempban":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"ban\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName()),
                    WorkerClient.esc(parts.length >= 3 ? joinFrom(parts, 2) : "Sin razón")));
                break;
            case "unban":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"unban\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"Desbanneo\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName())));
                break;
            case "kick":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"kick\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName()),
                    WorkerClient.esc(parts.length >= 3 ? joinFrom(parts, 2) : "Sin razón")));
                break;
            case "mute": case "tempmute":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"mute\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName()),
                    WorkerClient.esc(parts.length >= 3 ? joinFrom(parts, 2) : "Sin razón")));
                break;
            case "unmute":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"unmute\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"Desmuteado\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName())));
                break;
            case "warn":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"warn\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName()),
                    WorkerClient.esc(parts.length >= 3 ? joinFrom(parts, 2) : "Sin razón")));
                break;
            case "jail":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"jail\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName()),
                    WorkerClient.esc(parts.length >= 3 ? joinFrom(parts, 2) : "Sin razón")));
                break;
            case "unjail":
                if (!plugin.getConfig().getBoolean("sync.sanctions", true) || parts.length < 2) return;
                client.post("/mc/sanction", String.format(
                    "{\"type\":\"unjail\",\"target\":\"%s\",\"staff\":\"%s\",\"reason\":\"Liberado de jail\"}",
                    WorkerClient.esc(parts[1]), WorkerClient.esc(player.getName())));
                break;

            // ── FREEZE (toggle) ──
            case "freeze": case "fr":
                if (!plugin.getConfig().getBoolean("sync.activity", true) || parts.length < 2) return;
                String freezeTarget = parts[1];
                Player targetPlayer = Bukkit.getPlayer(freezeTarget);
                UUID targetUuid = targetPlayer != null ? targetPlayer.getUniqueId() : UUID.nameUUIDFromBytes(freezeTarget.getBytes());
                boolean wasFrozen = freezeState.getOrDefault(targetUuid, false);
                if (wasFrozen) {
                    freezeState.put(targetUuid, false);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"unfreeze\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"target\":\"%s\",\"detail\":\"Descongeló a %s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId(),
                        WorkerClient.esc(freezeTarget), WorkerClient.esc(freezeTarget)));
                } else {
                    freezeState.put(targetUuid, true);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"freeze\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"target\":\"%s\",\"detail\":\"Congeló a %s\"}",
                        WorkerClient.esc(player.getName()), player.getUniqueId(),
                        WorkerClient.esc(freezeTarget), WorkerClient.esc(freezeTarget)));
                }
                break;

            // ── STAFF CHAT (toggle) ──
            case "sc": case "staffchat":
                if (!plugin.getConfig().getBoolean("sync.activity", true)) return;
                UUID scUuid = player.getUniqueId();
                boolean wasInStaffChat = staffChatState.getOrDefault(scUuid, false);
                if (wasInStaffChat) {
                    staffChatState.put(scUuid, false);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"staffchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Desactivó el staff chat\"}",
                        WorkerClient.esc(player.getName()), scUuid));
                } else {
                    staffChatState.put(scUuid, true);
                    client.post("/mc/activity", String.format(
                        "{\"type\":\"staffchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Activó el staff chat\"}",
                        WorkerClient.esc(player.getName()), scUuid));
                }
                break;

            // ── REPORTS ──
            case "report":
                if (!plugin.getConfig().getBoolean("sync.reports", true) || parts.length < 3) return;
                Player reportedPlayer = Bukkit.getPlayer(parts[1]);
                String reportedUuid = reportedPlayer != null ? reportedPlayer.getUniqueId().toString() : "";
                client.post("/mc/report", String.format(
                    "{\"reporter\":\"%s\",\"reporterUuid\":\"%s\",\"reported\":\"%s\",\"reportedUuid\":\"%s\",\"reason\":\"%s\"}",
                    WorkerClient.esc(player.getName()), player.getUniqueId(),
                    WorkerClient.esc(parts[1]), reportedUuid,
                    WorkerClient.esc(joinFrom(parts, 2))));
                break;
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        staffModeState.remove(uuid);
        freezeState.remove(uuid);
        // No borrar adminChatState ni staffChatState al desconectarse
        // para evitar falsos positivos si RyzenStaff resetea el estado internamente
        adminChatState.remove(uuid);
        staffChatState.remove(uuid);
    }

    private String joinFrom(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) sb.append(" ");
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
