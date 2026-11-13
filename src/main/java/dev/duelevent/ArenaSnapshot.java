package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saves and restores the block layout of an arena. Work is spread across ticks so even a large
 * arena doesn't freeze the server. Block order is x fastest, then z, then y.
 */
final class ArenaSnapshot {

    private static final int MAGIC = 0x44554C31; // "DUL1"

    private ArenaSnapshot() {
    }

    /** done receives null on success or an error message. */
    static void capture(DuelPlugin plugin, Arena arena, Path file, int perTick, long maxVolume, Consumer<String> done) {
        World world = arena.world();
        if (world == null) {
            done.accept("The arena's world is not loaded.");
            return;
        }
        final long volume = arena.volume();
        if (volume > maxVolume) {
            done.accept("That region is " + volume + " blocks, over the limit of " + maxVolume + " (see regen.max-volume).");
            return;
        }
        final int minX = arena.minX();
        final int minY = arena.minY();
        final int minZ = arena.minZ();
        final int sx = arena.sizeX();
        final int sy = arena.sizeY();
        final int sz = arena.sizeZ();
        final char[] indices = new char[(int) volume];
        final Map<BlockData, Integer> ids = new HashMap<>();
        final List<String> palette = new ArrayList<>();

        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                try {
                    int end = (int) Math.min(volume, (long) i + perTick);
                    for (; i < end; i++) {
                        int x = minX + (i % sx);
                        int z = minZ + ((i / sx) % sz);
                        int y = minY + (i / (sx * sz));
                        BlockData data = world.getBlockData(x, y, z);
                        Integer id = ids.get(data);
                        if (id == null) {
                            id = palette.size();
                            if (id >= Character.MAX_VALUE) {
                                cancel();
                                done.accept("Too many different block types in that region.");
                                return;
                            }
                            ids.put(data, id);
                            palette.add(data.getAsString());
                        }
                        indices[i] = (char) id.intValue();
                    }
                    if (i >= volume) {
                        cancel();
                        write(file, sx, sy, sz, palette, indices);
                        done.accept(null);
                    }
                } catch (Exception ex) {
                    cancel();
                    plugin.getLogger().severe("Snapshot of arena " + arena.name + " failed: " + ex);
                    done.accept(String.valueOf(ex.getMessage()));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    /** Puts every block that differs from the snapshot back. done receives null or an error message. */
    static void restore(DuelPlugin plugin, Arena arena, Path file, int perTick, Consumer<String> done) {
        World world = arena.world();
        if (world == null) {
            done.accept("The arena's world is not loaded.");
            return;
        }
        final int minX = arena.minX();
        final int minY = arena.minY();
        final int minZ = arena.minZ();
        final int sx = arena.sizeX();
        final int sy = arena.sizeY();
        final int sz = arena.sizeZ();
        final long volume = arena.volume();

        final BlockData[] palette;
        final char[] indices;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readInt() != MAGIC) {
                done.accept("Snapshot file is not valid.");
                return;
            }
            if (in.readInt() != sx || in.readInt() != sy || in.readInt() != sz) {
                done.accept("Snapshot size doesn't match the arena region. Run /duel arena snapshot again.");
                return;
            }
            int paletteSize = in.readInt();
            palette = new BlockData[paletteSize];
            for (int p = 0; p < paletteSize; p++) {
                palette[p] = Bukkit.createBlockData(in.readUTF());
            }
            indices = new char[(int) volume];
            for (int n = 0; n < indices.length; n++) {
                indices[n] = in.readChar();
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Could not read snapshot for arena " + arena.name + ": " + ex);
            done.accept(String.valueOf(ex.getMessage()));
            return;
        }

        new BukkitRunnable() {
            int i = 0;
            long changed = 0;

            @Override
            public void run() {
                try {
                    int end = (int) Math.min(volume, (long) i + perTick);
                    for (; i < end; i++) {
                        int x = minX + (i % sx);
                        int z = minZ + ((i / sx) % sz);
                        int y = minY + (i / (sx * sz));
                        BlockData want = palette[indices[i]];
                        if (!world.getBlockData(x, y, z).equals(want)) {
                            world.getBlockAt(x, y, z).setBlockData(want, false);
                            changed++;
                        }
                    }
                    if (i >= volume) {
                        cancel();
                        plugin.getLogger().info("Arena " + arena.name + " regenerated (" + changed + " blocks restored).");
                        done.accept(null);
                    }
                } catch (Exception ex) {
                    cancel();
                    plugin.getLogger().severe("Regenerating arena " + arena.name + " failed: " + ex);
                    done.accept(String.valueOf(ex.getMessage()));
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private static void write(Path file, int sx, int sy, int sz, List<String> palette, char[] indices) throws IOException {
        Files.createDirectories(file.getParent());
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeInt(sx);
            out.writeInt(sy);
            out.writeInt(sz);
            out.writeInt(palette.size());
            for (String s : palette) out.writeUTF(s);
            for (char c : indices) out.writeChar(c);
        }
    }
}
