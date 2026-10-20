package cz.forgebound.guard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * X-ray hints: counts how many diamond / ancient debris veins a player finds compared to the plain stone they mine.
 * Someone with x-ray digs straight to the ores, so they find a lot of veins for very little stone. When that ratio is
 * clearly above normal, admins (guard.notify) get a message with the details and a click-to-teleport.
 * It never punishes anyone: it only points admins to something worth a look (Paper's anti-xray stops most x-ray anyway).
 * Also: /guard check <player>.
 */
public final class MiningWatch implements Listener {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** the ores that are watched; key = config name */
    private static final Map<String, Set<Material>> ORES = Map.of(
            "diamond", Set.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE),
            "ancient_debris", Set.of(Material.ANCIENT_DEBRIS));

    /** "filler" blocks you dig through while mining normally */
    private static final Set<Material> STONE = Set.of(Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.NETHERRACK, Material.BASALT, Material.BLACKSTONE,
            Material.COBBLED_DEEPSLATE, Material.CALCITE, Material.SMOOTH_BASALT, Material.SOUL_SOIL, Material.GRAVEL,
            Material.DIRT, Material.SAND, Material.MAGMA_BLOCK);

    private final ForgeboundGuard plugin;
    private final File logFile;
    private final Map<UUID, Stats> stats = new HashMap<>();
    /** ore blocks placed by players (re-mining them doesn't count) */
    private final Set<Long> placed = new HashSet<>();
    private final Map<UUID, Long> lastAlert = new HashMap<>();

    record Find(String ore, long at, Location where) { }

    /** what a player mined recently (in memory, since the server started) */
    static final class Stats {
        final Deque<Long> stone = new ArrayDeque<>();
        final Deque<Find> veins = new ArrayDeque<>();
        final Map<String, Integer> totalOres = new HashMap<>();
        int totalStone;
        Find lastOre;
    }

    public MiningWatch(ForgeboundGuard plugin) {
        this.plugin = plugin;
        this.logFile = new File(plugin.getDataFolder(), "mining.log");
    }

    private boolean on() {
        return plugin.getConfig().getBoolean("mining.enabled", true);
    }

    private static long key(Block b) {
        return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF)
                ^ (long) b.getWorld().getName().hashCode() << 52;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (oreOf(event.getBlock().getType()) != null) {
            placed.add(key(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (!on() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Block block = event.getBlock();
        Material type = block.getType();
        long now = System.currentTimeMillis();
        Stats s = stats.computeIfAbsent(p.getUniqueId(), k -> new Stats());
        if (STONE.contains(type)) {
            s.stone.addLast(now);
            s.totalStone++;
            trim(s, now);
            return;
        }
        String ore = oreOf(type);
        if (ore == null || placed.remove(key(block))) {
            return;
        }
        s.totalOres.merge(ore, 1, Integer::sum);
        Location at = block.getLocation();
        // blocks of one vein are close together: only the first one of a vein counts
        boolean sameVein = s.lastOre != null && s.lastOre.ore().equals(ore) && now - s.lastOre.at() < 60_000
                && s.lastOre.where().getWorld() == at.getWorld() && s.lastOre.where().distanceSquared(at) <= 16;
        s.lastOre = new Find(ore, now, at);
        if (sameVein) {
            return;
        }
        s.veins.addLast(s.lastOre);
        trim(s, now);
        plugin.appendLine(logFile, STAMP.format(LocalDateTime.now()) + " | " + p.getName() + " | " + ore + " | "
                + at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                + " | stone in window: " + s.stone.size());
        check(p, s, ore, now);
    }

    private static String oreOf(Material m) {
        for (Map.Entry<String, Set<Material>> e : ORES.entrySet()) {
            if (e.getValue().contains(m)) {
                return e.getKey();
            }
        }
        return null;
    }

    private void trim(Stats s, long now) {
        long window = Math.max(5, plugin.getConfig().getInt("mining.window-minutes", 45)) * 60_000L;
        while (!s.stone.isEmpty() && now - s.stone.peekFirst() > window) {
            s.stone.pollFirst();
        }
        while (!s.veins.isEmpty() && now - s.veins.peekFirst().at() > window) {
            s.veins.pollFirst();
        }
    }

    /** Suspicious when enough veins were found with too little stone around them. */
    static boolean suspicious(int veins, int stone, int minVeins, int stonePerVein) {
        return veins >= minVeins && stone < (long) veins * stonePerVein;
    }

    private void check(Player p, Stats s, String ore, long now) {
        int veins = (int) s.veins.stream().filter(f -> f.ore().equals(ore)).count();
        int minVeins = plugin.getConfig().getInt("mining." + ore + ".min-veins", ore.equals("diamond") ? 6 : 5);
        int perVein = plugin.getConfig().getInt("mining." + ore + ".stone-per-vein", ore.equals("diamond") ? 80 : 100);
        if (!suspicious(veins, s.stone.size(), minVeins, perVein) || p.hasPermission("guard.bypass")) {
            return;
        }
        Long last = lastAlert.get(p.getUniqueId());
        if (last != null && now - last < plugin.getConfig().getInt("mining.alert-cooldown-minutes", 15) * 60_000L) {
            return;
        }
        lastAlert.put(p.getUniqueId(), now);
        String name = ore.equals("diamond") ? "diamond" : "ancient debris";
        Location at = p.getLocation();
        StringBuilder hover = new StringBuilder("Last finds:");
        s.veins.stream().filter(f -> f.ore().equals(ore)).forEach(f -> hover.append('\n')
                .append(TIME.format(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(f.at()), java.time.ZoneId.systemDefault())))
                .append("  ").append(f.where().getBlockX()).append(' ').append(f.where().getBlockY()).append(' ')
                .append(f.where().getBlockZ()));
        hover.append("\n\nNormal mining: about ").append(perVein).append("+ stone per vein.")
                .append("\nCaves, TNT/bed mining can look the same - have a look before judging.\nClick to teleport.");
        Component msg = ForgeboundGuard.color("&8[&bMining&8] &e" + p.getName() + " &7found &f" + veins + " " + name
                        + " veins &7with only &f" + s.stone.size() + " stone &7in "
                        + plugin.getConfig().getInt("mining.window-minutes", 45) + " min &8(possible x-ray)")
                .hoverEvent(HoverEvent.showText(Component.text(hover.toString())))
                .clickEvent(ClickEvent.runCommand("/execute in " + at.getWorld().getKey().asString() + " run tp @s "
                        + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()));
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("guard.notify")) {
                admin.sendMessage(msg);
            }
        }
        plugin.getLogger().info("[Mining] " + p.getName() + " found " + veins + " " + name + " veins with only "
                + s.stone.size() + " stone (possible x-ray)");
        plugin.appendLine(logFile, STAMP.format(LocalDateTime.now()) + " | " + p.getName() + " | ALERT | " + veins + " "
                + name + " veins, " + s.stone.size() + " stone");
    }

    // ================================================================ /guard check <player>

    public void check(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        @SuppressWarnings("deprecation")
        OfflinePlayer p = online != null ? online : Bukkit.getOfflinePlayer(name);
        if (online == null && !p.hasPlayedBefore()) {
            sender.sendMessage(ForgeboundGuard.color("&c" + name + " has never played here."));
            return;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(ForgeboundGuard.color("&d&lCheck: &f" + p.getName()));
        if (online != null) {
            Location l = online.getLocation();
            lines.add(ForgeboundGuard.color("&7Online &8- &7" + l.getWorld().getName() + " &f" + l.getBlockX() + " "
                            + l.getBlockY() + " " + l.getBlockZ() + " &e[tp]")
                    .clickEvent(ClickEvent.runCommand("/execute in " + l.getWorld().getKey().asString() + " run tp @s "
                            + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ())));
            lines.add(ForgeboundGuard.color(String.format(Locale.ROOT, "&7Health &f%.0f&7, %s, ping &f%d ms",
                    online.getHealth(), online.getGameMode().name().toLowerCase(Locale.ROOT), online.getPing())));
            int[] inv = count(online.getInventory());
            int[] ender = count(online.getEnderChest());
            lines.add(ForgeboundGuard.color("&7Diamonds &f" + inv[0] + " &8(+" + ender[0] + " in ender chest)&7, "
                    + "netherite &f" + inv[1] + " &8(+" + ender[1] + ")"));
        } else {
            long seen = p.getLastSeen();
            lines.add(ForgeboundGuard.color("&7Offline, last seen &f"
                    + (seen > 0 ? ForgeboundGuard.time(System.currentTimeMillis() - seen) + " ago" : "?")));
        }
        Stats s = stats.get(p.getUniqueId());
        if (s != null) {
            trim(s, System.currentTimeMillis());
            lines.add(ForgeboundGuard.color("&7Since the restart: &f" + s.totalStone + " stone&7, &f"
                    + s.totalOres.getOrDefault("diamond", 0) + " diamond ore&7, &f"
                    + s.totalOres.getOrDefault("ancient_debris", 0) + " ancient debris"));
            lines.add(ForgeboundGuard.color("&7Last " + plugin.getConfig().getInt("mining.window-minutes", 45)
                    + " min: &f" + s.veins.size() + " veins&7, &f" + s.stone.size() + " stone"));
            List<Find> finds = new ArrayList<>(s.veins);
            for (Find f : finds.subList(Math.max(0, finds.size() - 5), finds.size())) {
                lines.add(ForgeboundGuard.color("&8 - " + TIME.format(LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(f.at()), java.time.ZoneId.systemDefault())) + " &7" + f.ore()
                        + " &f" + f.where().getBlockX() + " " + f.where().getBlockY() + " " + f.where().getBlockZ()));
            }
        } else {
            lines.add(ForgeboundGuard.color("&7No mining since the restart."));
        }
        if (plugin.moderation() != null) {
            lines.add(ForgeboundGuard.color("&7Muted: " + (plugin.moderation().muted(p.getUniqueId()) ? "&cyes" : "&ano")
                    + " &8- /guard modlog " + p.getName()));
        }
        lines.forEach(sender::sendMessage);
    }

    /** [diamonds (incl. blocks and ores), netherite ingots (incl. blocks)] */
    private static int[] count(Inventory inv) {
        int diamonds = 0;
        int netherite = 0;
        for (ItemStack it : inv.getContents()) {
            if (it == null) {
                continue;
            }
            switch (it.getType()) {
                case DIAMOND, DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> diamonds += it.getAmount();
                case DIAMOND_BLOCK -> diamonds += it.getAmount() * 9;
                case NETHERITE_INGOT -> netherite += it.getAmount();
                case NETHERITE_BLOCK -> netherite += it.getAmount() * 9;
                default -> { }
            }
        }
        return new int[] {diamonds, netherite};
    }
}
