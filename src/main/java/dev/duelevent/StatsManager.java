package dev.duelevent;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Win counts per player, saved to stats.yml so they survive restarts. */
final class StatsManager {

    record Row(String name, int wins) {
    }

    private static final class Entry {
        String name;
        int wins;
    }

    private final DuelPlugin plugin;
    private final File file;
    private final Map<UUID, Entry> wins = new HashMap<>();

    StatsManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
        load();
    }

    private void load() {
        wins.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("wins");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Entry e = new Entry();
                e.name = root.getString(key + ".name", "?");
                e.wins = root.getInt(key + ".wins", 0);
                if (e.wins > 0) wins.put(id, e);
            } catch (IllegalArgumentException ignored) {
                // skip a damaged entry
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Entry> e : wins.entrySet()) {
            y.set("wins." + e.getKey() + ".name", e.getValue().name);
            y.set("wins." + e.getKey() + ".wins", e.getValue().wins);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save stats.yml: " + ex.getMessage());
        }
    }

    void addWin(UUID id, String name) {
        Entry e = wins.computeIfAbsent(id, k -> new Entry());
        e.name = name;
        e.wins++;
        save();
    }

    /** Everyone with at least one win, best first (ties sorted by name). */
    List<Row> ranked() {
        List<Row> rows = new ArrayList<>();
        for (Entry e : wins.values()) rows.add(new Row(e.name, e.wins));
        rows.sort(Comparator.comparingInt(Row::wins).reversed()
                .thenComparing(r -> r.name().toLowerCase(Locale.ROOT)));
        return rows;
    }

    void reset() {
        wins.clear();
        save();
    }
}
