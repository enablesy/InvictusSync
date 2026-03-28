package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class LookupListener implements Listener {

    private final InvictusSync plugin;
    private final Map<UUID, MenuData> menuData = new ConcurrentHashMap<>();
    private final Map<UUID, String> openMenus  = new ConcurrentHashMap<>();

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy HH:mm");

    private static final String TITLE_MAIN      = ChatColor.DARK_GRAY + "» " + ChatColor.GOLD   + "Lookup";
    private static final String TITLE_SANCTIONS = ChatColor.DARK_GRAY + "» " + ChatColor.RED    + "Sanciones";
    private static final String TITLE_ALTS      = ChatColor.DARK_GRAY + "» " + ChatColor.YELLOW + "Cuentas alternativas";
    private static final String TITLE_INFO      = ChatColor.DARK_GRAY + "» " + ChatColor.AQUA   + "Información";

    public LookupListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    public static class MenuData {
        public String targetName;
        public String targetUuid;
        public List<Map<String, String>> sanctions = new ArrayList<>();
        public List<Map<String, String>> alts      = new ArrayList<>();
        public String firstSeen;
        public String lastSeen;
    }

    // ── FETCH ─────────────────────────────────────────────────
    public void fetchAndOpen(Player viewer, String targetName) {
        try {
            String sanctionsResponse = plugin.getWorkerClient().getAndRead(
                "/mc/sanctions?search=" + java.net.URLEncoder.encode(targetName, "UTF-8") + "&page=1");
            String uuidResponse = plugin.getWorkerClient().getAndRead(
                "/mc/player/uuid?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8"));

            String targetUuid = null;
            if (uuidResponse != null && uuidResponse.contains("\"uuid\"")) {
                String ex = uuidResponse.replaceAll(".*\"uuid\"\\s*:\\s*\"([^\"]+)\".*", "$1");
                if (!ex.equals(uuidResponse)) targetUuid = ex;
            }

            String ipsResponse = targetUuid != null
                ? plugin.getWorkerClient().getAndRead("/auth/player/ips?uuid=" + targetUuid)
                : plugin.getWorkerClient().getAndRead(
                    "/auth/player/ips?nick=" + java.net.URLEncoder.encode(targetName, "UTF-8"));

            MenuData data = new MenuData();
            data.targetName = targetName;
            data.targetUuid = targetUuid;

            if (sanctionsResponse != null && sanctionsResponse.contains("\"sanctions\""))
                data.sanctions = parseSanctions(sanctionsResponse, targetName);
            if (ipsResponse != null && ipsResponse.contains("\"alts\""))
                data.alts = parseAlts(ipsResponse);
            if (ipsResponse != null && ipsResponse.contains("\"history\""))
                parseConnectionDates(ipsResponse, data);

            openMain(viewer, data);

        } catch (Exception e) {
            plugin.getLogger().warning("Error en /lookup: " + e.getMessage());
            plugin.getServer().getScheduler().runTask(plugin, () ->
                viewer.sendMessage(plugin.getMsg("error-contact")));
        }
    }

    // ── MENÚ PRINCIPAL (27 slots) ─────────────────────────────
    private void openMain(Player viewer, MenuData data) {
        menuData.put(viewer.getUniqueId(), data);
        openMenus.put(viewer.getUniqueId(), "main");

        Inventory inv = Bukkit.createInventory(null, 27, TITLE_MAIN);
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        border(inv, 27, Material.GRAY_STAINED_GLASS_PANE);

        // Cabeza centrada — slot 4
        ItemStack head = makeSkull(data.targetName);
        ItemMeta hm = head.getItemMeta();
        hm.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + data.targetName);
        hm.setLore(Arrays.asList(
            ChatColor.DARK_GRAY + "UUID: "             + ChatColor.GRAY + nvl(data.targetUuid, "Desconocido"),
            ChatColor.DARK_GRAY + "Primera conexión: " + ChatColor.GRAY + nvl(data.firstSeen,  "—"),
            ChatColor.DARK_GRAY + "Última conexión: "  + ChatColor.GRAY + nvl(data.lastSeen,   "—")
        ));
        head.setItemMeta(hm);
        inv.setItem(4, head);

        // Botón Sanciones — slot 11
        inv.setItem(11, makeItem(Material.BOOK,
            ChatColor.RED + "" + ChatColor.BOLD + "Sanciones",
            Arrays.asList(
                ChatColor.GRAY + "" + data.sanctions.size() + " registradas",
                "",
                ChatColor.YELLOW + "» Clic para ver"
            )));

        // Botón Alts — slot 13
        inv.setItem(13, makeItem(Material.COMPASS,
            ChatColor.YELLOW + "" + ChatColor.BOLD + "Cuentas alternativas",
            Arrays.asList(
                ChatColor.GRAY + "" + data.alts.size() + " detectadas",
                "",
                ChatColor.YELLOW + "» Clic para ver"
            )));

        // Botón Info — slot 15
        inv.setItem(15, makeItem(Material.PAPER,
            ChatColor.AQUA + "" + ChatColor.BOLD + "Información",
            Arrays.asList(
                ChatColor.GRAY + "UUID, IPs, fechas de conexión",
                "",
                ChatColor.YELLOW + "» Clic para ver"
            )));

        // Cerrar — slot 22
        inv.setItem(22, makeItem(Material.BARRIER, ChatColor.RED + "Cerrar"));

        open(viewer, inv);
    }

    // ── SUBMENÚ SANCIONES (54 slots) ──────────────────────────
    private void openSanctions(Player viewer, MenuData data) {
        openMenus.put(viewer.getUniqueId(), "sanctions");

        Inventory inv = Bukkit.createInventory(null, 54, TITLE_SANCTIONS);
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        border(inv, 54, Material.RED_STAINED_GLASS_PANE);

        inv.setItem(4, makeItem(Material.BOOK,
            ChatColor.RED + "" + ChatColor.BOLD + "Sanciones de " + data.targetName,
            Collections.singletonList(ChatColor.GRAY + "" + data.sanctions.size() + " registradas")));

        // Slots interiores: filas 2-5, columnas 1-7 (slots 10-16, 19-25, 28-34, 37-43)
        int[] innerSlots = buildInnerSlots(54);
        int idx = 0;
        for (Map<String, String> s : data.sanctions) {
            if (idx >= innerSlots.length) break;
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "Razón: "    + ChatColor.GRAY   + truncate(s.getOrDefault("reason", "—"), 40));
            lore.add(ChatColor.DARK_GRAY + "Staff: "    + ChatColor.YELLOW + s.getOrDefault("staff", "?"));
            lore.add(ChatColor.DARK_GRAY + "Fecha: "    + ChatColor.GRAY   + s.getOrDefault("date", "?"));
            String dur = s.get("duration");
            if (dur != null) lore.add(ChatColor.DARK_GRAY + "Duración: " + ChatColor.GRAY + dur);
            String type = s.getOrDefault("type", "warn");
            inv.setItem(innerSlots[idx], makeItem(getSanctionMaterial(type),
                getSanctionColor(type) + "" + ChatColor.BOLD + getTypeName(type)
                    + ChatColor.RESET + ChatColor.DARK_GRAY + " → " + ChatColor.WHITE + s.getOrDefault("target", "?"),
                lore));
            idx++;
        }
        if (data.sanctions.isEmpty()) {
            inv.setItem(22, makeItem(Material.LIME_DYE,
                ChatColor.GREEN + "Sin sanciones",
                Collections.singletonList(ChatColor.GRAY + "Este jugador no tiene historial.")));
        }

        inv.setItem(49, makeItem(Material.ARROW, ChatColor.GRAY + "← Volver"));
        open(viewer, inv);
    }

    // ── SUBMENÚ ALTS (54 slots) ───────────────────────────────
    private void openAlts(Player viewer, MenuData data) {
        openMenus.put(viewer.getUniqueId(), "alts");

        Inventory inv = Bukkit.createInventory(null, 54, TITLE_ALTS);
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        border(inv, 54, Material.YELLOW_STAINED_GLASS_PANE);

        inv.setItem(4, makeItem(Material.COMPASS,
            ChatColor.YELLOW + "" + ChatColor.BOLD + "Alts de " + data.targetName,
            Collections.singletonList(ChatColor.GRAY + "" + data.alts.size() + " detectadas")));

        int[] innerSlots = buildInnerSlots(54);
        int idx = 0;
        for (Map<String, String> alt : data.alts) {
            if (idx >= innerSlots.length) break;
            String altNick = alt.getOrDefault("nick", "?");
            ItemStack altHead = makeSkull(altNick);
            ItemMeta m = altHead.getItemMeta();
            m.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + altNick);
            m.setLore(Arrays.asList(
                ChatColor.DARK_GRAY + "IP compartida: " + ChatColor.GRAY + alt.getOrDefault("sharedIp", "?"),
                "",
                ChatColor.YELLOW + "» Clic para ver su lookup"
            ));
            altHead.setItemMeta(m);
            inv.setItem(innerSlots[idx], altHead);
            idx++;
        }
        if (data.alts.isEmpty()) {
            inv.setItem(22, makeItem(Material.LIME_DYE,
                ChatColor.GREEN + "Sin alts detectados",
                Collections.singletonList(ChatColor.GRAY + "No se encontraron IPs compartidas.")));
        }

        inv.setItem(49, makeItem(Material.ARROW, ChatColor.GRAY + "← Volver"));
        open(viewer, inv);
    }

    // ── SUBMENÚ INFO (27 slots) ───────────────────────────────
    private void openInfo(Player viewer, MenuData data) {
        openMenus.put(viewer.getUniqueId(), "info");

        Inventory inv = Bukkit.createInventory(null, 27, TITLE_INFO);
        fill(inv, Material.BLACK_STAINED_GLASS_PANE);
        border(inv, 27, Material.CYAN_STAINED_GLASS_PANE);

        ItemStack head = makeSkull(data.targetName);
        ItemMeta hm = head.getItemMeta();
        hm.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + data.targetName);
        hm.setLore(Arrays.asList(
            ChatColor.DARK_GRAY + "UUID: "             + ChatColor.GRAY + nvl(data.targetUuid, "Desconocido"),
            ChatColor.DARK_GRAY + "Primera conexión: " + ChatColor.GRAY + nvl(data.firstSeen,  "—"),
            ChatColor.DARK_GRAY + "Última conexión: "  + ChatColor.GRAY + nvl(data.lastSeen,   "—")
        ));
        head.setItemMeta(hm);
        inv.setItem(13, head);

        inv.setItem(22, makeItem(Material.ARROW, ChatColor.GRAY + "← Volver"));
        open(viewer, inv);
    }

    // ── CLICK ─────────────────────────────────────────────────
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player viewer = (Player) event.getWhoClicked();
        UUID uuid = viewer.getUniqueId();
        if (!openMenus.containsKey(uuid)) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        // Ignorar fondos y bordes
        switch (clicked.getType()) {
            case BLACK_STAINED_GLASS_PANE:
            case GRAY_STAINED_GLASS_PANE:
            case RED_STAINED_GLASS_PANE:
            case YELLOW_STAINED_GLASS_PANE:
            case CYAN_STAINED_GLASS_PANE:
                return;
        }

        MenuData data   = menuData.get(uuid);
        String   screen = openMenus.get(uuid);

        if (clicked.getType() == Material.BARRIER) { viewer.closeInventory(); return; }

        if (clicked.getType() == Material.ARROW) {
            openMain(viewer, data);
            return;
        }

        if ("main".equals(screen)) {
            int slot = event.getSlot();
            if (slot == 11) openSanctions(viewer, data);
            else if (slot == 13) openAlts(viewer, data);
            else if (slot == 15) openInfo(viewer, data);
            return;
        }

        if ("alts".equals(screen) && clicked.getType() == Material.PLAYER_HEAD) {
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
        UUID uuid = event.getPlayer().getUniqueId();
        openMenus.remove(uuid);
        menuData.remove(uuid);
    }

    // ── UTILIDADES ────────────────────────────────────────────
    private void open(Player viewer, Inventory inv) {
        plugin.getServer().getScheduler().runTask(plugin, () -> viewer.openInventory(inv));
    }

    private void fill(Inventory inv, Material mat) {
        ItemStack bg = makeItem(mat, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, bg);
    }

    private void border(Inventory inv, int size, Material mat) {
        ItemStack b = makeItem(mat, " ");
        int rows = size / 9;
        for (int i = 0; i < 9; i++) inv.setItem(i, b);
        for (int i = size - 9; i < size; i++) inv.setItem(i, b);
        for (int r = 1; r < rows - 1; r++) {
            inv.setItem(r * 9, b);
            inv.setItem(r * 9 + 8, b);
        }
    }

    /** Devuelve los slots interiores (sin bordes) de un inventario de [size] slots */
    private int[] buildInnerSlots(int size) {
        List<Integer> slots = new ArrayList<>();
        int rows = size / 9;
        for (int r = 1; r < rows - 1; r++) {
            for (int c = 1; c <= 7; c++) {
                int s = r * 9 + c;
                // Reservar slot 4 (header) y slot size-5 (volver)
                if (s == 4 || s == size - 5) continue;
                slots.add(s);
            }
        }
        return slots.stream().mapToInt(i -> i).toArray();
    }

    private String nvl(String s, String fallback) { return s != null ? s : fallback; }
    private String truncate(String s, int max) { return s != null && s.length() > max ? s.substring(0, max) + "..." : s; }

    // ── PARSERS ───────────────────────────────────────────────
    private List<Map<String, String>> parseSanctions(String json, String targetName) {
        List<Map<String, String>> result = new ArrayList<>();
        try {
            int start = json.indexOf("\"sanctions\":[");
            if (start == -1) return result;
            start = json.indexOf("[", start) + 1;
            for (String obj : splitJsonObjects(extractUntilClose(json, start, '[', ']'))) {
                if (result.size() >= 15) break;
                String target = extractJsonString(obj, "target");
                if (target == null || !target.equalsIgnoreCase(targetName)) continue;
                Map<String, String> s = new HashMap<>();
                s.put("type",     extractJsonString(obj, "type"));
                s.put("target",   target);
                s.put("staff",    extractJsonString(obj, "staff"));
                s.put("reason",   extractJsonString(obj, "reason"));
                s.put("duration", extractJsonString(obj, "duration"));
                String ts = extractJsonString(obj, "timestamp");
                if (ts != null) try { s.put("date", DATE_FMT.format(new Date(Long.parseLong(ts)))); } catch (Exception ignored) {}
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
                alt.put("nick",     extractJsonString(obj, "nick"));
                alt.put("uuid",     extractJsonString(obj, "uuid"));
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
                if (ts != null) try { data.lastSeen  = DATE_FMT.format(new Date(Long.parseLong(ts))); } catch (Exception ignored) {}
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
            case "ban":  return Material.BARRIER;
            case "kick": return Material.LEATHER_BOOTS;
            case "mute": return Material.PAPER;
            case "warn": return Material.YELLOW_DYE;
            case "jail": return Material.IRON_BARS;
            default:     return Material.GRAY_DYE;
        }
    }
    private ChatColor getSanctionColor(String type) {
        if (type == null) return ChatColor.GRAY;
        switch (type.toLowerCase()) {
            case "ban":  return ChatColor.RED;
            case "kick": return ChatColor.GOLD;
            case "mute": return ChatColor.YELLOW;
            case "warn": return ChatColor.AQUA;
            case "jail": return ChatColor.DARK_GRAY;
            default:     return ChatColor.GRAY;
        }
    }
    private String getTypeName(String type) {
        if (type == null) return "?";
        switch (type.toLowerCase()) {
            case "ban":    return "BAN";    case "kick":   return "KICK";
            case "mute":   return "MUTE";   case "warn":   return "WARN";
            case "jail":   return "JAIL";   case "unban":  return "UNBAN";
            case "unmute": return "UNMUTE"; case "unjail": return "UNJAIL";
            default:       return type.toUpperCase();
        }
    }
}
