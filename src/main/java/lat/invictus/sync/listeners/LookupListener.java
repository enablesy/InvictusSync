package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LookupListener implements Listener {

    private final InvictusSync plugin;
    private final Map<UUID, String> openMenus = new ConcurrentHashMap<>();
    private final Map<UUID, MenuData> menuData = new ConcurrentHashMap<>();
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy HH:mm");

    public LookupListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    public static class MenuData {
        public String targetName;
        public String targetUuid;
        public List<Map<String, String>> sanctions = new ArrayList<>();
        public List<Map<String, String>> alts = new ArrayList<>();
        public String firstSeen;
        public String lastSeen;
    }

    // ── INTERCEPTAR /lookup ANTES QUE ESSENTIALS ─────────────
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        String lower = msg.toLowerCase();
        if (!lower.startsWith("/lookup ") && !lower.equals("/lookup")) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("invictussync.link")) return; // dejar pasar a Essentials si no es staff

        event.setCancelled(true);

        String[] parts = msg.split(" ", 2);
        if (parts.length < 2 || parts[1].trim().isEmpty()) {
            player.sendMessage(plugin.getMsg("lookup-usage"));
            return;
        }

        String targetName = parts[1].trim();
        player.sendMessage(plugin.getMsg("lookup-loading").replace("{player}", targetName));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
            fetchAndOpen(player, targetName)
        );
    }

    // ── FETCH Y APERTURA DEL MENÚ ─────────────────────────────
    public void fetchAndOpen(Player viewer, String targetName) {
        try {
            String sanctionsResponse = plugin.getWorkerClient().getAndRead(
                "/mc/sanctions?search=" + java.net.URLEncoder.encode(targetName, "UTF-8") + "&page=1"
            );

            String uuidResponse = plugin.getWorkerClient().getAndRead(
                "/mc/player/uuid?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8")
            );

            String targetUuid = null;
            if (uuidResponse != null && uuidResponse.contains("\"uuid\"")) {
                String extracted = uuidResponse.replaceAll(".*\"uuid\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                if (!extracted.equals(uuidResponse)) targetUuid = extracted;
            }

            String ipsResponse = null;
            if (targetUuid != null) {
                ipsResponse = plugin.getWorkerClient().getAndRead("/auth/player/ips?uuid=" + targetUuid);
            } else {
                ipsResponse = plugin.getWorkerClient().getAndRead(
                    "/auth/player/ips?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8")
                );
            }

            MenuData data = new MenuData();
            data.targetName = targetName;
            data.targetUuid = targetUuid;

            if (sanctionsResponse != null && sanctionsResponse.contains("\"sanctions\"")) {
                data.sanctions = parseSanctions(sanctionsResponse, targetName);
            }
            if (ipsResponse != null && ipsResponse.contains("\"alts\"")) {
                data.alts = parseAlts(ipsResponse);
            }
            if (ipsResponse != null && ipsResponse.contains("\"history\"")) {
                parseConnectionDates(ipsResponse, data);
            }

            openFor(viewer, data);

        } catch (Exception e) {
            plugin.getLogger().warning("Error en /lookup: " + e.getMessage());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                viewer.sendMessage(plugin.getMsg("error-contact"))
            );
        }
    }

    // ── MENÚ GUI ──────────────────────────────────────────────
    public void openFor(Player viewer, MenuData data) {
        openMenus.put(viewer.getUniqueId(), data.targetName);
        menuData.put(viewer.getUniqueId(), data);
        plugin.getServer().getScheduler().runTask(plugin, () ->
            viewer.openInventory(buildMenu(data))
        );
    }

    private Inventory buildMenu(MenuData data) {
        String title = ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + data.targetName + ChatColor.DARK_GRAY + " — Lookup";
        Inventory inv = Bukkit.createInventory(null, 54, title);

        ItemStack bg = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        ItemStack border = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, bg);
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,45,46,47,48,49,50,51,52,53}) inv.setItem(i, border);

        // Cabeza del jugador
        ItemStack head = makeSkull(data.targetName);
        ItemMeta headMeta = head.getItemMeta();
        headMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + data.targetName);
        headMeta.setLore(Arrays.asList(
            ChatColor.DARK_GRAY + "UUID: " + ChatColor.GRAY + (data.targetUuid != null ? data.targetUuid : "Desconocido"),
            ChatColor.DARK_GRAY + "Primera conexión: " + ChatColor.GRAY + (data.firstSeen != null ? data.firstSeen : "—"),
            ChatColor.DARK_GRAY + "Última conexión: " + ChatColor.GRAY + (data.lastSeen != null ? data.lastSeen : "—")
        ));
        head.setItemMeta(headMeta);
        inv.setItem(4, head);

        // Header sanciones
        inv.setItem(9, makeItem(Material.BOOK,
            ChatColor.RED + "" + ChatColor.BOLD + "Sanciones recientes",
            Collections.singletonList(ChatColor.GRAY + "" + data.sanctions.size() + " registradas")));

        // Sanciones
        int slot = 10;
        for (Map<String, String> s : data.sanctions) {
            if (slot > 21) break;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "Razón: " + ChatColor.GRAY + truncate(s.getOrDefault("reason", "—"), 40));
            lore.add(ChatColor.DARK_GRAY + "Staff: " + ChatColor.YELLOW + s.getOrDefault("staff", "?"));
            lore.add(ChatColor.DARK_GRAY + "Fecha: " + ChatColor.GRAY + s.getOrDefault("date", "?"));
            String dur = s.get("duration");
            if (dur != null) lore.add(ChatColor.DARK_GRAY + "Duración: " + ChatColor.GRAY + dur);
            String type = s.getOrDefault("type", "warn");
            inv.setItem(slot, makeItem(getSanctionMaterial(type),
                getSanctionColor(type) + getTypeName(type) + ChatColor.DARK_GRAY + " → " + ChatColor.WHITE + s.getOrDefault("target", "?"),
                lore));
            slot++;
        }
        if (data.sanctions.isEmpty()) {
            inv.setItem(10, makeItem(Material.LIME_DYE,
                ChatColor.GREEN + "Sin sanciones registradas",
                Collections.singletonList(ChatColor.GRAY + "Este jugador no tiene historial.")));
        }

        // Separador
        inv.setItem(22, makeItem(Material.WHITE_STAINED_GLASS_PANE, " "));

        // Header alts
        inv.setItem(23, makeItem(Material.COMPASS,
            ChatColor.YELLOW + "" + ChatColor.BOLD + "Posibles cuentas alternativas",
            Collections.singletonList(ChatColor.GRAY + "" + data.alts.size() + " detectadas")));

        // Alts
        int altSlot = 24;
        for (Map<String, String> alt : data.alts) {
            if (altSlot > 34) break;
            String altNick = alt.getOrDefault("nick", "?");
            ItemStack altHead = makeSkull(altNick);
            ItemMeta m = altHead.getItemMeta();
            m.setDisplayName(ChatColor.YELLOW + altNick);
            m.setLore(Arrays.asList(
                ChatColor.DARK_GRAY + "IP compartida: " + ChatColor.GRAY + alt.getOrDefault("sharedIp", "?"),
                ChatColor.YELLOW + "» Clic para ver su lookup"
            ));
            altHead.setItemMeta(m);
            inv.setItem(altSlot, altHead);
            altSlot++;
        }
        if (data.alts.isEmpty()) {
            inv.setItem(24, makeItem(Material.LIME_DYE,
                ChatColor.GREEN + "Sin alts detectados",
                Collections.singletonList(ChatColor.GRAY + "No se encontraron IPs compartidas.")));
        }

        inv.setItem(35, makeItem(Material.WHITE_STAINED_GLASS_PANE, " "));
        inv.setItem(49, makeItem(Material.BARRIER, ChatColor.RED + "Cerrar"));
        return inv;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        if (!openMenus.containsKey(viewer.getUniqueId())) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (clicked.getType() == Material.BARRIER) { viewer.closeInventory(); return; }

        int slot = event.getSlot();
        if (slot >= 24 && slot <= 34 && clicked.getType() == Material.PLAYER_HEAD) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String altName = ChatColor.stripColor(meta.getDisplayName());
                viewer.closeInventory();
                viewer.sendMessage(plugin.getMsg("lookup-loading").replace("{player}", altName));
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> fetchAndOpen(viewer, altName));
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
        menuData.remove(event.getPlayer().getUniqueId());
    }

    // ── PARSERS ───────────────────────────────────────────────
    private List<Map<String, String>> parseSanctions(String json, String targetName) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            int start = json.indexOf("\"sanctions\":[");
            if (start == -1) return result;
            start = json.indexOf("[", start) + 1;
            List<String> objects = splitJsonObjects(extractUntilClose(json, start, '[', ']'));
            for (String obj : objects) {
                if (result.size() >= 15) break;
                String target = extractJsonString(obj, "target");
                if (target == null || !target.equalsIgnoreCase(targetName)) continue;
                Map<String, String> s = new HashMap<>();
                s.put("type", extractJsonString(obj, "type"));
                s.put("target", target);
                s.put("staff", extractJsonString(obj, "staff"));
                s.put("reason", extractJsonString(obj, "reason"));
                s.put("duration", extractJsonString(obj, "duration"));
                String ts = extractJsonString(obj, "timestamp");
                if (ts != null) {
                    try { s.put("date", DATE_FMT.format(new Date(Long.parseLong(ts)))); } catch (Exception ignored) {}
                }
                result.add(s);
            }
        } catch (Exception e) { plugin.getLogger().warning("parseSanctions: " + e.getMessage()); }
        return result;
    }

    private List<Map<String, String>> parseAlts(String json) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            int start = json.indexOf("\"alts\":[");
            if (start == -1) return result;
            start = json.indexOf("[", start) + 1;
            for (String obj : splitJsonObjects(extractUntilClose(json, start, '[', ']'))) {
                if (result.size() >= 10) break;
                Map<String, String> alt = new HashMap<>();
                alt.put("nick", extractJsonString(obj, "nick"));
                alt.put("uuid", extractJsonString(obj, "uuid"));
                alt.put("sharedIp", extractJsonString(obj, "sharedIp"));
                if (alt.get("nick") != null) result.add(alt);
            }
        } catch (Exception e) { plugin.getLogger().warning("parseAlts: " + e.getMessage()); }
        return result;
    }

    private void parseConnectionDates(String json, MenuData data) {
        try {
            int start = json.indexOf("\"history\":[");
            if (start == -1) return;
            start = json.indexOf("[", start) + 1;
            List<String> objects = splitJsonObjects(extractUntilClose(json, start, '[', ']'));
            if (!objects.isEmpty()) {
                String ts = extractJsonString(objects.get(0), "ts");
                if (ts != null) try { data.lastSeen = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
                ts = extractJsonString(objects.get(objects.size() - 1), "ts");
                if (ts != null) try { data.firstSeen = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private List<String> splitJsonObjects(String array) {
        List<String> objects = new ArrayList<>();
        int depth = 0, start = -1;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '{') { if (depth == 0) start = i; depth++; }
            else if (c == '}') { depth--; if (depth == 0 && start != -1) { objects.add(array.substring(start, i + 1)); start = -1; } }
        }
        return objects;
    }

    private String extractUntilClose(String json, int start, char open, char close) {
        int depth = 1, i = start;
        while (i < json.length() && depth > 0) {
            char c = json.charAt(i);
            if (c == open) depth++;
            else if (c == close) depth--;
            i++;
        }
        return json.substring(start, Math.max(0, i - 1));
    }

    private String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx == -1) return null;
        int valStart = idx + search.length();
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart >= json.length()) return null;
        char first = json.charAt(valStart);
        if (first == '"') {
            int end = json.indexOf('"', valStart + 1);
            return end == -1 ? null : json.substring(valStart + 1, end);
        } else if (first == 'n') return null;
        else {
            int end = valStart;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(valStart, end).trim();
        }
    }

    // ── ITEM HELPERS ──────────────────────────────────────────
    private ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack makeItem(Material mat, String name) { return makeItem(mat, name, null); }

    private ItemStack makeSkull(String playerName) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta != null) { meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName)); skull.setItemMeta(meta); }
        return skull;
    }

    private Material getSanctionMaterial(String type) {
        if (type == null) return Material.GRAY_DYE;
        switch (type.toLowerCase()) {
            case "ban": return Material.BARRIER;
            case "kick": return Material.LEATHER_BOOTS;
            case "mute": return Material.PAPER;
            case "warn": return Material.YELLOW_DYE;
            case "jail": return Material.IRON_BARS;
            default: return Material.GRAY_DYE;
        }
    }
    private ChatColor getSanctionColor(String type) {
        if (type == null) return ChatColor.GRAY;
        switch (type.toLowerCase()) {
            case "ban": return ChatColor.RED;
            case "kick": return ChatColor.GOLD;
            case "mute": return ChatColor.YELLOW;
            case "warn": return ChatColor.AQUA;
            case "jail": return ChatColor.DARK_GRAY;
            default: return ChatColor.GRAY;
        }
    }
    private String getTypeName(String type) {
        if (type == null) return "?";
        switch (type.toLowerCase()) {
            case "ban": return "BAN"; case "kick": return "KICK"; case "mute": return "MUTE";
            case "warn": return "WARN"; case "jail": return "JAIL"; case "unban": return "UNBAN";
            case "unmute": return "UNMUTE"; case "unjail": return "UNJAIL";
            default: return type.toUpperCase();
        }
    }
    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) + "..." : s; }
}
