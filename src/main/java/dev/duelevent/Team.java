package dev.duelevent;

import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One side of a duel: its players, who is still alive, and the current forfeit vote. */
final class Team {

    final List<UUID> members = new ArrayList<>();
    private final Map<UUID, String> names = new LinkedHashMap<>();
    /** Players still fighting. Eliminated players leave this set but stay in members (they spectate). */
    final Set<UUID> alive = new LinkedHashSet<>();
    final Set<UUID> ffVotes = new HashSet<>();
    /** Eliminated players who used /duel leave. They stay in members (for the result) but are no longer in the duel. */
    final Set<UUID> left = new HashSet<>();
    BukkitTask ffTask;

    Team(List<Player> players) {
        for (Player p : players) {
            members.add(p.getUniqueId());
            names.put(p.getUniqueId(), p.getName());
            alive.add(p.getUniqueId());
        }
    }

    boolean has(UUID id) {
        return names.containsKey(id);
    }

    String nameOf(UUID id) {
        return names.get(id);
    }

    String joinedNames() {
        return String.join(", ", names.values());
    }

    int size() {
        return members.size();
    }
}
