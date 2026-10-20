package cz.forgebound.guard;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forgebound Guard: chat moderation (a free filter + an optional AI moderator) and watchdog alerts.
 * A separate plugin, so it can be switched off or removed without touching Forgebound.
 */
public final class ForgeboundGuard extends JavaPlugin {

    private ModerationManager moderation;
    private Watchdog watchdog;
    private LogBridge logs;
    private Updater updater;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ForgeboundGuard-IO");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void onEnable() {
        migrateFromForgebound();
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        moderation = new ModerationManager(this);
        getServer().getPluginManager().registerEvents(moderation, this);
        moderation.start();
        watchdog = new Watchdog(this);
        getServer().getPluginManager().registerEvents(watchdog, this);
        watchdog.start();
        logs = new LogBridge(this);
        logs.start();
        updater = new Updater(this);
        getServer().getPluginManager().registerEvents(updater, this);
        updater.start();
        registerCommands();
        getLogger().info("Chat filter " + (moderation.enabled() ? "on" : "off") + ", AI moderator "
                + (moderation.aiReady() ? "on" : getConfig().getBoolean("ai.enabled") ? "on but has no API key" : "off") + ".");
    }

    @Override
    public void onDisable() {
        if (moderation != null) {
            moderation.stop();
        }
        if (watchdog != null) {
            watchdog.stop();
        }
        if (logs != null) {
            logs.stop();
        }
        if (updater != null) {
            updater.stop();
        }
        io.shutdown();
        try {
            io.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public void reload() {
        reloadConfig();
        moderation.start();
        watchdog.start();
        logs.start();
        updater.start();
    }

    // ================================================================ moving over from Forgebound 2.5

    /**
     * Forgebound 2.5.0 had the moderation built in. On the first start, its settings (moderation.* and watchdog.*),
     * the active mutes and the log are taken over, so nothing has to be set up again.
     */
    private void migrateFromForgebound() {
        File old = new File(getDataFolder().getParentFile(), "Forgebound");
        File config = new File(getDataFolder(), "config.yml");
        if (config.exists() || !old.isDirectory()) {
            return;
        }
        YamlConfiguration oldConfig = YamlConfiguration.loadConfiguration(new File(old, "config.yml"));
        ConfigurationSection mod = oldConfig.getConfigurationSection("moderation");
        ConfigurationSection dog = oldConfig.getConfigurationSection("watchdog");
        if (mod == null && dog == null) {
            return;
        }
        saveDefaultConfig();
        reloadConfig();
        if (mod != null) {
            for (String path : mod.getKeys(true)) {
                if (!mod.isConfigurationSection(path)) {
                    getConfig().set(path, mod.get(path));
                }
            }
        }
        if (dog != null) {
            for (String path : dog.getKeys(true)) {
                if (!dog.isConfigurationSection(path)) {
                    getConfig().set("watchdog." + path, dog.get(path));
                }
            }
        }
        saveConfig();
        for (String name : List.of("moderation.yml", "moderation.log")) {
            File from = new File(old, name);
            if (from.isFile()) {
                try {
                    Files.copy(from.toPath(), new File(getDataFolder(), name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ex) {
                    getLogger().warning("Could not copy " + name + " from Forgebound: " + ex.getMessage());
                }
            }
        }
        getLogger().info("Took over the moderation settings, mutes and log from Forgebound.");
    }

    // ================================================================ /guard

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            var root = Commands.literal("guard").requires(s -> s.getSender().hasPermission("guard.admin"))
                    .executes(ctx -> status(ctx.getSource().getSender()));
            root.then(Commands.literal("status").executes(ctx -> status(ctx.getSource().getSender())));
            root.then(Commands.literal("reload").executes(ctx -> {
                reload();
                ctx.getSource().getSender().sendMessage(color("&aForgebound Guard reloaded."));
                return status(ctx.getSource().getSender());
            }));
            root.then(Commands.literal("ai")
                    .then(Commands.literal("on").executes(ctx -> ai(ctx.getSource().getSender(), true)))
                    .then(Commands.literal("off").executes(ctx -> ai(ctx.getSource().getSender(), false))));
            var update = Commands.literal("update").executes(ctx -> {
                updater.command(ctx.getSource().getSender(), "now");
                return Command.SINGLE_SUCCESS;
            });
            for (String sub : List.of("on", "off", "now")) {
                update.then(Commands.literal(sub).executes(ctx -> {
                    updater.command(ctx.getSource().getSender(), sub);
                    return Command.SINGLE_SUCCESS;
                }));
            }
            root.then(update);
            root.then(Commands.literal("logs").then(Commands.literal("now").executes(ctx -> {
                logs.now(ctx.getSource().getSender());
                return Command.SINGLE_SUCCESS;
            })));
            root.then(Commands.literal("mute")
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(this::players)
                            .executes(ctx -> mod(ctx, "mute", 10))
                            .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 10080))
                                    .executes(ctx -> mod(ctx, "mute", IntegerArgumentType.getInteger(ctx, "minutes"))))));
            root.then(Commands.literal("unmute")
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(this::players)
                            .executes(ctx -> mod(ctx, "unmute", 0))));
            root.then(Commands.literal("modlog")
                    .executes(ctx -> {
                        moderation.command(ctx.getSource().getSender(), "modlog", null, 0);
                        return Command.SINGLE_SUCCESS;
                    })
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(this::players)
                            .executes(ctx -> mod(ctx, "modlog", 0))));
            commands.register(root.build(), "Chat moderation and watchdog (Forgebound Guard)", List.of("fguard"));
        });
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> players(
            CommandContext<CommandSourceStack> ctx, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(java.util.Locale.ROOT).startsWith(typed)) {
                builder.suggest(p.getName());
            }
        }
        return builder.buildFuture();
    }

    private int mod(CommandContext<CommandSourceStack> ctx, String sub, int minutes) {
        moderation.command(ctx.getSource().getSender(), sub, StringArgumentType.getString(ctx, "player"), minutes);
        return Command.SINGLE_SUCCESS;
    }

    private int ai(CommandSender sender, boolean on) {
        getConfig().set("ai.enabled", on);
        saveConfig();
        moderation.start();
        if (on && !moderation.hasKey()) {
            sender.sendMessage(color("&eAI turned on, but there's no API key yet: put it into &fai.api-key &ein "
                    + "plugins/ForgeboundGuard/config.yml and run &f/guard reload&e."));
        } else {
            sender.sendMessage(color(on ? "&aAI moderator on." : "&7AI moderator off &8(the free filter keeps running)."));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int status(CommandSender sender) {
        sender.sendMessage(color("&d&lForgebound Guard"));
        sender.sendMessage(color("&7Chat filter: " + (moderation.enabled() ? "&aon" : "&coff")));
        String ai = moderation.aiReady() ? "&aon &8(" + getConfig().getString("ai.model") + ")"
                : getConfig().getBoolean("ai.enabled") ? "&eon, but no API key" : "&coff";
        sender.sendMessage(color("&7AI moderator: " + ai + " &8- /guard ai on|off"));
        sender.sendMessage(color("&7Watchdog: " + (getConfig().getBoolean("watchdog.enabled", true) ? "&aon" : "&coff")));
        sender.sendMessage(color("&7Log bridge: " + (logs.enabled() ? "&aon &8(" + logs.lastResult() + ")" : "&coff")
                + " &8- /guard logs now"));
        sender.sendMessage(color("&7Auto-update: " + (updater.enabled() ? "&aon" : "&coff") + " &8- /guard update on|off|now"));
        sender.sendMessage(color("&7Muted right now: &f" + moderation.mutedCount() + " &8- /guard modlog"));
        return Command.SINGLE_SUCCESS;
    }

    // ================================================================ helpers

    /** This plugin's jar in plugins/ (for the updater). */
    public File jarFile() {
        return getFile();
    }

    public static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** A message from the config's "messages" section ({name} placeholders), with the prefix. */
    public Component msg(String key, String def, Object... pairs) {
        String text = getConfig().getString("messages." + key, def);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace("{" + pairs[i] + "}", String.valueOf(pairs[i + 1]));
        }
        return color(getConfig().getString("messages.prefix", "") + text);
    }

    /** 3:05 / 1:02:03 */
    public static String time(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, sec) : String.format("%d:%02d", m, sec);
    }

    /** Saves a YAML file in the background (the text is taken now). */
    public void saveYaml(YamlConfiguration yaml, File file) {
        String text = yaml.saveToString();
        Runnable task = () -> {
            try {
                Path target = file.toPath();
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(file.getName() + ".tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                getLogger().warning("Could not save " + file.getName() + ": " + ex.getMessage());
            }
        };
        run(task);
    }

    /** Appends a line to a text file in the background. */
    public void appendLine(File file, String line) {
        run(() -> {
            try {
                Files.createDirectories(file.toPath().getParent());
                Files.writeString(file.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                getLogger().warning("Could not write " + file.getName() + ": " + ex.getMessage());
            }
        });
    }

    private void run(Runnable task) {
        if (io.isShutdown()) {
            task.run();
        } else {
            io.execute(task);
        }
    }
}
