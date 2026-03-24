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
import org.bukkit.event.player.AsyncPlayerChatEvent;
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
            try {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    UUID uuid = player.getUniqueId();
                    boolean inStaffMode = StaffSystem.isInStaffMode(player);
                    boolean wasInStaffMode = staffModeState.getOrDefault(uuid, false);
                    if (inStaffMode && !wasInStaffMode) {
                        staffModeState.put(uuid, true);
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"staffmode_on\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Entró al modo staff\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    } else if (!inStaffMode && wasInStaffMode) {
                        staffModeState.put(uuid, false);
                        client.post("/mc/activity", String.format(
                            "{\"type\":\"staffmode_off\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"Salió del modo staff\"}",
                            WorkerClient.esc(player.getName()), uuid));
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error en polling: " + e.getMessage());
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("sync.activity", true)) return;
        if (ryzen == null) return;
        Player player = event.getPlayer();
        try {
            RyzenStaffApi api = new RyzenStaffApi(ryzen);
            if (api.isAdminChatMode(player)) {
                client.post("/mc/activity", String.format(
                    "{\"type\":\"adminchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"%s\"}",
                    WorkerClient.esc(player.getName()), player.getUniqueId(),
                    WorkerClient.esc(event.getMessage())));
            } else if (api.isStaffChatMode(player)) {
                client.post("/mc/activity", String.format(
                    "{\"type\":\"staffchat\",\"staff\":\"%s\",\"staffUuid\":\"%s\",\"detail\":\"%s\"}",
                    WorkerClient.esc(player.getName()), player.getUniqueId(),
                    WorkerClient.esc(event.getMessage())));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error en chat listener: " + e.getMessage());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        staffModeState.remove(uuid);
        freezeState.remove(uuid);
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
