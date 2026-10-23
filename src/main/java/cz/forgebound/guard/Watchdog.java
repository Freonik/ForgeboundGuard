package cz.forgebound.guard;

import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerPluginException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Keeps an eye on the server and tells the admins (guard.notify) in game when something is off:
 * low TPS (with the chunk that has the most entities), memory almost full, a plugin throwing errors.
 */
public final class Watchdog implements Listener {

    private final ForgeboundGuard plugin;
    private BukkitTask task;
    private final Map<String, Long> lastAlert = new ConcurrentHashMap<>();
    // performance numbers collected between two [Perf] lines (one sample every 30 s)
    private final long started = System.currentTimeMillis();
    private long nextPerf = started + 5 * 60_000L;
    private double minTps = 20;
    private double maxMspt;
    private int maxMemory;
    private int maxPlayers;

    public Watchdog(ForgeboundGuard plugin) {
        this.plugin = plugin;
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("watchdog.enabled", true);
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L * 60, 20L * 30);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** true if this kind of alert wasn't sent in the last cooldown period */
    private boolean due(String kind) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(30, plugin.getConfig().getInt("watchdog.cooldown-seconds", 300)) * 1000L;
        Long last = lastAlert.get(kind);
        if (last != null && now - last < cooldown) {
            return false;
        }
        lastAlert.put(kind, now);
        return true;
    }

    private void check() {
        if (!on()) {
            return;
        }
        sample();
        double tps = Bukkit.getTPS()[0];
        double warn = plugin.getConfig().getDouble("watchdog.tps-below", 15);
        if (tps < warn && due("tps")) {
            Component msg = ForgeboundGuard.color(String.format(java.util.Locale.ROOT,
                    "&8[&cWatchdog&8] &cTPS %.1f &7(last minute)", tps));
            Chunk worst = null;
            int most = 0;
            for (World w : Bukkit.getWorlds()) {
                for (Chunk c : w.getLoadedChunks()) {
                    int n = c.getEntities().length;
                    if (n > most) {
                        most = n;
                        worst = c;
                    }
                }
            }
            if (worst != null && most >= plugin.getConfig().getInt("watchdog.entities-hint-from", 100)) {
                int x = worst.getX() * 16 + 8;
                int z = worst.getZ() * 16 + 8;
                String where = worst.getWorld().getName() + " " + x + " " + z;
                msg = msg.append(ForgeboundGuard.color(" &7- most entities: &f" + most + " &7at &f" + where + " &e[tp]")
                        .clickEvent(ClickEvent.suggestCommand("/execute in " + worst.getWorld().getKey().asString()
                                + " run tp @s " + x + " ~ " + z)));
            }
            alert(msg);
            plugin.getLogger().warning(String.format(java.util.Locale.ROOT, "[Perf] Low TPS %.1f, MSPT %.1f%s", tps,
                    Bukkit.getAverageTickTime(), worst != null ? ", most entities " + most + " in " + worst.getWorld().getName()
                            + " " + (worst.getX() * 16 + 8) + " " + (worst.getZ() * 16 + 8) : ""));
        }
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int percent = (int) (used * 100 / Math.max(1, rt.maxMemory()));
        if (percent >= plugin.getConfig().getInt("watchdog.memory-above-percent", 90) && due("memory")) {
            alert(ForgeboundGuard.color("&8[&cWatchdog&8] &cMemory " + percent + " % full &7("
                    + used / 1048576 + " / " + rt.maxMemory() / 1048576 + " MB)"));
            plugin.getLogger().warning("[Perf] Memory " + percent + " % full (" + used / 1048576 + " / "
                    + rt.maxMemory() / 1048576 + " MB)");
        }
    }

    /** One sample every 30 s; every perf-log-minutes the console gets a [Perf] line with the period's worst values. */
    private void sample() {
        double tps = Math.min(20, Bukkit.getTPS()[0]);
        long worstTick = 0;
        for (long t : Bukkit.getTickTimes()) {
            worstTick = Math.max(worstTick, t);
        }
        Runtime rt = Runtime.getRuntime();
        int memory = (int) ((rt.totalMemory() - rt.freeMemory()) * 100 / Math.max(1, rt.maxMemory()));
        minTps = Math.min(minTps, tps);
        maxMspt = Math.max(maxMspt, worstTick / 1_000_000.0);
        maxMemory = Math.max(maxMemory, memory);
        maxPlayers = Math.max(maxPlayers, Bukkit.getOnlinePlayers().size());
        int every = plugin.getConfig().getInt("watchdog.perf-log-minutes", 60);
        long now = System.currentTimeMillis();
        if (every <= 0 || now < nextPerf) {
            return;
        }
        nextPerf = now + every * 60_000L;
        plugin.getLogger().info(perfLine(tps, rt));
        minTps = 20;
        maxMspt = 0;
        maxMemory = 0;
        maxPlayers = 0;
    }

    private String perfLine(double tps, Runtime rt) {
        StringBuilder worlds = new StringBuilder();
        int chunks = 0;
        int entities = 0;
        for (World w : Bukkit.getWorlds()) {
            int c = w.getChunkCount();
            int e = w.getEntityCount();
            chunks += c;
            entities += e;
            worlds.append(worlds.isEmpty() ? "" : ", ").append(w.getName()).append(' ').append(c).append('/').append(e);
        }
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576;
        long up = (System.currentTimeMillis() - started) / 60_000L;
        return String.format(java.util.Locale.ROOT,
                "[Perf] TPS %.1f (min %.1f), MSPT %.1f (worst tick %.0f ms), RAM %d/%d MB (max %d %%), players %d (max %d),"
                        + " chunks %d, entities %d [%s chunks/entities], uptime %dh%02dm",
                tps, minTps, Bukkit.getAverageTickTime(), maxMspt, used, rt.maxMemory() / 1048576, maxMemory,
                Bukkit.getOnlinePlayers().size(), maxPlayers, chunks, entities, worlds, up / 60, up % 60);
    }

    /** Errors thrown inside plugins (events, commands, tasks). */
    @EventHandler
    public void onError(ServerExceptionEvent event) {
        if (!on() || !plugin.getConfig().getBoolean("watchdog.plugin-errors", true)) {
            return;
        }
        String source = event.getException() instanceof ServerPluginException pe && pe.getResponsiblePlugin() != null
                ? pe.getResponsiblePlugin().getName() : "server";
        if (!due("error:" + source)) {
            return;
        }
        Throwable cause = event.getException().getCause() != null ? event.getException().getCause() : event.getException();
        String text = cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        if (text.length() > 120) {
            text = text.substring(0, 120) + "...";
        }
        String line = "&8[&cWatchdog&8] &cError in &f" + source + "&7: " + text.replace('&', '+');
        if (Bukkit.isPrimaryThread()) {
            alert(ForgeboundGuard.color(line + " &8(see the console)"));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> alert(ForgeboundGuard.color(line + " &8(see the console)")));
        }
    }

    private void alert(Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("guard.notify")) {
                p.sendMessage(message);
            }
        }
    }
}
