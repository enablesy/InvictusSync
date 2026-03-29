package lat.invictus.sync.listeners;

import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.InvictusSync;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TicketListener implements Listener {

    private final InvictusSync plugin;

    private final Map<UUID, String> awaitingInput      = new ConcurrentHashMap<>();
    private final Map<UUID, String> awaitingReportedNick = new ConcurrentHashMap<>();
    private final Set<UUID>         openMenus           = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long>   cooldowns           = new ConcurrentHashMap<>();

    private static final long COOLDOWN_MS = 60000;

    // Categorías por defecto — se sobreescriben con lo del config si existe
    private static final Object[][] DEFAULT_CATEGORIES = {
        { Material.REDSTONE,      "Reporte de jugador", "Reporta a un jugador por romper\nlas reglas del servidor.",           "RED",    "reporte"    },
        { Material.WRITABLE_BOOK, "Bug / Error",        "Informa de un fallo técnico\nque encontraste en el servidor.",        "AQUA",   "bug"        },
        { Material.BOOK,          "Queja",              "Queja sobre el comportamiento\ndel servidor o el staff.",             "YELLOW", "queja"      },
        { Material.COMPASS,       "Sugerencia",         "Propón una mejora o idea\npara el servidor.",                        "GREEN",  "sugerencia" },
        { Material.PAPER,         "Otro",               "Cualquier consulta que no\nencaje en las categorías anteriores.",    "GRAY",   "otro"       },
    };
    private static final int[] CAT_SLOTS = { 10, 11, 12, 13, 14 };

    private String menuTitle() {
        return InvictusSync.translateColors(plugin.getConfig().getString("menus.ticket.title", "» Abrir Ticket"));
    }
    private String menuCancel() {
        return InvictusSync.translateColors(plugin.getConfig().getString("menus.ticket.btn-cancel", "§cCancelar"));
    }
    private String catName(int i) {
        String key = "menus.ticket.categories." + (String) DEFAULT_CATEGORIES[i][4] + ".name";
        return InvictusSync.translateColors(plugin.getConfig().getString(key, (String) DEFAULT_CATEGORIES[i][1]));
    }
    private String catDesc(int i) {
        return plugin.getConfig().getString("menus.ticket.categories." + (String) DEFAULT_CATEGORIES[i][4] + ".desc",
            (String) DEFAULT_CATEGORIES[i][2]);
    }
    private String catColor(int i) {
        return plugin.getConfig().getString("menus.ticket.categories." + (String) DEFAULT_CATEGORIES[i][4] + ".color",
            (String) DEFAULT_CATEGORIES[i][3]);
    }
    private ChatColor parseChatColor(String name) {
        try { return ChatColor.valueOf(name.toUpperCase()); } catch (Exception e) { return ChatColor.GRAY; }
    }

    public TicketListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    // ── /ticket ───────────────────────────────────────────────
    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!event.getMessage().equalsIgnoreCase("/ticket")) return;
        event.setCancelled(true);
        Player player = event.getPlayer();

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) {
            player.sendMessage(plugin.getMsg("ticket-cooldown"));
            return;
        }
        openTicketMenu(player);
    }

    // ── MENÚ (27 slots) ───────────────────────────────────────
    private void openTicketMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, menuTitle());

        // Fondo negro
        ItemStack bg = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, bg);

        // Borde gris oscuro — fila 0, fila 2 (inferior), columnas 0 y 8
        ItemStack border = makeItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++)  inv.setItem(i, border);      // fila 0
        for (int i = 18; i < 27; i++) inv.setItem(i, border);     // fila 2
        inv.setItem(9,  border);                                    // col 0 fila 1
        inv.setItem(17, border);                                    // col 8 fila 1

        // Categorías en slots 10-14 (fila del medio, interior)
        for (int i = 0; i < DEFAULT_CATEGORIES.length; i++) {
            String name  = catName(i);
            String desc  = catDesc(i);
            ChatColor color = parseChatColor(catColor(i));
            Material mat = (Material) DEFAULT_CATEGORIES[i][0];

            List<String> lore = new ArrayList<>();
            for (String line : desc.split("\n"))
                lore.add(ChatColor.GRAY + line);
            lore.add("");
            lore.add(ChatColor.YELLOW + "» Clic para seleccionar");

            inv.setItem(CAT_SLOTS[i], makeItem(mat, color + "" + ChatColor.BOLD + name, lore));
        }

        // Botón cancelar — slot 22 (centro fila inferior)
        inv.setItem(22, makeItem(Material.BARRIER, menuCancel(),
            Collections.singletonList(ChatColor.GRAY + "Cerrar sin abrir ticket")));

        openMenus.add(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> player.openInventory(inv));
    }

    // ── CLIC EN MENÚ ─────────────────────────────────────────
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (!openMenus.contains(player.getUniqueId())) return;
        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.BLACK_STAINED_GLASS_PANE
                || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) return;

        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        int slot = event.getSlot();
        for (int i = 0; i < CAT_SLOTS.length; i++) {
            if (slot != CAT_SLOTS[i]) continue;
            String catId = (String) DEFAULT_CATEGORIES[i][4];
            player.closeInventory();

            if (catId.equals("reporte")) {
                awaitingReportedNick.put(player.getUniqueId(), "");
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY
                    + "Escribe el nick del jugador a reportar:");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe "
                    + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
            } else {
                awaitingInput.put(player.getUniqueId(), catId);
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY
                    + "Describe tu " + ChatColor.YELLOW + (String) DEFAULT_CATEGORIES[i][1]
                    + ChatColor.GRAY + ":");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe "
                    + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
            }
            return;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (openMenus.contains(event.getWhoClicked().getUniqueId()))
            event.setCancelled(true);
    }

    // ── INPUT POR CHAT ────────────────────────────────────────
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID   uuid   = player.getUniqueId();
        String msg    = event.getMessage().trim();

        // Esperando nick del reportado
        if (awaitingReportedNick.containsKey(uuid)) {
            event.setCancelled(true);
            awaitingReportedNick.remove(uuid);
            if (msg.equalsIgnoreCase("cancelar")) {
                player.sendMessage(ChatColor.GRAY + "Ticket cancelado.");
                return;
            }
            awaitingInput.put(uuid, "reporte:" + msg);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY
                    + "Ahora describe el motivo contra " + ChatColor.YELLOW + msg + ChatColor.GRAY + ":");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe "
                    + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
            });
            return;
        }

        // Esperando descripción
        if (awaitingInput.containsKey(uuid)) {
            event.setCancelled(true);
            String catId = awaitingInput.remove(uuid);
            if (msg.equalsIgnoreCase("cancelar")) {
                player.sendMessage(ChatColor.GRAY + "Ticket cancelado.");
                return;
            }

            cooldowns.put(uuid, System.currentTimeMillis());

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String catLabel, reason;
                    if (catId.startsWith("reporte:")) {
                        String reportedNick = catId.substring("reporte:".length());
                        catLabel = "Reporte de jugador";
                        reason   = "[TICKET:" + catLabel + "] Jugador reportado: " + reportedNick + " — " + msg;
                    } else {
                        catLabel = getCatLabel(catId);
                        reason   = "[TICKET:" + catLabel + "] " + msg;
                    }

                    String json = String.format(
                        "{\"reporter\":\"%s\",\"reporterUuid\":\"%s\",\"reported\":\"SERVIDOR\",\"reason\":\"%s\"}",
                        WorkerClient.esc(player.getName()), uuid.toString(), WorkerClient.esc(reason)
                    );
                    plugin.getWorkerClient().post("/mc/report", json);

                    final String finalCatId  = catId;
                    final String finalMsg    = msg;
                    final String finalLabel  = catLabel;
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage(plugin.getMsg("ticket-sent"));
                        plugin.getServer().getOnlinePlayers().stream()
                            .filter(p -> p.hasPermission("invictussync.link") && !p.equals(player))
                            .forEach(p -> p.sendMessage(
                                ChatColor.BLUE + "" + ChatColor.BOLD + "[TICKET] " + ChatColor.RESET
                                + ChatColor.GRAY + player.getName() + " — "
                                + ChatColor.YELLOW + finalLabel
                                + ChatColor.GRAY + ": " + finalMsg
                            ));
                    });
                } catch (Exception e) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(plugin.getMsg("ticket-error")));
                }
            });
        }
    }

    // ── UTILS ─────────────────────────────────────────────────
    private String getCatLabel(String catId) {
        for (Object[] cat : DEFAULT_CATEGORIES)
            if (cat[4].equals(catId)) return (String) cat[1];
        return catId;
    }

    private ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta  meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    private ItemStack makeItem(Material material, String name) { return makeItem(material, name, null); }
}
