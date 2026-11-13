package dev.duelevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DuelManager {

    private final DuelPlugin plugin;
    private final ArenaManager arenas;
    private final KitManager kits;
    private final StateManager states;
    private final StatsManager stats;

    private final Map<UUID, Duel> byPlayer = new HashMap<>();
    private final Set<Duel> active = new LinkedHashSet<>();
    /** Players whose duel is waiting on the pre-fight arena repair. */
    private final Set<UUID> reserved = new HashSet<>();
    private boolean idleProtection;
    private final Trims trims;
    /** When each player's last player-run duel with a winner ended (for player-duels.cooldown-seconds). */
    private final Map<UUID, Long> lastDuelEnd = new HashMap<>();

    DuelManager(DuelPlugin plugin, ArenaManager arenas, KitManager kits, StateManager states, StatsManager stats) {
        this.plugin = plugin;
        this.arenas = arenas;
        this.kits = kits;
        this.states = states;
        this.stats = stats;
        this.trims = new Trims(plugin);
        reloadSettings();
    }

    /** Re-reads the settings that are checked very often (called on enable and /duel reload). */
    void reloadSettings() {
        idleProtection = plugin.getConfig().getBoolean("arena.protect-when-idle", false);
    }

    boolean idleProtection() {
        return idleProtection;
    }

    private boolean repairBefore() {
        return plugin.getConfig().getBoolean("arena.repair-before-fight", true);
    }

    private boolean repairAfter() {
        return plugin.getConfig().getBoolean("arena.repair-after-fight", false);
    }

    // ------------------------------------------------------------------ lookups

    Duel duelOf(UUID id) {
        return byPlayer.get(id);
    }

    Collection<Duel> all() {
        return List.copyOf(active);
    }

    boolean isEmpty() {
        return active.isEmpty();
    }

    /** The arena of any running duel that contains this location, or null. */
    Arena arenaInUseAt(Location l) {
        if (active.isEmpty()) return null;
        for (Duel d : active) {
            if (d.arena.contains(l)) return d.arena;
        }
        return null;
    }

    /** The arena that should currently be protected from outside changes here: a running one, or any with idle protection on. */
    Arena protectedArenaAt(Location l) {
        if (idleProtection) {
            for (Arena a : arenas.all()) {
                if (a.contains(l)) return a;
            }
            return null;
        }
        return arenaInUseAt(l);
    }

    /** True where blocks must not change: inside an arena that isn't hosting a duel, with idle protection on. */
    boolean idleLocked(Location l) {
        if (!idleProtection) return false;
        for (Arena a : arenas.all()) {
            if (a.contains(l)) return a.state != Arena.State.IN_USE;
        }
        return false;
    }

    // ------------------------------------------------------------------ messaging helpers

    private static void fail(CommandSender requester, String error) {
        Msg.send(requester, Msg.PREFIX + "<red>" + Msg.esc(error));
    }

    private void tell(Team team, String text) {
        for (UUID id : team.members) {
            if (team.left.contains(id)) continue;
            Player p = Bukkit.getPlayer(id);
            if (p != null) Msg.send(p, Msg.PREFIX + text);
        }
    }

    private void announce(Duel duel, String text) {
        tell(duel.team1, text);
        tell(duel.team2, text);
    }

    // ------------------------------------------------------------------ starting

    /** Largest number of players a duel can have in total (max-team-size in config.yml). */
    int maxPlayers() {
        return Math.max(2, plugin.getConfig().getInt("max-team-size", 16));
    }

    /** True if this player is in a duel or is waiting for one to start. */
    boolean isBusy(UUID id) {
        return byPlayer.containsKey(id) || reserved.contains(id);
    }

    /** Seconds before this player may send another duel request (0 if they can now). */
    long cooldownLeft(UUID id) {
        int cooldown = plugin.getConfig().getInt("player-duels.cooldown-seconds", 0);
        Long last = lastDuelEnd.get(id);
        if (cooldown <= 0 || last == null) return 0;
        long left = last + cooldown * 1000L - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 999) / 1000;
    }

    /** True if at least one ready arena can hold this many players. */
    boolean anyArenaFits(int players) {
        for (Arena a : arenas.all()) {
            if (a.isReady() && (a.maxPlayers <= 0 || a.maxPlayers >= players)) return true;
        }
        return false;
    }

    /** Name of the kit used when none is chosen (default-kit, or the only kit), else the first kit, else null. */
    String defaultKitName() {
        Kit k = defaultKit();
        if (k != null) return k.name();
        return kits.names().stream().findFirst().orElse(null);
    }

    /**
     * Starts a duel and tells the requester how it went. With repair-before-fight on, the arena is
     * rebuilt first and the duel begins as soon as that finishes.
     */
    void start(CommandSender requester, List<Player> team1, List<Player> team2, Arena arena, Kit kit) {
        start(requester, team1, team2, arena, kit, false);
    }

    /** playerRun is true for duels set up through the menu; those follow the player-duels settings. */
    void start(CommandSender requester, List<Player> team1, List<Player> team2, Arena arena, Kit kit, boolean playerRun) {
        List<Player> everyone = new ArrayList<>(team1);
        everyone.addAll(team2);

        String err = checkPlayers(everyone);
        if (err != null) {
            failStart(requester, everyone, playerRun, err);
            return;
        }
        if (everyone.size() > maxPlayers()) {
            failStart(requester, everyone, playerRun, "A duel can have at most " + maxPlayers() + " players in total.");
            return;
        }
        if (arena == null) {
            arena = firstFreeArena(everyone.size());
            if (arena == null) {
                failStart(requester, everyone, playerRun, "No free, ready arena is available for " + everyone.size() + " players.");
                return;
            }
        }
        if (arena.maxPlayers > 0 && everyone.size() > arena.maxPlayers) {
            failStart(requester, everyone, playerRun, "Arena " + arena.name + " only fits " + arena.maxPlayers + " players.");
            return;
        }
        if (arena.state != Arena.State.IDLE) {
            failStart(requester, everyone, playerRun, "Arena " + arena.name + " is busy.");
            return;
        }
        Arena clash = arenas.overlapping(arena);
        if (clash != null && clash.state != Arena.State.IDLE) {
            failStart(requester, everyone, playerRun, "Arena " + arena.name + " overlaps arena " + clash.name + ", which is in use.");
            return;
        }
        boolean before = repairBefore();
        if (arena.dirty && !before && repairAfter()) {
            failStart(requester, everyone, playerRun, "Arena " + arena.name + " still needs repairing. Run /duel arena regen " + arena.name + ".");
            return;
        }
        if (!arena.isReady()) {
            failStart(requester, everyone, playerRun, "Arena " + arena.name + " isn't fully set up (needs pos1, pos2, spawn1, spawn2 and a snapshot).");
            return;
        }
        if (arena.spawn1.toLocation() == null || arena.spawn2.toLocation() == null) {
            failStart(requester, everyone, playerRun, "Arena " + arena.name + "'s world is not loaded.");
            return;
        }
        if (kit == null) kit = defaultKit();
        if (kit == null) {
            failStart(requester, everyone, playerRun, "No kit chosen. Create one with /duel kit set <name>, or set default-kit in config.yml.");
            return;
        }
        try {
            kit.contents();
        } catch (RuntimeException ex) {
            failStart(requester, everyone, playerRun, "Kit " + kit.name() + " could not be loaded: " + ex.getMessage());
            return;
        }

        final Arena chosen = arena;
        final Kit chosenKit = kit;

        if (!before) {
            finishStart(requester, team1, team2, chosen, chosenKit, playerRun);
            return;
        }

        // Rebuild the arena first, so anything that happened to it since the last duel doesn't matter.
        final List<UUID> ids = new ArrayList<>();
        for (Player p : everyone) ids.add(p.getUniqueId());
        reserved.addAll(ids);
        Msg.send(requester, Msg.PREFIX + "<gray>Repairing arena " + chosen.name + " before the duel starts...");
        cleanEntities(chosen);
        arenas.regenerate(chosen, repairErr -> {
            ids.forEach(reserved::remove);
            if (repairErr != null) {
                failStart(requester, everyone, playerRun,
                        "Arena " + chosen.name + " could not be repaired (" + repairErr + "), so the duel was not started.");
                return;
            }
            finishStart(requester, team1, team2, chosen, chosenKit, playerRun);
        });
    }

    /** Tells the requester why the duel didn't start; for player-run duels everyone involved hears it. */
    private void failStart(CommandSender requester, List<Player> everyone, boolean playerRun, String error) {
        fail(requester, error);
        if (!playerRun) return;
        for (Player p : everyone) {
            if (requester instanceof Player r && r.getUniqueId().equals(p.getUniqueId())) continue;
            if (p.isOnline()) fail(p, "The duel could not start: " + error);
        }
    }

    private void finishStart(CommandSender requester, List<Player> team1, List<Player> team2, Arena arena, Kit kit, boolean playerRun) {
        List<Player> everyone = new ArrayList<>(team1);
        everyone.addAll(team2);
        String err = checkPlayers(everyone); // players may have changed while the arena was being repaired
        if (err == null) err = begin(team1, team2, arena, kit, playerRun);
        if (err != null) {
            failStart(requester, everyone, playerRun, err);
        } else {
            Msg.send(requester, Msg.PREFIX + "<green>Duel started: " + Msg.esc(byPlayer.get(team1.get(0).getUniqueId()).title())
                    + " on " + arena.name + ".");
        }
    }

    /** Returns an error message, or null if all these players are free to start a duel. */
    String checkPlayers(List<Player> players) {
        Set<UUID> seen = new HashSet<>();
        for (Player p : players) {
            UUID id = p.getUniqueId();
            if (!seen.add(id)) return p.getName() + " is listed more than once.";
            if (!p.isOnline()) return p.getName() + " is not online.";
            if (byPlayer.containsKey(id) || reserved.contains(id)) return p.getName() + " is already in a duel.";
            if (plugin.kitEdits().isEditing(id)) return p.getName() + " is editing a kit.";
            if (states.hasPending(id)) {
                return p.getName() + " still has saved items waiting to be restored. Wait a moment or have them rejoin.";
            }
            if (p.isDead()) return p.getName() + " is dead.";
        }
        return null;
    }

    /** Saves everyone, kits them, teleports them in and starts the countdown. Returns an error or null. */
    private String begin(List<Player> team1, List<Player> team2, Arena arena, Kit kit, boolean playerRun) {
        if (arena.state != Arena.State.IDLE) return "Arena " + arena.name + " is busy.";
        Location s1 = arena.spawn1.toLocation();
        Location s2 = arena.spawn2.toLocation();
        if (s1 == null || s2 == null) return "Arena " + arena.name + "'s world is not loaded.";

        List<Player> everyone = new ArrayList<>(team1);
        everyone.addAll(team2);

        // Each player gets their own saved arrangement of the kit if they have one, plus their team's armor trims.
        List<ItemStack[]> kitCopies = new ArrayList<>();
        try {
            for (int i = 0; i < everyone.size(); i++) {
                ItemStack[] contents = plugin.layouts().load(everyone.get(i).getUniqueId(), kit);
                if (contents == null) contents = kit.contents();
                trims.apply(contents, i < team1.size());
                kitCopies.add(contents);
            }
        } catch (RuntimeException ex) {
            return "Kit " + kit.name() + " could not be loaded: " + ex.getMessage();
        }

        // Never touch anyone's inventory until everyone's original state is safely on disk.
        List<Player> saved = new ArrayList<>();
        for (Player p : everyone) {
            if (!states.save(p)) {
                for (Player q : saved) states.discard(q.getUniqueId());
                return "Could not save " + p.getName() + "'s inventory, so nothing was changed.";
            }
            saved.add(p);
        }

        Duel duel = new Duel(arena, kit, new Team(team1), new Team(team2), playerRun);
        arena.state = Arena.State.IN_USE;
        arena.dirty = true;
        arenas.save();
        active.add(duel);
        for (Player p : everyone) byPlayer.put(p.getUniqueId(), duel);

        // Anyone in this duel can't be part of other pending duel requests any more.
        List<UUID> ids = new ArrayList<>();
        for (Player p : everyone) ids.add(p.getUniqueId());
        plugin.requests().playersJoinedDuel(ids);

        List<Location> spots1 = cluster(arena, s1, team1.size());
        List<Location> spots2 = cluster(arena, s2, team2.size());
        int k = 0;
        for (int i = 0; i < team1.size(); i++) prepare(team1.get(i), kitCopies.get(k++), spots1.get(i));
        for (int i = 0; i < team2.size(); i++) prepare(team2.get(i), kitCopies.get(k++), spots2.get(i));
        startCountdown(duel);
        return null;
    }

    /**
     * Spots for one team, laid out as a grid starting at the spawn block: first the spawn itself, then the
     * blocks to its right, then the next row behind it, and so on. A spot that is outside the arena or
     * blocked is replaced by the spawn point itself.
     */
    private List<Location> cluster(Arena arena, Location spawn, int count) {
        int q = Math.floorMod(Math.round(spawn.getYaw() / 90f), 4);
        int fx = switch (q) {
            case 1 -> -1;
            case 3 -> 1;
            default -> 0;
        };
        int fz = switch (q) {
            case 0 -> 1;
            case 2 -> -1;
            default -> 0;
        };
        int rx = -fz;
        int rz = fx;
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        List<Location> spots = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int col = i % columns;
            int row = i / columns;
            Location l = spawn.clone().add(rx * col - fx * row, 0, rz * col - fz * row);
            if (i > 0) {
                Block feet = l.getBlock();
                boolean blocked = !feet.isPassable() || !feet.getRelative(0, 1, 0).isPassable();
                if (blocked || !arena.contains(l)) l = spawn.clone();
            }
            spots.add(l);
        }
        return spots;
    }

    private Location teamSpawn(Duel d, UUID id) {
        Point p = d.team1.has(id) ? d.arena.spawn1 : d.arena.spawn2;
        Location l = p == null ? null : p.toLocation();
        return l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private Arena firstFreeArena(int players) {
        for (Arena a : arenas.all()) {
            if (fits(a, players) && a.state == Arena.State.IDLE && !a.dirty && a.isReady()) return a;
        }
        // No clean arena: with repair-before-fight on, a used one is fine because it gets rebuilt first.
        if (repairBefore()) {
            for (Arena a : arenas.all()) {
                if (fits(a, players) && a.state == Arena.State.IDLE && a.isReady()) return a;
            }
        }
        return null;
    }

    private static boolean fits(Arena a, int players) {
        return a.maxPlayers <= 0 || a.maxPlayers >= players;
    }

    private Kit defaultKit() {
        String name = plugin.getConfig().getString("default-kit", "");
        if (name != null && !name.isBlank()) {
            Kit k = kits.get(name);
            if (k != null) return k;
        }
        return kits.only();
    }

    private void prepare(Player p, ItemStack[] contents, Location spawn) {
        p.closeInventory();
        p.leaveVehicle();
        p.setGameMode(GameMode.SURVIVAL);
        p.setAllowFlight(false);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());

        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setContents(Arrays.copyOf(contents, Math.min(contents.length, inv.getSize())));

        p.setHealth(StateManager.maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setInvulnerable(true);
        p.teleport(spawn);
        p.updateInventory();
    }

    private void startCountdown(Duel duel) {
        final int seconds = Math.max(0, plugin.getConfig().getInt("countdown-seconds", 5));
        duel.task = new BukkitRunnable() {
            int left = seconds;

            @Override
            public void run() {
                if (!active.contains(duel) || duel.phase != Duel.Phase.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (left > 0) {
                    title(duel, Component.text(String.valueOf(left), NamedTextColor.YELLOW),
                            Component.text(duel.title(), NamedTextColor.GRAY));
                    left--;
                    return;
                }
                duel.phase = Duel.Phase.FIGHTING;
                for (UUID id : duel.players()) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null) p.setInvulnerable(false);
                }
                title(duel, Component.text("FIGHT!", NamedTextColor.RED), Component.empty());
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void title(Duel duel, Component main, Component sub) {
        Title t = Title.title(main, sub,
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100)));
        for (UUID id : duel.players()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.showTitle(t);
        }
    }

    // ------------------------------------------------------------------ forfeit vote

    /** A living player votes for their team to forfeit. Returns an error message, or null. */
    String forfeitVote(Player p) {
        UUID id = p.getUniqueId();
        Duel d = byPlayer.get(id);
        if (d == null) return "You are not in a duel.";
        if (d.phase != Duel.Phase.FIGHTING) return "You can't forfeit right now.";
        Team t = d.teamOf(id);
        if (!t.alive.contains(id)) return "You've been eliminated, so only your living teammates can vote.";
        if (t.ffVotes.contains(id)) return "You already voted to forfeit.";

        t.ffVotes.add(id);
        t.ffVotes.retainAll(t.alive);
        if (t.ffVotes.containsAll(t.alive)) {
            forfeitTeam(d, t);
            return null;
        }

        int timeout = Math.max(5, plugin.getConfig().getInt("forfeit.vote-timeout-seconds", 30));
        if (t.ffTask == null) {
            t.ffTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                t.ffTask = null;
                if (!t.ffVotes.isEmpty()) {
                    t.ffVotes.clear();
                    tell(t, "<gray>The forfeit vote expired.");
                }
            }, timeout * 20L);
        }
        // Only the voter's own team sees this.
        tell(t, "<yellow>" + Msg.esc(p.getName()) + "</yellow> voted to forfeit (" + t.ffVotes.size() + "/" + t.alive.size()
                + "). Type <white>/ff</white> to agree. The vote expires in " + timeout + "s.");
        return null;
    }

    private void forfeitTeam(Duel d, Team t) {
        announce(d, "<yellow>" + Msg.esc(t.joinedNames()) + "</yellow> forfeited.");
        finish(d, d.other(t));
    }

    private void clearVote(Team t) {
        t.ffVotes.clear();
        if (t.ffTask != null) {
            t.ffTask.cancel();
            t.ffTask = null;
        }
    }

    // ------------------------------------------------------------------ eliminations and finishing

    /** A living player took lethal damage: they are eliminated and become a spectator instead of dying. */
    void onLethal(Duel d, Player p) {
        UUID id = p.getUniqueId();
        Team t = d.teamOf(id);
        if (t == null || !t.alive.remove(id)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player online = Bukkit.getPlayer(id);
            if (online != null && active.contains(d)) toSpectator(d, online);
        });
        afterElimination(d, t, p.getName());
    }

    private void toSpectator(Duel d, Player p) {
        p.setHealth(StateManager.maxHealth(p));
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setGameMode(GameMode.SPECTATOR);
        if (!d.arena.contains(p.getLocation())) p.teleport(teamSpawn(d, p.getUniqueId()));
    }

    private void afterElimination(Duel d, Team t, String name) {
        int left = t.alive.size();
        announce(d, "<red>" + Msg.esc(name) + "</red> was eliminated" + (left > 0 ? " (" + left + " left on their team)" : "") + ".");
        if (left == 0) {
            finish(d, d.other(t));
            return;
        }
        t.ffVotes.retainAll(t.alive);
        if (t.ffVotes.isEmpty()) {
            clearVote(t);
        } else if (t.ffVotes.containsAll(t.alive)) {
            forfeitTeam(d, t);
        }
    }

    /** Declares the winning team, shows the result, then ends the duel after the configured delay. */
    void finish(Duel duel, Team winner) {
        if (duel.phase == Duel.Phase.ENDING || !active.contains(duel)) return;
        duel.phase = Duel.Phase.ENDING;
        duel.winner = winner;
        if (duel.task != null) duel.task.cancel();
        clearVote(duel.team1);
        clearVote(duel.team2);
        Team loser = duel.other(winner);

        for (UUID id : duel.players()) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || duel.awaitingRespawn.contains(id)) continue;
            p.setInvulnerable(true);
            boolean won = winner.has(id);
            p.showTitle(Title.title(
                    Component.text(won ? "VICTORY" : "DEFEAT", won ? NamedTextColor.GREEN : NamedTextColor.RED),
                    Component.empty(),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofSeconds(2), Duration.ofMillis(300))));
        }
        if (!duel.playerRun || plugin.getConfig().getBoolean("player-duels.count-wins-on-leaderboard", false)) {
            for (UUID id : winner.members) stats.addWin(id, winner.nameOf(id));
        }

        String result = "<yellow>" + Msg.esc(winner.joinedNames()) + "</yellow> defeated <yellow>"
                + Msg.esc(loser.joinedNames()) + "</yellow>!";
        boolean broadcast = duel.playerRun
                ? plugin.getConfig().getBoolean("player-duels.broadcast-result", false)
                : plugin.getConfig().getBoolean("broadcast-result", true);
        if (broadcast) {
            Bukkit.broadcast(Msg.parse(Msg.PREFIX + result));
        } else if (duel.playerRun) {
            announce(duel, result); // only the people in the duel hear about it
        }

        long delay = Math.max(0, plugin.getConfig().getInt("end-delay-seconds", 3)) * 20L;
        duel.task = Bukkit.getScheduler().runTaskLater(plugin, () -> endDuel(duel), delay);
    }

    /** Ends a duel without a winner (operator stop, or a player left during the countdown). */
    void stopDuel(Duel duel, String reason) {
        if (!active.contains(duel)) return;
        duel.phase = Duel.Phase.ENDING;
        announce(duel, "<red>" + Msg.esc(reason));
        endDuel(duel);
    }

    /** Restores everyone, clears the arena of loose entities and (optionally) starts regeneration. */
    private void endDuel(Duel duel) {
        if (!active.remove(duel)) return;
        if (duel.task != null) duel.task.cancel();
        clearVote(duel.team1);
        clearVote(duel.team2);

        for (UUID id : duel.players()) {
            byPlayer.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null && states.hasPending(id) && !duel.awaitingRespawn.contains(id)) {
                if (states.restore(p)) Msg.send(p, Msg.PREFIX + "<green>Your items and position have been restored.");
            }
        }

        cleanEntities(duel.arena);
        if (repairAfter()) {
            arenas.regenerate(duel.arena, null);
        } else {
            duel.arena.state = Arena.State.IDLE; // stays flagged as damaged until the next pre-fight repair
        }

        if (duel.winner != null && duel.playerRun) {
            long now = System.currentTimeMillis();
            for (UUID id : duel.team1.members) lastDuelEnd.put(id, now);
            for (UUID id : duel.team2.members) lastDuelEnd.put(id, now);
        }
        if (duel.winner != null && (!duel.playerRun || plugin.getConfig().getBoolean("player-duels.run-winner-commands", false))) {
            Team loser = duel.other(duel.winner);
            for (UUID id : duel.winner.members) {
                for (String cmd : plugin.getConfig().getStringList("winner-commands")) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            cmd.replace("{winner}", duel.winner.nameOf(id)).replace("{loser}", loser.joinedNames()));
                }
            }
        }
    }

    private void cleanEntities(Arena arena) {
        World w = arena.world();
        if (w == null) return;
        for (Entity e : w.getNearbyEntities(arena.boundingBox())) {
            if (e instanceof Player) continue;
            if (e instanceof Item || e instanceof Projectile || e instanceof ExperienceOrb
                    || e instanceof TNTPrimed || e instanceof FallingBlock || e instanceof AreaEffectCloud) {
                e.remove();
            }
        }
    }

    // ------------------------------------------------------------------ player events

    /**
     * An eliminated duelist who is watching stops watching: they get their items and position back right away
     * instead of waiting for the duel to end. Returns an error message, or null on success.
     */
    String leaveSpectating(Player p) {
        UUID id = p.getUniqueId();
        Duel d = byPlayer.get(id);
        if (d == null) return "You are not in a duel.";
        Team t = d.teamOf(id);
        if (t == null || t.alive.contains(id)) return "You are still fighting. Use /ff to vote to forfeit.";
        if (d.awaitingRespawn.contains(id)) return "Respawn first, then you can leave.";

        byPlayer.remove(id);
        t.left.add(id);
        t.ffVotes.remove(id);
        if (states.hasPending(id) && !states.restore(p)) {
            byPlayer.put(id, d);
            t.left.remove(id);
            return "Your items could not be restored right now. Try again in a moment.";
        }
        Msg.send(p, Msg.PREFIX + "<green>You left the duel. Your items and position have been restored.");
        return null;
    }

    void onQuit(Player p) {
        UUID id = p.getUniqueId();
        Duel d = byPlayer.get(id);
        if (d == null) return;
        if (d.phase == Duel.Phase.COUNTDOWN) {
            stopDuel(d, p.getName() + " left before the duel started. Duel cancelled.");
        } else if (d.phase == Duel.Phase.FIGHTING) {
            Team t = d.teamOf(id);
            if (t != null && t.alive.remove(id)) afterElimination(d, t, p.getName() + " (left the server)");
        }
        // If the duel is already ending, endDuel() leaves the offline player's saved file for their next join.
    }

    /** Fallback for deaths the damage handler didn't catch (void, /kill, totem pops that still died...). */
    void onDeath(PlayerDeathEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        Duel d = byPlayer.get(id);
        if (d == null) return;
        e.setKeepInventory(true);
        e.getDrops().clear();
        e.setKeepLevel(true);
        e.setDroppedExp(0);
        e.deathMessage(null);
        d.awaitingRespawn.add(id);
        Team t = d.teamOf(id);
        if (d.phase == Duel.Phase.COUNTDOWN) {
            stopDuel(d, p.getName() + " died before the duel started. Duel cancelled.");
        } else if (d.phase == Duel.Phase.FIGHTING && t != null && t.alive.remove(id)) {
            afterElimination(d, t, p.getName());
        }
    }

    void onRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();
        UUID id = player.getUniqueId();
        Duel d = byPlayer.get(id);

        // Died mid-fight but their team is still going: come back as a spectator inside the arena.
        if (d != null && d.awaitingRespawn.contains(id) && d.phase == Duel.Phase.FIGHTING && active.contains(d)) {
            d.awaitingRespawn.remove(id);
            e.setRespawnLocation(teamSpawn(d, id));
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null && active.contains(d)) p.setGameMode(GameMode.SPECTATOR);
            });
            return;
        }

        if (!states.hasPending(id)) return;
        if (d != null && !d.awaitingRespawn.contains(id)) return;
        Location loc = states.savedLocation(id);
        if (loc != null) e.setRespawnLocation(loc);
        byPlayer.remove(id);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null && states.restore(p)) {
                Msg.send(p, Msg.PREFIX + "<green>Your items and position have been restored.");
            }
        });
    }

    /** Called on join (and on enable): gives back anything a player was holding when they left mid-duel. */
    void restoreIfIdle(Player p) {
        UUID id = p.getUniqueId();
        if (!p.isOnline() || byPlayer.containsKey(id) || !states.hasPending(id)) return;
        if (states.restore(p)) Msg.send(p, Msg.PREFIX + "<green>Your items and position have been restored.");
    }

    /** On plugin disable: put every online duelist back right now. Arenas stay flagged and are repaired later. */
    void shutdown() {
        for (Duel d : List.copyOf(active)) {
            if (d.task != null) d.task.cancel();
            clearVote(d.team1);
            clearVote(d.team2);
            for (UUID id : d.players()) {
                byPlayer.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null && states.hasPending(id)) states.restore(p);
            }
        }
        active.clear();
        byPlayer.clear();
    }
}
