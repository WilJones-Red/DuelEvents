package dev.duelevent;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Each player's own arrangement of a kit, saved as layouts/&lt;kit&gt;/&lt;player uuid&gt;.yml.
 * Every file remembers a fingerprint of the kit it was made from. When the kit changes the layouts are
 * wiped (right away when it's changed through the plugin, and on startup or /duel reload when a kit file
 * was edited by hand), so nobody is ever handed a stale arrangement.
 */
final class LayoutManager {

    private final DuelPlugin plugin;
    private final Path dir;

    LayoutManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.dir = plugin.getDataFolder().toPath().resolve("layouts");
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not create " + dir + ": " + ex.getMessage());
        }
    }

    static String fingerprint(Kit kit) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(kit.data().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            return Integer.toHexString(kit.data().hashCode());
        }
    }

    private Path kitDir(String kitName) {
        return dir.resolve(kitName.toLowerCase(Locale.ROOT));
    }

    private Path file(String kitName, UUID id) {
        return kitDir(kitName).resolve(id + ".yml");
    }

    boolean has(UUID id, Kit kit) {
        return Files.exists(file(kit.name(), id));
    }

    /** The player's saved arrangement of this kit (41 slots), or null if they have none that still matches the kit. */
    ItemStack[] load(UUID id, Kit kit) {
        Path f = file(kit.name(), id);
        if (!Files.exists(f)) return null;
        try {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
            if (!fingerprint(kit).equals(y.getString("fingerprint"))) {
                Files.deleteIfExists(f);
                return null;
            }
            String data = y.getString("contents");
            return data == null ? null : ItemCodec.decode(data);
        } catch (Exception ex) {
            plugin.getLogger().warning("Could not read layout " + f + ": " + ex);
            return null;
        }
    }

    /** Returns an error message, or null on success. */
    String save(UUID id, Kit kit, ItemStack[] layout) {
        Path f = file(kit.name(), id);
        try {
            Files.createDirectories(f.getParent());
            YamlConfiguration y = new YamlConfiguration();
            y.set("fingerprint", fingerprint(kit));
            y.set("contents", ItemCodec.encode(layout));
            y.save(f.toFile());
            return null;
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not save layout " + f + ": " + ex);
            return ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
    }

    void reset(UUID id, String kitName) {
        try {
            Files.deleteIfExists(file(kitName, id));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not delete layout " + file(kitName, id) + ": " + ex.getMessage());
        }
    }

    /** Deletes every player's saved layout of this kit (the kit was changed or deleted). */
    void wipeKit(String kitName) {
        deleteTree(kitDir(kitName));
    }

    /** Deletes layouts whose kit no longer exists or no longer matches the kit they were made from. */
    void purgeStale(KitManager kits) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> kitDirs = Files.newDirectoryStream(dir)) {
            for (Path kd : kitDirs) {
                if (!Files.isDirectory(kd)) continue;
                Kit kit = kits.get(kd.getFileName().toString());
                if (kit == null) {
                    deleteTree(kd);
                    continue;
                }
                String fp = fingerprint(kit);
                try (DirectoryStream<Path> files = Files.newDirectoryStream(kd, "*.yml")) {
                    for (Path f : files) {
                        YamlConfiguration y = YamlConfiguration.loadConfiguration(f.toFile());
                        if (!fp.equals(y.getString("fingerprint"))) Files.deleteIfExists(f);
                    }
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not clean up old kit layouts: " + ex.getMessage());
        }
    }

    private void deleteTree(Path folder) {
        if (!Files.isDirectory(folder)) return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            for (Path f : files) Files.deleteIfExists(f);
            Files.deleteIfExists(folder);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not delete " + folder + ": " + ex.getMessage());
        }
    }
}
