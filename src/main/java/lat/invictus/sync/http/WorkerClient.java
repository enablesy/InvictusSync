package lat.invictus.sync.http;

import lat.invictus.sync.InvictusSync;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorkerClient {

    private final InvictusSync plugin;
    private String workerUrl;
    private String mcToken;
    private final ExecutorService executor;

    public WorkerClient(InvictusSync plugin) {
        this.plugin = plugin;
        this.workerUrl = plugin.getConfig().getString("worker-url", "");
        this.mcToken = plugin.getConfig().getString("mc-token", "");
        this.executor = Executors.newFixedThreadPool(4);
    }

    public void post(String endpoint, String json) {
        executor.submit(() -> {
            try {
                URL url = new URL(workerUrl + endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-MC-Token", mcToken);
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    plugin.getLogger().warning("Worker respondió " + code + " en " + endpoint);
                }
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().warning("Error al contactar Worker (" + endpoint + "): " + e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdown();
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
