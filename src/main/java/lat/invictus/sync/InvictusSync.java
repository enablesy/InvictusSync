package lat.invictus.sync;

import lat.invictus.sync.listeners.RyzenStaffListener;
import lat.invictus.sync.tasks.StatusTask;
import lat.invictus.sync.http.WorkerClient;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public class InvictusSync extends JavaPlugin {

    private static InvictusSync instance;
    private WorkerClient workerClient;
    private StatusTask statusTask;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        workerClient = new WorkerClient(this);

        // Register RyzenStaff event listener
        getServer().getPluginManager().registerEvents(new RyzenStaffListener(this), this);

        // Start status task
        if (getConfig().getBoolean("sync.status", true)) {
            int interval = getConfig().getInt("status-interval", 30) * 20; // ticks
            statusTask = new StatusTask(this);
            statusTask.runTaskTimerAsynchronously(this, 100L, interval);
        }

        getLogger().info("InvictusSync habilitado. Conectado a: " + getConfig().getString("worker-url"));
    }

    @Override
    public void onDisable() {
        if (statusTask != null) statusTask.cancel();
        getLogger().info("InvictusSync deshabilitado.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("invictussync")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("invictussync.admin")) {
                    sender.sendMessage("§cNo tenés permiso para hacer esto.");
                    return true;
                }
                reloadConfig();
                workerClient = new WorkerClient(this);
                sender.sendMessage("§aInvictusSync recargado.");
                return true;
            }
            sender.sendMessage("§eUso: /invictussync reload");
            return true;
        }
        return false;
    }

    public static InvictusSync getInstance() { return instance; }
    public WorkerClient getWorkerClient() { return workerClient; }
}
