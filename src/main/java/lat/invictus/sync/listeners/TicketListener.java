package lat.invictus.sync.listeners;

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

    // UUID → categoría seleccionada (esperando descripción por chat)
    private final Map<UUID, String> awaitingInput = new ConcurrentHashMap<>();
    // UUID → nick del reportado (solo para categoría REPORTE)
    private final Map<UUID, String> awaitingReportedNick = new ConcurrentHashMap<>();
    // UUID → inventario abierto
    private final Set<UUID> openMenus = ConcurrentHashMap.newKeySet();
    // Cooldowns
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60000;

    private static final String MENU_TITLE = ChatColor.DARK_GRAY + "» " + ChatColor.GOLD + "Abrir Ticket";

    // Categorías: Material, nombre, descripción, color
    private static final Object[][] CATEGORIES = {
        { Material.REDSTONE, "Reporte de jugador",   "Reporta a un jugador por hacer\nalgo contra las reglas.",        ChatColor.RED,        "reporte"    },
        { Material.WRITABLE_BOOK, "Bug / Error",     "Informa de un fallo o error\nque encontraste en el servidor.",   ChatColor.AQUA,       "bug"        },
        { Material.BOOK, "Queja",                    "Queja sobre el comportamiento\ndel servidor o el staff.",         ChatColor.YELLOW,     "queja"      },
        { Material.COMPASS, "Sugerencia",            "Propón una mejora o idea\npara el servidor.",                     ChatColor.GREEN,      "sugerencia" },
        { Material.PAPER, "Otro",                    "Cualquier otra consulta\nque no encaje en las anteriores.",       ChatColor.GRAY,       "otro"       },
    };

    public TicketListener(InvictusSync plugin) {
        this.plugin = plugin;
    }

    // ── /ticket ──────────────────────────────────────────────
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

    // ── MENÚ PRINCIPAL ────────────────────────────────────────
    private void openTicketMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_TITLE);

        // Fondo
        ItemStack bg = makeItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, bg);

        // Categorías en slots 10, 11, 12, 13, 14
        int[] slots = {10, 11, 12, 13, 14};
        for (int i = 0; i < CATEGORIES.length; i++) {
            Object[] cat = CATEGORIES[i];
            Material mat = (Material) cat[0];
            String name = (String) cat[1];
            String desc = (String) cat[2];
            ChatColor color = (ChatColor) cat[3];

            List<String> lore = new ArrayList<>();
            for (String line : desc.split("\n"))
                lore.add(ChatColor.GRAY + line);
            lore.add("");
            lore.add(ChatColor.YELLOW + "» Click para seleccionar");

            inv.setItem(slots[i], makeItem(mat, color + "" + ChatColor.BOLD + name, lore));
        }

        // Botón cerrar
        inv.setItem(22, makeItem(Material.BARRIER, ChatColor.RED + "Cancelar"));

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
                || clicked.getType() == Material.BLACK_STAINED_GLASS_PANE) return;

        if (clicked.getType() == Material.BARRIER) {
            player.closeInventory();
            return;
        }

        // Identificar categoría por slot
        int slot = event.getSlot();
        int[] slots = {10, 11, 12, 13, 14};
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                String catId = (String) CATEGORIES[i][4];
                player.closeInventory();

                if (catId.equals("reporte")) {
                    // Primero pedir el nick del jugador a reportar
                    awaitingReportedNick.put(player.getUniqueId(), "");
                    player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY + "Escribe el nick del jugador que quieres reportar en el chat:");
                    player.sendMessage(ChatColor.DARK_GRAY + "(Escribe " + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
                } else {
                    awaitingInput.put(player.getUniqueId(), catId);
                    player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY + "Describe tu " + ChatColor.YELLOW + (String) CATEGORIES[i][1] + ChatColor.GRAY + " en el chat:");
                    player.sendMessage(ChatColor.DARK_GRAY + "(Escribe " + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
                }
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        openMenus.remove(event.getPlayer().getUniqueId());
    }

    // ── INPUT POR CHAT ────────────────────────────────────────
    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        String msg = event.getMessage().trim();

        // Esperando nick del reportado
        if (awaitingReportedNick.containsKey(uuid)) {
            event.setCancelled(true);
            awaitingReportedNick.remove(uuid);

            if (msg.equalsIgnoreCase("cancelar")) {
                player.sendMessage(ChatColor.GRAY + "Ticket cancelado.");
                return;
            }

            String reportedNick = msg;
            awaitingInput.put(uuid, "reporte:" + reportedNick);
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GOLD + "⚔ " + ChatColor.GRAY + "Ahora describe el motivo del reporte contra "
                    + ChatColor.YELLOW + reportedNick + ChatColor.GRAY + ":");
                player.sendMessage(ChatColor.DARK_GRAY + "(Escribe " + ChatColor.RED + "cancelar" + ChatColor.DARK_GRAY + " para cancelar)");
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

            String finalCatId = catId;
            String finalMsg = msg;

            // Aplicar cooldown
            cooldowns.put(uuid, System.currentTimeMillis());

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String catLabel;
                    String reason;

                    if (finalCatId.startsWith("reporte:")) {
                        String reportedNick = finalCatId.substring("reporte:".length());
                        catLabel = "Reporte de jugador";
                        reason = "[TICKET:" + catLabel + "] Jugador reportado: " + reportedNick + " — " + finalMsg;
                    } else {
                        catLabel = getCatLabel(finalCatId);
                        reason = "[TICKET:" + catLabel + "] " + finalMsg;
                    }

                    String json = String.format(
                        "{\"reporter\":\"%s\",\"reporterUuid\":\"%s\",\"reported\":\"SERVIDOR\",\"reason\":\"%s\"}",
                        WorkerClient.esc(player.getName()),
                        uuid.toString(),
                        WorkerClient.esc(reason)
                    );
                    plugin.getWorkerClient().post("/mc/report", json);

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        player.sendMessage(plugin.getMsg("ticket-sent"));
                        // Notificar al staff online
                        plugin.getServer().getOnlinePlayers().stream()
                            .filter(p -> p.hasPermission("invictussync.link") && !p.equals(player))
                            .forEach(p -> p.sendMessage(
                                ChatColor.BLUE + "" + ChatColor.BOLD + "[TICKET] " + ChatColor.RESET
                                + ChatColor.GRAY + player.getName() + " — "
                                + ChatColor.YELLOW + getCatLabel(finalCatId.startsWith("reporte:") ? "reporte" : finalCatId)
                                + ChatColor.GRAY + ": " + finalMsg
                            ));
                    });
                } catch (Exception e) {
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.sendMessage(plugin.getMsg("ticket-error"))
                    );
                }
            });
        }
    }

    // ── UTILS ─────────────────────────────────────────────────
    private String getCatLabel(String catId) {
        for (Object[] cat : CATEGORIES) {
            if (cat[4].equals(catId)) return (String) cat[1];
        }
        return catId;
    }

    private ItemStack makeItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack makeItem(Material material, String name) {
        return makeItem(material, name, null);
    }
}
