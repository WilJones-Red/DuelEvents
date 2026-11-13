package dev.duelevent;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A duel a player is putting together in the menu, before the invites are sent. Players can go on either team. */
final class Setup {

    final UUID creator;
    final List<UUID> blue = new ArrayList<>();
    final List<UUID> red = new ArrayList<>();
    private final Map<UUID, String> names = new HashMap<>();
    /** Kit name, or null if there are no kits. */
    String kit;
    /** Arena name, or null for any free arena that fits. */
    String arena;

    Setup(Player creator, String kit) {
        this.creator = creator.getUniqueId();
        this.kit = kit;
        add(true, creator);
    }

    int total() {
        return blue.size() + red.size();
    }

    boolean has(UUID id) {
        return blue.contains(id) || red.contains(id);
    }

    String nameOf(UUID id) {
        String n = names.get(id);
        return n == null ? "?" : n;
    }

    void add(boolean blueSide, Player p) {
        (blueSide ? blue : red).add(p.getUniqueId());
        names.put(p.getUniqueId(), p.getName());
    }

    void remove(UUID id) {
        blue.remove(id);
        red.remove(id);
    }

    String format() {
        return blue.size() + "v" + red.size();
    }

    /** Why a player can't be added to that side, or null if they can. */
    String canAdd(boolean blueSide, int maxTotal, int maxPerSide) {
        if (total() >= maxTotal) return "The limit is " + maxTotal + " players in total.";
        if ((blueSide ? blue : red).size() >= maxPerSide) {
            return "The menu fits at most " + maxPerSide + " players per team.";
        }
        return null;
    }
}
