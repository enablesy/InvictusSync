package lat.invictus.sync.menu;

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

public class HistorialMenu implements Listener {

    private final InvictusSync plugin;
    // UUID del viewer → inventario abierto (para identificar clics)
    private final Map<UUID, String> openMenus = new HashMap<>();
    // UUID del viewer → datos cargados
    private final Map<UUID, MenuData> menuData = new HashMap<>();

    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("dd/MM/yy HH:mm");

    public HistorialMenu(InvictusSync plugin) {
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

    public void openFor(Player viewer, MenuData data) {
        openMenus.put(viewer.getUniqueId(), data.targetName);
        menuData.put(viewer.getUniqueId(), data);
        viewer.getServer().getScheduler().runTask(plugin, () -> {
            Inventory inv = buildMainMenu(viewer, data);
            viewer.openInventory(inv);
        });
    }

    private Inventory buildMainMenu(Player viewer, MenuData data) {
        String title = ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + data.targetName + ChatColor.DARK_GRAY + " — Historial";
        Inventory inv = Bukkit.createInventory(null, 54, title);

        // ── RELLENO DE FONDO ──
        ItemStack gray = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        ItemStack darkGray = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, darkGray);
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,45,46,47,48,49,50,51,52,53}) inv.setItem(i, gray);

        // ── CABEZA DEL JUGADOR (centro superior) ──
        ItemStack head = makeSkull(data.targetName);
        ItemMeta headMeta = head.getItemMeta();
        headMeta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + data.targetName);
        List<String> headLore = new ArrayList<>();
        headLore.add(ChatColor.DARK_GRAY + "UUID: " + ChatColor.GRAY + (data.targetUuid != null ? data.targetUuid : "Desconocido"));
        headLore.add(ChatColor.DARK_GRAY + "Primera conexión: " + ChatColor.GRAY + (data.firstSeen != null ? data.firstSeen : "—"));
        headLore.add(ChatColor.DARK_GRAY + "Última conexión: " + ChatColor.GRAY + (data.lastSeen != null ? data.lastSeen : "—"));
        headMeta.setLore(headLore);
        head.setItemMeta(headMeta);
        inv.setItem(4, head);

        // ── SANCIONES (slots 9-22) ──
        ItemStack sanctionHeader = makeItem(Material.BOOK,
            ChatColor.RED + "" + ChatColor.BOLD + "Sanciones recientes",
            ChatColor.GRAY + "" + data.sanctions.size() + " registradas");
        inv.setItem(9, sanctionHeader);

        int slot = 10;
        for (Map<String, String> s : data.sanctions) {
            if (slot > 22) break;
            Material mat = getSanctionMaterial(s.getOrDefault("type", "warn"));
            String typeName = getTypeName(s.getOrDefault("type", "warn"));
            String target = s.getOrDefault("target", "?");
            String reason = s.getOrDefault("reason", "Sin razón");
            String staffName = s.getOrDefault("staff", "?");
            String date = s.getOrDefault("date", "?");
            String duration = s.getOrDefault("duration", null);

            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "Razón: " + ChatColor.GRAY + truncate(reason, 40));
            lore.add(ChatColor.DARK_GRAY + "Staff: " + ChatColor.YELLOW + staffName);
            lore.add(ChatColor.DARK_GRAY + "Fecha: " + ChatColor.GRAY + date);
            if (duration != null) lore.add(ChatColor.DARK_GRAY + "Duración: " + ChatColor.GRAY + duration);

            ItemStack sanctionItem = makeItem(mat,
                getSanctionColor(s.getOrDefault("type", "warn")) + typeName + ChatColor.DARK_GRAY + " → " + ChatColor.WHITE + target,
                lore.toArray(new String[0])
            );
            inv.setItem(slot, sanctionItem);
            slot++;
        }

        if (data.sanctions.isEmpty()) {
            inv.setItem(10, makeItem(Material.LIME_DYE,
                ChatColor.GREEN + "Sin sanciones registradas",
                ChatColor.GRAY + "Este jugador no tiene historial."));
        }

        // ── ALTS (slots 23-35) ──
        ItemStack altsHeader = makeItem(Material.COMPASS,
            ChatColor.YELLOW + "" + ChatColor.BOLD + "Posibles cuentas alternativas",
            ChatColor.GRAY + "" + data.alts.size() + " detectadas");
        inv.setItem(23, altsHeader);

        int altSlot = 24;
        for (Map<String, String> alt : data.alts) {
            if (altSlot > 35) break;
            String altNick = alt.getOrDefault("nick", "?");
            String sharedIp = alt.getOrDefault("sharedIp", "?");

            ItemStack altHead = makeSkull(altNick);
            ItemMeta altMeta = altHead.getItemMeta();
            altMeta.setDisplayName(ChatColor.YELLOW + altNick);
            altMeta.setLore(Arrays.asList(
                ChatColor.DARK_GRAY + "IP compartida: " + ChatColor.GRAY + sharedIp,
                ChatColor.DARK_GRAY + "Haz clic para ver su historial"
            ));
            altHead.setItemMeta(altMeta);
            inv.setItem(altSlot, altHead);
            altSlot++;
        }

        if (data.alts.isEmpty()) {
            inv.setItem(24, makeItem(Material.LIME_DYE,
                ChatColor.GREEN + "Sin alts detectados",
                ChatColor.GRAY + "No se encontraron IPs compartidas."));
        }

        // ── BOTÓN CERRAR ──
        inv.setItem(49, makeItem(Material.BARRIER, ChatColor.RED + "Cerrar"));

        // ── SEPARADORES ──
        ItemStack separator = makeItem(Material.WHITE_STAINED_GLASS_PANE, " ");
        inv.setItem(22, separator);
        inv.setItem(35, separator);

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

        // Cerrar
        if (clicked.getType() == Material.BARRIER) {
            viewer.closeInventory();
            return;
        }

        // Clic en un alt — abrir su historial
        MenuData data = menuData.get(viewer.getUniqueId());
        if (data == null) return;

        int slot = event.getSlot();
        if (slot >= 24 && slot <= 35 && clicked.getType() == Material.PLAYER_HEAD) {
            ItemMeta meta = clicked.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String altName = ChatColor.stripColor(meta.getDisplayName());
                viewer.closeInventory();
                viewer.sendMessage(plugin.getMsg("oracle-loading").replace("{player}", altName));
                // Cargar historial del alt
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () ->
                    plugin.getHistorialCommand().fetchAndOpen(viewer, altName)
                );
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player viewer = (Player) event.getPlayer();
        openMenus.remove(viewer.getUniqueId());
        menuData.remove(viewer.getUniqueId());
    }

    // ── HELPERS ──────────────────────────────────────────────

    private ItemStack makeItem(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore.length > 0) meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeItem(Material material, String name, List<String> lore) {
        return makeItem(material, name, lore.toArray(new String[0]));
    }

    private ItemStack makeSkull(String playerName) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private Material getSanctionMaterial(String type) {
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
        switch (type.toLowerCase()) {
            case "ban": return "BAN";
            case "kick": return "KICK";
            case "mute": return "MUTE";
            case "warn": return "WARN";
            case "jail": return "JAIL";
            case "unban": return "UNBAN";
            case "unmute": return "UNMUTE";
            case "unjail": return "UNJAIL";
            default: return type.toUpperCase();
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
