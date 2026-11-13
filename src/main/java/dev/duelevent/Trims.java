package dev.duelevent;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Puts a team-coloured armor trim on the armor a player gets from a kit (see team-trims in config.yml). */
final class Trims {

    // Slots of a player's inventory array: 36 boots, 37 leggings, 38 chestplate, 39 helmet.
    private static final int BOOTS = 36;
    private static final int LEGGINGS = 37;
    private static final int CHESTPLATE = 38;
    private static final int HELMET = 39;

    private final DuelPlugin plugin;
    private final Set<String> warned = new HashSet<>();

    Trims(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /** Trims the armor pieces in this inventory array (blue is team 1, red is team 2). */
    void apply(ItemStack[] contents, boolean blue) {
        String side = blue ? "blue" : "red";
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("team-trims." + side);
        if (s == null) return;
        TrimMaterial material = material(s.getString("material", blue ? "diamond" : "redstone"));
        if (material == null) return;
        trim(contents, HELMET, material, pattern(s.getString("helmet", "flow")));
        trim(contents, CHESTPLATE, material, pattern(s.getString("chestplate", "flow")));
        trim(contents, LEGGINGS, material, pattern(s.getString("leggings", "none")));
        trim(contents, BOOTS, material, pattern(s.getString("boots", "tide")));
    }

    private void trim(ItemStack[] contents, int slot, TrimMaterial material, TrimPattern pattern) {
        if (pattern == null || slot >= contents.length) return;
        ItemStack item = contents[slot];
        if (item == null || item.getType().isAir()) return;
        if (!(item.getItemMeta() instanceof ArmorMeta meta)) return;
        meta.setTrim(new ArmorTrim(material, pattern));
        item.setItemMeta(meta);
    }

    private static boolean isNone(String name) {
        if (name == null) return true;
        String n = name.trim().toLowerCase(Locale.ROOT);
        return n.isEmpty() || n.equals("none") || n.equals("off") || n.equals("false");
    }

    private static NamespacedKey key(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        return NamespacedKey.fromString(n.contains(":") ? n : "minecraft:" + n);
    }

    private TrimMaterial material(String name) {
        if (isNone(name)) return null;
        NamespacedKey k = key(name);
        TrimMaterial m = k == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).get(k);
        if (m == null) warnOnce("trim material", name);
        return m;
    }

    private TrimPattern pattern(String name) {
        if (isNone(name)) return null;
        NamespacedKey k = key(name);
        TrimPattern p = k == null ? null : RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(k);
        if (p == null) warnOnce("trim pattern", name);
        return p;
    }

    private void warnOnce(String what, String name) {
        if (warned.add(what + ":" + name)) {
            plugin.getLogger().warning("Unknown " + what + " \"" + name + "\" in team-trims (config.yml); it is being ignored.");
        }
    }
}
