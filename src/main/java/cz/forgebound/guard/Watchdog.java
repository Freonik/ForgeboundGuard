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
        }
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int percent = (int) (used * 100 / Math.max(1, rt.maxMemory()));
        if (percent >= plugin.getConfig().getInt("watchdog.memory-above-percent", 90) && due("memory")) {
            alert(ForgeboundGuard.color("&8[&cWatchdog&8] &cMemory " + percent + " % full &7("
                    + used / 1048576 + " / " + rt.maxMemory() / 1048576 + " MB)"));
        }
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
