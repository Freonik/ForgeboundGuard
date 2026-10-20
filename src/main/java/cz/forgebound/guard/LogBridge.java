package cz.forgebound.guard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sends the server's logs to a private GitHub repository, so someone who isn't on the server (e.g. Claude, when the
 * owner asks) can read what happened. Every few minutes the new lines of logs/latest.log are added to
 * console/YYYY-MM-DD.log, and moderation.log is copied as it is. Player IP addresses are hidden.
 * Free: it uses the GitHub API with the owner's token, nothing else.
 */
public final class LogBridge {

    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?\\b");

    private final ForgeboundGuard plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final File state;
    private BukkitTask task;
    private volatile boolean busy;
    private volatile String lastResult = "not run yet";
    /** how far latest.log was already sent */
    private long position;
    private LocalDate lastCleanup;
    /** remote path -> blob sha (needed to overwrite a file on GitHub) */
    private final Map<String, String> shas = new HashMap<>();

    public LogBridge(ForgeboundGuard plugin) {
        this.plugin = plugin;
        this.state = new File(plugin.getDataFolder(), "log-bridge");
    }

    private String cfg(String key, String def) {
        return plugin.getConfig().getString("log-bridge." + key, def);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("log-bridge.enabled", false)
                && !cfg("token", "").isBlank() && cfg("repo", "").contains("/");
    }

    public String lastResult() {
        return lastResult;
    }

    public void start() {
        stop();
        if (!enabled()) {
            return;
        }
        long every = Math.max(1, plugin.getConfig().getInt("log-bridge.interval-minutes", 5)) * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> sync(null), 20L * 30, every);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** /guard logs now */
    public void now(CommandSender sender) {
        if (!enabled()) {
            sender.sendMessage(ForgeboundGuard.color("&7The log bridge is off. Set &flog-bridge.enabled&7, &ftoken &7and &frepo&7."));
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sync(sender));
    }

    /** Runs off the main thread. */
    private synchronized void sync(CommandSender sender) {
        if (busy || !enabled()) {
            return;
        }
        busy = true;
        try {
            int sent = 0;
            cleanup();
            if (plugin.getConfig().getBoolean("log-bridge.console", true)) {
                sent += console();
            }
            if (plugin.getConfig().getBoolean("log-bridge.moderation", true)) {
                File mod = new File(plugin.getDataFolder(), "moderation.log");
                if (mod.isFile()) {
                    sent += upload("moderation.log", hide(Files.readString(mod.toPath(), StandardCharsets.UTF_8)), true);
                }
            }
            lastResult = "ok at " + java.time.LocalTime.now().withNano(0) + (sent == 0 ? " (nothing new)" : "");
        } catch (Exception ex) {
            lastResult = "failed: " + ex.getMessage();
            plugin.getLogger().warning("Log bridge: " + ex.getMessage());
        } finally {
            busy = false;
        }
        if (sender != null) {
            String msg = "&7Log bridge: &f" + lastResult;
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ForgeboundGuard.color(msg)));
        }
    }

    /** New lines of logs/latest.log -> appended to the local copy of today's file -> uploaded. */
    private int console() throws IOException, InterruptedException {
        File latest = new File("logs", "latest.log");
        if (!latest.isFile()) {
            return 0;
        }
        String fresh;
        try (RandomAccessFile raf = new RandomAccessFile(latest, "r")) {
            long length = raf.length();
            if (length < position) {
                position = 0;                               // a new latest.log (restart / new day)
            }
            if (length == position) {
                return 0;
            }
            long max = 4L * 1024 * 1024;                    // at most 4 MB per round
            long from = Math.max(position, length - max);
            byte[] bytes = new byte[(int) (length - from)];
            raf.seek(from);
            raf.readFully(bytes);
            position = length;
            fresh = new String(bytes, StandardCharsets.UTF_8);
        }
        String name = "console/" + LocalDate.now() + ".log";
        File local = new File(state, name);
        Files.createDirectories(local.toPath().getParent());
        Files.writeString(local.toPath(), hide(fresh), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        return upload(name, Files.readString(local.toPath(), StandardCharsets.UTF_8), false);
    }

    /** Once a day: old daily files go away - here after log-bridge.keep-days-server, on GitHub after keep-days-github. */
    private void cleanup() throws IOException, InterruptedException {
        LocalDate today = LocalDate.now();
        if (today.equals(lastCleanup)) {
            return;
        }
        lastCleanup = today;
        int keepHere = Math.max(1, plugin.getConfig().getInt("log-bridge.keep-days-server", 7));
        File[] local = new File(state, "console").listFiles();
        if (local != null) {
            for (File f : local) {
                LocalDate day = day(f.getName());
                if (day != null && day.isBefore(today.minusDays(keepHere - 1L))) {
                    Files.deleteIfExists(f.toPath());
                }
            }
        }
        int keepThere = plugin.getConfig().getInt("log-bridge.keep-days-github", 30);
        if (keepThere <= 0) {
            return;                                          // 0 = keep everything on GitHub
        }
        String repo = cfg("repo", "");
        HttpResponse<String> list = http.send(request("https://api.github.com/repos/" + repo + "/contents/console").GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (list.statusCode() != 200) {
            return;
        }
        for (var e : JsonParser.parseString(list.body()).getAsJsonArray()) {
            JsonObject f = e.getAsJsonObject();
            LocalDate day = day(f.get("name").getAsString());
            if (day == null || !day.isBefore(today.minusDays(keepThere - 1L))) {
                continue;
            }
            JsonObject body = new JsonObject();
            body.addProperty("message", "remove old logs " + f.get("name").getAsString());
            body.addProperty("sha", f.get("sha").getAsString());
            http.send(request("https://api.github.com/repos/" + repo + "/contents/console/" + f.get("name").getAsString())
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            shas.remove("console/" + f.get("name").getAsString());
        }
    }

    /** "2026-10-20.log" -> the date, anything else -> null */
    static LocalDate day(String fileName) {
        try {
            return fileName.endsWith(".log") ? LocalDate.parse(fileName.substring(0, fileName.length() - 4)) : null;
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }

    /** "185.12.33.4:51234" -> "185.12.x.x" */
    static String hide(String text) {
        return IPV4.matcher(text).replaceAll("$1.$2.x.x");
    }

    /** Creates or overwrites a file in the repository. Returns 1 if it was uploaded. */
    private int upload(String path, String content, boolean skipIfSame) throws IOException, InterruptedException {
        String repo = cfg("repo", "");
        String url = "https://api.github.com/repos/" + repo + "/contents/"
                + URLEncoder.encode(path, StandardCharsets.UTF_8).replace("%2F", "/");
        String sha = shas.get(path);
        if (sha == null) {
            HttpResponse<String> got = http.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (got.statusCode() == 200) {
                JsonObject o = JsonParser.parseString(got.body()).getAsJsonObject();
                sha = o.get("sha").getAsString();
                if (skipIfSame && o.has("content")) {
                    String remote = new String(Base64.getMimeDecoder().decode(o.get("content").getAsString()), StandardCharsets.UTF_8);
                    if (remote.equals(content)) {
                        shas.put(path, sha);
                        return 0;
                    }
                }
            } else if (got.statusCode() == 401 || got.statusCode() == 403) {
                throw new IOException("GitHub refused the token (HTTP " + got.statusCode() + ")");
            } else if (got.statusCode() == 404 && !repoExists(repo)) {
                throw new IOException("repository " + repo + " not found (or the token can't see it)");
            }
        }
        String key = path + "#" + content.hashCode();
        if (skipIfSame && key.equals(shas.get(path + "#last"))) {
            return 0;
        }
        JsonObject body = new JsonObject();
        body.addProperty("message", "logs " + path);
        body.addProperty("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (sha != null) {
            body.addProperty("sha", sha);
        }
        HttpResponse<String> put = http.send(request(url).PUT(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        if (put.statusCode() == 409 || put.statusCode() == 422) {
            shas.remove(path);                              // someone changed it meanwhile: re-read the sha next time
            throw new IOException("GitHub conflict on " + path + ", will retry");
        }
        if (put.statusCode() != 200 && put.statusCode() != 201) {
            throw new IOException("GitHub answered HTTP " + put.statusCode() + " for " + path);
        }
        String newSha = JsonParser.parseString(put.body()).getAsJsonObject()
                .getAsJsonObject("content").get("sha").getAsString();
        shas.put(path, newSha);
        shas.put(path + "#last", key);
        return 1;
    }

    private boolean repoExists(String repo) throws IOException, InterruptedException {
        return http.send(request("https://api.github.com/repos/" + repo).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + cfg("token", "").trim())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ForgeboundGuard");
    }
}
