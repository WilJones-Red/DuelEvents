package dev.duelevent;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

final class KitManager {

    private final DuelPlugin plugin;
    private final File dir;
    private final Map<String, Kit> kits = new TreeMap<>();

    KitManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "kits");
        dir.mkdirs();
        load();
    }

    void load() {
        kits.clear();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String data = y.getString("contents");
            if (data == null) continue;
            String name = f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT);
            kits.put(name, new Kit(name, data));
        }
    }

    Kit get(String name) {
        return name == null ? null : kits.get(name.toLowerCase(Locale.ROOT));
    }

    Collection<String> names() {
        return kits.keySet();
    }

    int size() {
        return kits.size();
    }

    Kit only() {
        return kits.size() == 1 ? kits.values().iterator().next() : null;
    }

    /** Returns an error message, or null on success. */
    String save(String name, PlayerInventory inv) {
        String key = name.toLowerCase(Locale.ROOT);
        try {
            String data = ItemCodec.encode(inv.getContents());
            YamlConfiguration y = new YamlConfiguration();
            y.set("contents", data);
            y.save(new File(dir, key + ".yml"));
            kits.put(key, new Kit(key, data));
            plugin.layouts().wipeKit(key); // players' saved layouts no longer match this kit
            return null;
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not save kit " + key + ": " + ex);
            return ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
    }

    boolean delete(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (kits.remove(key) == null) return false;
        new File(dir, key + ".yml").delete();
        plugin.layouts().wipeKit(key);
        return true;
    }
}
