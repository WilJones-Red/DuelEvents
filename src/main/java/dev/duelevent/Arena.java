package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;

final class Arena {

    enum State { IDLE, IN_USE, BUSY }

    final String name;
    Point pos1;
    Point pos2;
    Point spawn1;
    Point spawn2;
    /** True once a baseline snapshot of the region exists on disk. */
    boolean hasSnapshot;
    /** True from the moment a duel starts until regeneration has finished. */
    boolean dirty;
    /** Most players this arena holds at once; 0 means no limit. */
    int maxPlayers;
    State state = State.IDLE;

    Arena(String name) {
        this.name = name;
    }

    boolean hasRegion() {
        return pos1 != null && pos2 != null;
    }

    boolean isReady() {
        return hasRegion() && spawn1 != null && spawn2 != null && hasSnapshot;
    }

    String worldName() {
        return pos1 == null ? null : pos1.world();
    }

    World world() {
        String n = worldName();
        return n == null ? null : Bukkit.getWorld(n);
    }

    int minX() { return Math.min(pos1.bx(), pos2.bx()); }
    int minY() { return Math.min(pos1.by(), pos2.by()); }
    int minZ() { return Math.min(pos1.bz(), pos2.bz()); }
    int maxX() { return Math.max(pos1.bx(), pos2.bx()); }
    int maxY() { return Math.max(pos1.by(), pos2.by()); }
    int maxZ() { return Math.max(pos1.bz(), pos2.bz()); }

    int sizeX() { return maxX() - minX() + 1; }
    int sizeY() { return maxY() - minY() + 1; }
    int sizeZ() { return maxZ() - minZ() + 1; }

    long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    boolean contains(Location l) {
        if (!hasRegion() || l.getWorld() == null || !l.getWorld().getName().equals(pos1.world())) return false;
        int x = l.getBlockX();
        int y = l.getBlockY();
        int z = l.getBlockZ();
        return x >= minX() && x <= maxX() && y >= minY() && y <= maxY() && z >= minZ() && z <= maxZ();
    }

    /** Ignores height, so a player falling out of the bottom isn't pinned in place. */
    boolean containsHorizontal(Location l) {
        if (!hasRegion() || l.getWorld() == null || !l.getWorld().getName().equals(pos1.world())) return false;
        int x = l.getBlockX();
        int z = l.getBlockZ();
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    BoundingBox boundingBox() {
        return new BoundingBox(minX(), minY(), minZ(), maxX() + 1, maxY() + 1, maxZ() + 1);
    }
}
