package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** A location that stores the world by name, so it survives worlds loading late. */
record Point(String world, double x, double y, double z, float yaw, float pitch) {

    static Point of(Location l) {
        return new Point(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    static Point ofBlock(Location l) {
        return new Point(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), 0f, 0f);
    }

    static Point read(ConfigurationSection s) {
        if (s == null) return null;
        String w = s.getString("world");
        if (w == null) return null;
        return new Point(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    void write(ConfigurationSection s) {
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("pitch", (double) pitch);
    }

    World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    Location toLocation() {
        World w = bukkitWorld();
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    int bx() {
        return (int) Math.floor(x);
    }

    int by() {
        return (int) Math.floor(y);
    }

    int bz() {
        return (int) Math.floor(z);
    }

    String pretty() {
        return String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", x, y, z);
    }
}
