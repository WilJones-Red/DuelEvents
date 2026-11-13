package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Saves everything about a player that a duel changes (items, location, health, effects...)
 * to disk before the duel, and puts it back afterwards. Because it lives on disk, a crash
 * or a player disconnecting mid-duel can never lose their items: the state is restored the
 * next time they join.
 */
final class StateManager {

    private final DuelPlugin plugin;
    private final Path dir;

    StateManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("playerdata");
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not create " + dir + ": " + ex.getMessage());
        }
    }

    private Path file(UUID id) {
        return dir.resolve(id + ".yml");
    }

    boolean hasPending(UUID id) {
        return Files.exists(file(id));
    }

    void discard(UUID id) {
        try {
            Files.deleteIfExists(file(id));
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    Location savedLocation(UUID id) {
        Path f = file(id);
        if (!Files.exists(f)) return null;
        Point pt = Point.read(YamlConfiguration.loadConfiguration(f.toFile()).getConfigurationSection("location"));
        return pt == null ? null : pt.toLocation();
    }

    static double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20.0 : a.getValue();
    }

    /** Writes the player's state to disk. Returns false if it could not be saved safely. */
    boolean save(Player p) {
        try {
            YamlConfiguration y = new YamlConfiguration();
            y.set("contents", ItemCodec.encode(p.getInventory().getContents()));
            Point.of(p.getLocation()).write(y.createSection("location"));
            y.set("gamemode", p.getGameMode().name());
            y.set("health", p.getHealth());
            y.set("food", p.getFoodLevel());
            y.set("saturation", (double) p.getSaturation());
            y.set("level", p.getLevel());
            y.set("exp", (double) p.getExp());
            y.set("invulnerable", p.isInvulnerable());
            y.set("allow-flight", p.getAllowFlight());
            y.set("flying", p.isFlying());
            y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
            y.save(file(p.getUniqueId()).toFile());
            return Files.exists(file(p.getUniqueId()));
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not save state for " + p.getName() + ": " + ex);
            return false;
        }
    }

    /** Puts the player back exactly as saved, then deletes the file. Returns false on failure. */
    boolean restore(Player p) {
        Path f = file(p.getUniqueId());
        if (!Files.exists(f)) return false;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
            ItemStack[] items = ItemCodec.decode(Objects.requireNonNull(y.getString("contents"), "no saved items"));

            p.closeInventory();
            p.leaveVehicle();

            Point pt = Point.read(y.getConfigurationSection("location"));
            Location loc = pt == null ? null : pt.toLocation();
            if (loc == null) loc = Bukkit.getWorlds().get(0).getSpawnLocation();
            p.teleport(loc);

            PlayerInventory inv = p.getInventory();
            inv.clear();
            inv.setContents(Arrays.copyOf(items, Math.min(items.length, inv.getSize())));

            for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
            List<?> effects = y.getList("effects", List.of());
            for (Object o : effects) {
                if (o instanceof PotionEffect pe) p.addPotionEffect(pe);
            }

            GameMode gm;
            try {
                gm = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
            } catch (IllegalArgumentException ex) {
                gm = GameMode.SURVIVAL;
            }
            p.setGameMode(gm);

            double max = maxHealth(p);
            p.setHealth(Math.max(1.0, Math.min(y.getDouble("health", max), max)));
            p.setFoodLevel(y.getInt("food", 20));
            p.setSaturation((float) y.getDouble("saturation", 5.0));
            p.setLevel(y.getInt("level", 0));
            p.setExp((float) y.getDouble("exp", 0.0));
            p.setInvulnerable(y.getBoolean("invulnerable", false));
            p.setAllowFlight(y.getBoolean("allow-flight", false));
            if (p.getAllowFlight()) p.setFlying(y.getBoolean("flying", false));
            p.setFireTicks(0);
            p.setFallDistance(0f);
            p.setVelocity(new Vector(0, 0, 0));
            p.updateInventory();

            Files.deleteIfExists(f);
            return true;
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not restore state for " + p.getName()
                    + " (their saved file was kept, " + f + "): " + ex);
            return false;
        }
    }
}
