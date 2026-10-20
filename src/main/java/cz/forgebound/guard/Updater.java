package cz.forgebound.guard;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-update from the GitHub releases: a newer ForgeboundGuard.jar is downloaded into plugins/update/
 * (Paper swaps it in on the next restart). Nothing is replaced while the server runs.
 * Admins (guard.admin) are told what happened.
 */
public final class Updater implements Listener {

    private final ForgeboundGuard plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private BukkitTask task;
    /** what admins should hear about on join (e.g. "restart to update") */
    private volatile String pending;
    private volatile String downloaded;

    public Updater(ForgeboundGuard plugin) {
        this.plugin = plugin;
    }

    private String cfg(String key, String def) {
        return plugin.getConfig().getString("auto-update." + key, def);
    }

    private boolean on(String key, boolean def) {
        return plugin.getConfig().getBoolean("auto-update." + key, def);
    }

    public void start() {
        stop();
        if (!on("enabled", true)) {
            return;
        }
        long every = Math.max(1, plugin.getConfig().getInt("auto-update.check-hours", 6)) * 3600L * 20L;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::check, 20L * 60, every);
    }

    /** /guard update on|off|now */
    public void command(org.bukkit.command.CommandSender sender, String what) {
        switch (what) {
            case "on", "off" -> {
                plugin.getConfig().set("auto-update.enabled", what.equals("on"));
                plugin.saveConfig();
                start();
                sender.sendMessage(ForgeboundGuard.color(what.equals("on")
                        ? "&aAuto-update on &7(checks every " + plugin.getConfig().getInt("auto-update.check-hours", 6) + " h)."
                        : "&7Auto-update off. &8Nothing will be downloaded."));
            }
            default -> {
                sender.sendMessage(ForgeboundGuard.color("&7Checking for a new version..."));
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    String before = pending;
                    check();
                    String after = pending;
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(ForgeboundGuard.color(
                            after != null && !after.equals(before) ? "&8[&6Update&8] " + after
                                    : downloaded != null ? "&aVersion &f" + downloaded + " &ais already downloaded, restart to use it."
                                    : "&7You have the newest version &8(" + plugin.getPluginMeta().getVersion() + ")")));
                });
            }
        }
    }

    public boolean enabled() {
        return on("enabled", true);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** Off the main thread. */
    private void check() {
        try {
            String repo = cfg("repo", "Freonik/ForgeboundGuard");
            HttpResponse<String> res = http.send(get("https://api.github.com/repos/" + repo + "/releases/latest"),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return;
            }
            JsonObject release = JsonParser.parseString(res.body()).getAsJsonObject();
            String tag = release.get("tag_name").getAsString();
            String latest = version(tag);
            String current = plugin.getPluginMeta().getVersion();
            if (latest == null) {
                return;
            }
            int cmp = compare(latest, current);
            if (cmp > 0) {
                update(repo, tag, latest, release);
            }
        } catch (Exception ex) {
            plugin.getLogger().fine("Update check failed: " + ex.getMessage());
        }
    }

    private void update(String repo, String tag, String version, JsonObject release) throws IOException, InterruptedException {
        if (version.equals(downloaded)) {
            return;
        }
        if (!on("download", true)) {
            tell("&eForgebound Guard &f" + version + " &eis out &8(auto-update.download is off)");
            return;
        }
        String url = asset(release, "ForgeboundGuard.jar");
        if (url == null) {
            return;
        }
        File folder = Bukkit.getUpdateFolderFile();
        Files.createDirectories(folder.toPath());
        // the update folder replaces the jar with the same file name
        File target = new File(folder, plugin.jarFile().getName());
        Path tmp = target.toPath().resolveSibling(target.getName() + ".part");
        HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", "ForgeboundGuard").GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (res.statusCode() != 200 || Files.size(tmp) < 10_000) {
            Files.deleteIfExists(tmp);
            return;
        }
        Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        downloaded = version;
        tell("&aForgebound Guard &f" + version + " &ais downloaded"
                + "&a. &7It's used after the next &frestart&7.");
    }

    private static String asset(JsonObject release, String name) {
        if (!release.has("assets")) {
            return null;
        }
        for (JsonElement e : release.getAsJsonArray("assets")) {
            JsonObject a = e.getAsJsonObject();
            if (name.equals(a.get("name").getAsString())) {
                return a.get("browser_download_url").getAsString();
            }
        }
        return null;
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "ForgeboundGuard").GET().build();
    }

    /** "v2.7.0-build.65" -> "2.7.0" */
    static String version(String tag) {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(tag);
        return m.find() ? m.group() : null;
    }

    /** semantic compare of "x.y.z" (anything after the numbers is ignored) */
    static int compare(String a, String b) {
        String va = version(a);
        String vb = version(b);
        if (va == null || vb == null) {
            return 0;
        }
        String[] x = va.split("\\.");
        String[] y = vb.split("\\.");
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private void tell(String legacy) {
        pending = legacy;
        plugin.getLogger().info(PlainText.of(legacy));
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("guard.admin")) {
                    p.sendMessage(ForgeboundGuard.color("&8[&6Update&8] " + legacy));
                }
            }
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String msg = pending;
        if (msg != null && event.getPlayer().hasPermission("guard.admin")) {
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    event.getPlayer().sendMessage(ForgeboundGuard.color("&8[&6Update&8] " + msg)), 60L);
        }
    }

    /** "&aText" -> "Text" for the console */
    private static final class PlainText {
        static String of(String legacy) {
            return legacy.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        }
    }
}
