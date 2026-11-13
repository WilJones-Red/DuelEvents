package dev.duelevent;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

final class ArenaManager {

    private final DuelPlugin plugin;
    private final Map<String, Arena> arenas = new LinkedHashMap<>();
    private final File file;
    private final Path snapDir;

    ArenaManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "arenas.yml");
        this.snapDir = plugin.getDataFolder().toPath().resolve("snapshots");
    }

    private Path snapshotFile(String name) {
        return snapDir.resolve(name + ".snap");
    }

    private int perTick() {
        return Math.max(1000, plugin.getConfig().getInt("regen.blocks-per-tick", 20000));
    }

    private long maxVolume() {
        return plugin.getConfig().getLong("regen.max-volume", 4_000_000L);
    }

    void load() {
        arenas.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("arenas");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            Arena a = new Arena(key.toLowerCase(Locale.ROOT));
            a.pos1 = Point.read(s.getConfigurationSection("pos1"));
            a.pos2 = Point.read(s.getConfigurationSection("pos2"));
            a.spawn1 = Point.read(s.getConfigurationSection("spawn1"));
            a.spawn2 = Point.read(s.getConfigurationSection("spawn2"));
            a.dirty = s.getBoolean("dirty", false);
            a.maxPlayers = Math.max(0, s.getInt("max-players", 0));
            a.hasSnapshot = Files.exists(snapshotFile(a.name));
            arenas.put(a.name, a);
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Arena a : arenas.values()) {
            String base = "arenas." + a.name;
            if (a.pos1 != null) a.pos1.write(y.createSection(base + ".pos1"));
            if (a.pos2 != null) a.pos2.write(y.createSection(base + ".pos2"));
            if (a.spawn1 != null) a.spawn1.write(y.createSection(base + ".spawn1"));
            if (a.spawn2 != null) a.spawn2.write(y.createSection(base + ".spawn2"));
            y.set(base + ".dirty", a.dirty);
            if (a.maxPlayers > 0) y.set(base + ".max-players", a.maxPlayers);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save arenas.yml: " + ex.getMessage());
        }
    }

    Arena get(String name) {
        return name == null ? null : arenas.get(name.toLowerCase(Locale.ROOT));
    }

    Collection<Arena> all() {
        return arenas.values();
    }

    Arena create(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (arenas.containsKey(key)) return null;
        Arena a = new Arena(key);
        arenas.put(key, a);
        save();
        return a;
    }

    boolean delete(Arena a) {
        if (a.state != Arena.State.IDLE) return false;
        arenas.remove(a.name);
        try {
            Files.deleteIfExists(snapshotFile(a.name));
        } catch (IOException ignored) {
            // leftover file is harmless
        }
        save();
        return true;
    }

    /** Another arena whose region overlaps this one (same world), or null. */
    Arena overlapping(Arena a) {
        if (!a.hasRegion()) return null;
        for (Arena o : arenas.values()) {
            if (o == a || !o.hasRegion() || !o.worldName().equals(a.worldName())) continue;
            if (a.minX() <= o.maxX() && a.maxX() >= o.minX()
                    && a.minY() <= o.maxY() && a.maxY() >= o.minY()
                    && a.minZ() <= o.maxZ() && a.maxZ() >= o.minZ()) {
                return o;
            }
        }
        return null;
    }

    /** Sets pos1/pos2 (first = pos1). Returns an error message, or null on success. */
    String setRegionPoint(Arena a, boolean first, Location where) {
        Point oldPos1 = a.pos1;
        Point oldPos2 = a.pos2;
        Point p = Point.ofBlock(where);
        if (first) {
            a.pos1 = p;
            if (a.pos2 != null && !a.pos2.world().equals(p.world())) a.pos2 = null;
        } else {
            a.pos2 = p;
            if (a.pos1 != null && !a.pos1.world().equals(p.world())) a.pos1 = null;
        }
        Arena clash = overlapping(a);
        if (clash != null) {
            a.pos1 = oldPos1;
            a.pos2 = oldPos2;
            return "That would overlap arena " + clash.name + ". Arenas can't overlap, because duels running at the same time would damage each other.";
        }
        invalidateSnapshot(a);
        save();
        return null;
    }

    void setSpawn(Arena a, boolean first, Location where) {
        if (first) a.spawn1 = Point.of(where);
        else a.spawn2 = Point.of(where);
        save();
    }

    private void invalidateSnapshot(Arena a) {
        a.hasSnapshot = false;
        try {
            Files.deleteIfExists(snapshotFile(a.name));
        } catch (IOException ignored) {
            // leftover file is harmless
        }
    }

    /** Saves the arena's current blocks as the layout it will be restored to. */
    void capture(Arena a, Consumer<String> done) {
        if (!a.hasRegion()) {
            done.accept("Set pos1 and pos2 first.");
            return;
        }
        if (a.state != Arena.State.IDLE) {
            done.accept("The arena is busy right now.");
            return;
        }
        Arena clash = overlapping(a);
        if (clash != null) {
            done.accept("This region overlaps arena " + clash.name + ". Arenas can't overlap.");
            return;
        }
        a.state = Arena.State.BUSY;
        ArenaSnapshot.capture(plugin, a, snapshotFile(a.name), perTick(), maxVolume(), err -> {
            a.state = Arena.State.IDLE;
            if (err == null) {
                a.hasSnapshot = true;
                a.dirty = false;
                save();
            }
            done.accept(err);
        });
    }

    /** Restores the arena to its snapshot. after (may be null) receives null on success or an error message. */
    void regenerate(Arena a, Consumer<String> after) {
        if (!a.hasSnapshot) {
            a.state = Arena.State.IDLE;
            if (after != null) after.accept("The arena has no snapshot.");
            return;
        }
        a.state = Arena.State.BUSY;
        ArenaSnapshot.restore(plugin, a, snapshotFile(a.name), perTick(), err -> {
            a.state = Arena.State.IDLE;
            if (err == null) {
                a.dirty = false;
                save();
            } else {
                plugin.getLogger().severe("Arena " + a.name + " could not be repaired: " + err
                        + ". Run /duel arena regen " + a.name + " to try again.");
            }
            if (after != null) after.accept(err);
        });
    }

    /**
     * With repair-after-fight on, an arena left damaged by a crash or restart is rebuilt at startup.
     * (With only repair-before-fight on, the repair simply happens before the next duel.)
     */
    void regenerateDirtyArenas() {
        if (!plugin.getConfig().getBoolean("arena.repair-after-fight", false)) return;
        for (Arena a : arenas.values()) {
            if (a.dirty && a.hasSnapshot && a.state == Arena.State.IDLE) {
                plugin.getLogger().info("Arena " + a.name + " was left damaged by an unfinished duel, repairing...");
                regenerate(a, null);
            }
        }
    }
}
