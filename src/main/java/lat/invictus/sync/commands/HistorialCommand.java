package lat.invictus.sync.commands;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.menu.HistorialMenu;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.*;

public class HistorialCommand implements CommandExecutor {

    private final InvictusSync plugin;
    private final HistorialMenu menu;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy HH:mm");

    public HistorialCommand(InvictusSync plugin, HistorialMenu menu) {
        this.plugin = plugin;
        this.menu = menu;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getMsg("player-only"));
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("invictussync.link")) {
            player.sendMessage(plugin.getMsg("no-permission"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.getMsg("historial-usage"));
            return true;
        }

        String targetName = args[0];
        player.sendMessage(plugin.getMsg("historial-loading").replace("{player}", targetName));

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            fetchAndOpen(player, targetName)
        );
        return true;
    }

    public void fetchAndOpen(Player viewer, String targetName) {
        try {
            // Fetch sanciones del jugador
            String sanctionsResponse = plugin.getWorkerClient().getAndRead(
                "/mc/sanctions?search=" + java.net.URLEncoder.encode(targetName, "UTF-8") + "&page=1"
            );

            // Fetch UUID del jugador para buscar IPs
            String uuidResponse = plugin.getWorkerClient().getAndRead(
                "/mc/player/uuid?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8")
            );

            String targetUuid = null;
            if (uuidResponse != null && uuidResponse.contains("\"uuid\"")) {
                targetUuid = uuidResponse.replaceAll(".*\"uuid\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            }

            // Fetch historial de IPs y alts
            String ipsResponse = null;
            if (targetUuid != null) {
                ipsResponse = plugin.getWorkerClient().getAndRead(
                    "/auth/player/ips?uuid=" + targetUuid
                );
            } else {
                ipsResponse = plugin.getWorkerClient().getAndRead(
                    "/auth/player/ips?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8")
                );
            }

            // Construir MenuData
            HistorialMenu.MenuData data = new HistorialMenu.MenuData();
            data.targetName = targetName;
            data.targetUuid = targetUuid;

            // Parsear sanciones (simplificado — el Worker devuelve JSON)
            if (sanctionsResponse != null && sanctionsResponse.contains("\"sanctions\"")) {
                data.sanctions = parseSanctions(sanctionsResponse, targetName);
            }

            // Parsear alts
            if (ipsResponse != null && ipsResponse.contains("\"alts\"")) {
                data.alts = parseAlts(ipsResponse);
            }

            // Parsear primera/última conexión
            if (ipsResponse != null && ipsResponse.contains("\"history\"")) {
                parseConnectionDates(ipsResponse, data);
            }

            if (data.sanctions.isEmpty() && data.alts.isEmpty() && data.targetUuid == null) {
                viewer.sendMessage(plugin.getMsg("historial-not-found").replace("{player}", targetName));
                return;
            }

            menu.openFor(viewer, data);

        } catch (Exception e) {
            plugin.getLogger().warning("Error en /historial: " + e.getMessage());
            viewer.sendMessage(plugin.getMsg("error-contact"));
        }
    }

    // ── PARSERS JSON MANUALES (sin dependencias externas) ────

    private List<Map<String, String>> parseSanctions(String json, String targetName) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            // Extraer array de sanctions
            int start = json.indexOf("\"sanctions\":[");
            if (start == -1) return result;
            start = json.indexOf("[", start) + 1;
            int depth = 1;
            int i = start;
            while (i < json.length() && depth > 0) {
                char c = json.charAt(i);
                if (c == '{') depth++;
                if (c == '}') depth--;
                i++;
            }
            String arrayContent = json.substring(start, i - 1);

            // Separar objetos
            List<String> objects = splitJsonObjects(arrayContent);
            int count = 0;
            for (String obj : objects) {
                if (count >= 15) break;
                String type = extractJsonString(obj, "type");
                String target = extractJsonString(obj, "target");
                // Solo mostrar sanciones donde el jugador es el objetivo
                if (target == null || !target.equalsIgnoreCase(targetName)) continue;
                Map<String, String> s = new HashMap<>();
                s.put("type", type != null ? type : "warn");
                s.put("target", target);
                s.put("staff", extractJsonString(obj, "staff"));
                s.put("reason", extractJsonString(obj, "reason"));
                s.put("duration", extractJsonString(obj, "duration"));
                String ts = extractJsonString(obj, "timestamp");
                if (ts != null) {
                    try {
                        long tsLong = Long.parseLong(ts);
                        s.put("date", DATE_FMT.format(new Date(tsLong)));
                    } catch (NumberFormatException ignored) {}
                }
                result.add(s);
                count++;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error parseando sanciones: " + e.getMessage());
        }
        return result;
    }

    private List<Map<String, String>> parseAlts(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            int start = json.indexOf("\"alts\":[");
            if (start == -1) return result;
            start = json.indexOf("[", start) + 1;
            String arrayContent = extractUntilClose(json, start);
            List<String> objects = splitJsonObjects(arrayContent);
            for (String obj : objects) {
                if (result.size() >= 10) break;
                Map<String, String> alt = new HashMap<>();
                alt.put("nick", extractJsonString(obj, "nick"));
                alt.put("uuid", extractJsonString(obj, "uuid"));
                alt.put("sharedIp", extractJsonString(obj, "sharedIp"));
                if (alt.get("nick") != null) result.add(alt);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error parseando alts: " + e.getMessage());
        }
        return result;
    }

    private void parseConnectionDates(String json, HistorialMenu.MenuData data) {
        try {
            int start = json.indexOf("\"history\":[");
            if (start == -1) return;
            start = json.indexOf("[", start) + 1;
            String arrayContent = extractUntilClose(json, start);
            List<String> objects = splitJsonObjects(arrayContent);
            if (!objects.isEmpty()) {
                // Primer objeto = más reciente
                String firstObj = objects.get(0);
                String ts = extractJsonString(firstObj, "ts");
                if (ts != null) {
                    try { data.lastSeen = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
                }
                // Último objeto = más antiguo
                String lastObj = objects.get(objects.size() - 1);
                ts = extractJsonString(lastObj, "ts");
                if (ts != null) {
                    try { data.firstSeen = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
    }

    private List<String> splitJsonObjects(String array) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start != -1) {
                    objects.add(array.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int valStart = idx + search.length();
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart >= json.length()) return null;
        char first = json.charAt(valStart);
        if (first == '"') {
            int end = json.indexOf('"', valStart + 1);
            if (end == -1) return null;
            return json.substring(valStart + 1, end);
        } else if (first == 'n') {
            return null; // null
        } else {
            // número u otro
            int end = valStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(valStart, end).trim();
        }
    }

    private String extractUntilClose(String json, int start) {
        int depth = 1;
        int i = start;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            if (c == ']') depth--;
            i++;
        }
        return json.substring(start, Math.max(0, i - 1));
    }
}
