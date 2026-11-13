package dev.duelevent;

import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class Duel {

    enum Phase { COUNTDOWN, FIGHTING, ENDING }

    final Arena arena;
    final Kit kit;
    /** The blue team: gets the first spawn point and the blue armor trims. */
    final Team team1;
    final Team team2;
    /** True when a player set this duel up through the menu (not an operator with /duel start). */
    final boolean playerRun;

    Phase phase = Phase.COUNTDOWN;
    BukkitTask task;
    Team winner;
    /** Players who died (fallback path) and are waiting to respawn. */
    final Set<UUID> awaitingRespawn = new HashSet<>();

    Duel(Arena arena, Kit kit, Team team1, Team team2, boolean playerRun) {
        this.arena = arena;
        this.kit = kit;
        this.team1 = team1;
        this.team2 = team2;
        this.playerRun = playerRun;
    }

    /** Everyone still in the duel (players who left as spectators are not). */
    List<UUID> players() {
        List<UUID> all = new ArrayList<>();
        for (UUID id : team1.members) {
            if (!team1.left.contains(id)) all.add(id);
        }
        for (UUID id : team2.members) {
            if (!team2.left.contains(id)) all.add(id);
        }
        return all;
    }

    boolean has(UUID id) {
        return team1.has(id) || team2.has(id);
    }

    Team teamOf(UUID id) {
        if (team1.has(id)) return team1;
        if (team2.has(id)) return team2;
        return null;
    }

    Team other(Team t) {
        return t == team1 ? team2 : team1;
    }

    boolean isAlive(UUID id) {
        Team t = teamOf(id);
        return t != null && t.alive.contains(id);
    }

    String nameOf(UUID id) {
        Team t = teamOf(id);
        return t == null ? "?" : t.nameOf(id);
    }

    String title() {
        return team1.joinedNames() + " vs " + team2.joinedNames();
    }
}
