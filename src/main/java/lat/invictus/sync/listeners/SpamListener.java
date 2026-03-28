package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpamListener implements Listener {

    private final InvictusSync plugin;

    // UUID → lista de timestamps de mensajes recientes
    private final Map<UUID, Deque<Long>> messageTimestamps = new ConcurrentHashMap<>();
    // UUID → lista de mensajes recientes para detectar repetición
    private final Map<UUID, Deque<String>> recentMessages = new ConcurrentHashMap<>();
    // UUID → timestamp del último reporte enviado (evitar spam de reportes)
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();

    // Configuración de detección
    private static final int FLOOD_COUNT = 6;         // mensajes en la ventana de tiempo
    private static final long FLOOD_WINDOW_MS = 5000; // ventana de 5 segundos
    private static final int REPEAT_COUNT = 4;         // mismo mensaje repetido N veces
    private static final long REPEAT_WINDOW_MS = 8000; // en 8 segundos
    private static final long REPORT_COOLDOWN_MS = 30000; // 30s entre reportes del mismo jugador

    public SpamListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        // El staff no se reporta a sí mismo
        if (player.hasPermission("invictussync.link")) return;

        UUID uuid = player.getUniqueId();
        String message = event.getMessage().trim().toLowerCase();
        long now = System.currentTimeMillis();

        // ── FLOOD: muchos mensajes en poco tiempo ──
        messageTimestamps.putIfAbsent(uuid, new ArrayDeque<>());
        Deque<Long> timestamps = messageTimestamps.get(uuid);
        timestamps.addLast(now);
        // Limpiar mensajes fuera de la ventana
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > FLOOD_WINDOW_MS) {
            timestamps.pollFirst();
        }
        if (timestamps.size() >= FLOOD_COUNT) {
            sendSpamReport(player, "flood",
                "§c[FLOOD] §7" + timestamps.size() + " mensajes en " + (FLOOD_WINDOW_MS / 1000) + " segundos.");
            return;
        }

        // ── REPEAT: mismo mensaje repetido ──
        recentMessages.putIfAbsent(uuid, new ArrayDeque<>());
        Deque<String> recent = recentMessages.get(uuid);
        recent.addLast(message);
        if (recent.size() > REPEAT_COUNT + 2) recent.pollFirst();

        // Contar cuántas veces aparece el mensaje actual
        long repeatCount = recent.stream().filter(m -> m.equals(message)).count();
        if (repeatCount >= REPEAT_COUNT) {
            sendSpamReport(player, "repeat",
                "§c[SPAM] §7\"" + event.getMessage().substring(0, Math.min(event.getMessage().length(), 60)) + "\" repetido " + repeatCount + " veces.");
        }
    }

    private void sendSpamReport(Player player, String spamType, String detail) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Cooldown para no enviar múltiples reportes seguidos
        Long lastTime = lastReport.get(uuid);
        if (lastTime != null && now - lastTime < REPORT_COOLDOWN_MS) return;
        lastReport.put(uuid, now);

        // Limpiar historial para este jugador
        messageTimestamps.getOrDefault(uuid, new ArrayDeque<>()).clear();
        recentMessages.getOrDefault(uuid, new ArrayDeque<>()).clear();

        // Enviar reporte al Worker de forma asíncrona
        String json = String.format(
            "{\"reporter\":\"SISTEMA\",\"reported\":\"%s\",\"reportedUuid\":\"%s\",\"reason\":\"[Auto] Spam detectado (%s): %s\"}",
            WorkerClient.esc(player.getName()),
            player.getUniqueId().toString(),
            spamType,
            WorkerClient.esc(detail)
        );
        plugin.getWorkerClient().post("/mc/report", json);
        plugin.getLogger().info("[SpamDetector] Reporte automático enviado para " + player.getName() + " (" + spamType + ")");

        // Notificar al staff en el servidor
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player staff : plugin.getServer().getOnlinePlayers()) {
                if (staff.hasPermission("invictussync.link")) {
                    staff.sendMessage("§c§l[SPAM] §r§7Reporte automático: §e" + player.getName() + " §7— " + detail);
                }
            }
        });
    }
}
