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
    private final Map<UUID, Deque<Long>> messageTimestamps = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> recentMessages = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();

    public SpamListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("invictussync.link")) return; // staff no se reporta

        UUID uuid = player.getUniqueId();
        String message = event.getMessage().trim();
        String messageLower = message.toLowerCase();
        long now = System.currentTimeMillis();

        int floodCount = plugin.getConfig().getInt("spam.flood-count", 6);
        long floodWindow = plugin.getConfig().getLong("spam.flood-window-ms", 5000);
        int repeatCount = plugin.getConfig().getInt("spam.repeat-count", 4);
        long repeatWindow = plugin.getConfig().getLong("spam.repeat-window-ms", 8000);
        long reportCooldown = plugin.getConfig().getLong("spam.report-cooldown-ms", 30000);
        double capsThreshold = plugin.getConfig().getDouble("spam.caps-threshold", 0.70);
        int capsMinLength = plugin.getConfig().getInt("spam.caps-min-length", 8);

        // ── PALABRAS PROHIBIDAS ──
        String matchedWord = plugin.getWordFilter().findMatch(messageLower);
        if (matchedWord != null) {
            String action = plugin.getConfig().getString("word-filter.action", "both");
            if (action.equals("report") || action.equals("both")) {
                sendReport(player, "word_filter",
                    "Palabra prohibida detectada: \"" + matchedWord + "\"", reportCooldown, false);
            }
            if (action.equals("notify") || action.equals("both")) {
                notifyStaff(plugin.getMsg("caps-staff-notify").replace("{player}", player.getName())
                    + " §7[Palabra: §e" + matchedWord + "§7]");
            }
            return;
        }

        // ── CAPS ──
        if (message.length() >= capsMinLength) {
            long upperCount = message.chars().filter(Character::isUpperCase).count();
            long letterCount = message.chars().filter(Character::isLetter).count();
            if (letterCount > 0 && (double) upperCount / letterCount >= capsThreshold) {
                notifyStaff(plugin.getMsg("caps-staff-notify").replace("{player}", player.getName()));
            }
        }

        // ── FLOOD ──
        messageTimestamps.putIfAbsent(uuid, new ArrayDeque<>());
        Deque<Long> timestamps = messageTimestamps.get(uuid);
        timestamps.addLast(now);
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() > floodWindow)
            timestamps.pollFirst();
        if (timestamps.size() >= floodCount) {
            sendReport(player, "flood",
                "[FLOOD] " + timestamps.size() + " mensajes en " + (floodWindow / 1000) + "s",
                reportCooldown, true);
            return;
        }

        // ── REPEAT ──
        recentMessages.putIfAbsent(uuid, new ArrayDeque<>());
        Deque<String> recent = recentMessages.get(uuid);
        recent.addLast(messageLower);
        if (recent.size() > repeatCount + 2) recent.pollFirst();
        long count = recent.stream().filter(m -> m.equals(messageLower)).count();
        if (count >= repeatCount) {
            sendReport(player, "repeat",
                "[SPAM] Mensaje repetido " + count + " veces: \"" + truncate(message, 40) + "\"",
                reportCooldown, true);
        }
    }

    private void sendReport(Player player, String type, String detail, long cooldown, boolean clearHistory) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long lastTime = lastReport.get(uuid);
        if (lastTime != null && now - lastTime < cooldown) return;
        lastReport.put(uuid, now);

        if (clearHistory) {
            messageTimestamps.getOrDefault(uuid, new ArrayDeque<>()).clear();
            recentMessages.getOrDefault(uuid, new ArrayDeque<>()).clear();
        }

        String json = String.format(
            "{\"reporter\":\"SISTEMA\",\"reported\":\"%s\",\"reportedUuid\":\"%s\",\"reason\":\"[Auto] %s\"}",
            WorkerClient.esc(player.getName()),
            player.getUniqueId().toString(),
            WorkerClient.esc(detail)
        );
        plugin.getWorkerClient().post("/mc/report", json);
        plugin.getLogger().info("[SpamDetector] Reporte auto: " + player.getName() + " — " + type);

        String staffMsg = plugin.getMsg("spam-staff-notify")
            .replace("{player}", player.getName())
            .replace("{detail}", detail);
        notifyStaff(staffMsg);
    }

    private void notifyStaff(String message) {
        if (!plugin.getConfig().getBoolean("spam.notify-staff", true)) return;
        plugin.getServer().getScheduler().runTask(plugin, () ->
            plugin.getServer().getOnlinePlayers().stream()
                .filter(p -> p.hasPermission("invictussync.link"))
                .forEach(p -> p.sendMessage(message))
        );
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
