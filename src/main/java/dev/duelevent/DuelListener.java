package dev.duelevent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.Locale;

final class DuelListener implements Listener {

    private final DuelPlugin plugin;
    private final DuelManager duels;
    private final StateManager states;

    DuelListener(DuelPlugin plugin) {
        this.plugin = plugin;
        this.duels = plugin.duels();
        this.states = plugin.states();
    }

    // ------------------------------------------------------------------ movement

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        Player p = e.getPlayer();
        Duel d = duels.duelOf(p.getUniqueId());
        if (d == null) return;
        Location from = e.getFrom();
        Location to = e.getTo();

        if (d.phase == Duel.Phase.COUNTDOWN) {
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                e.setTo(stay(from, to));
            }
            return;
        }
        // Spectators are held inside the whole arena box (they can fly); fighters only need to stay within its walls.
        boolean spectator = !d.isAlive(p.getUniqueId());
        boolean inside = spectator ? d.arena.contains(to) : d.arena.containsHorizontal(to);
        if (!inside) {
            e.setTo(stay(from, to));
        }
    }

    /** The old position, but with the player's new look direction so they can still turn their head. */
    private static Location stay(Location from, Location to) {
        Location back = from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        return back;
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        Duel d = duels.duelOf(p.getUniqueId());
        if (d == null) return;
        PlayerTeleportEvent.TeleportCause cause = e.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.PLUGIN) return; // our own teleports
        boolean inArena = e.getTo() != null && d.arena.contains(e.getTo());
        boolean alive = d.isAlive(p.getUniqueId());
        // Spectators may hop to players through the spectator menu, as long as it stays inside the arena.
        if (cause == PlayerTeleportEvent.TeleportCause.SPECTATE && !alive && inArena) return;
        boolean pearl = cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT;
        if (pearl && alive && d.phase == Duel.Phase.FIGHTING && inArena) return;
        e.setCancelled(true);
    }

    // ------------------------------------------------------------------ damage and deaths

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity damager = e.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) damager = shooter;
        if (!(damager instanceof Player attacker)) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        Duel attackerDuel = duels.duelOf(attacker.getUniqueId());
        // Only players in the same duel can hurt each other.
        if (attackerDuel != duels.duelOf(victim.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (attackerDuel == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("rules.friendly-fire", false)
                && attackerDuel.teamOf(attacker.getUniqueId()) == attackerDuel.teamOf(victim.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = duels.duelOf(p.getUniqueId());
        if (d == null) return;
        if (d.phase != Duel.Phase.FIGHTING || !d.isAlive(p.getUniqueId())) {
            e.setCancelled(true);
            return;
        }
        if (e.isCancelled()) {
            // Something else (WorldGuard's no-PvP flag, invincible, ...) cancelled this. If the duel's own
            // rules allow it, let it through: duelists have their own rules inside the arena.
            if (!overrideOn() || !allowedByDuelRules(e, d, p)) return;
            e.setCancelled(false);
        }
        if (p.getHealth() - e.getFinalDamage() > 0) return;
        if (hasTotem(p)) return; // let the totem do its thing
        // Don't actually kill them: eliminate them instead, so no death screen or item loss.
        e.setCancelled(true);
        duels.onLethal(d, p);
    }

    /** Would the duel's own rules have allowed this damage? (Same duel, attacker alive, friendly fire respected.) */
    private boolean allowedByDuelRules(EntityDamageEvent e, Duel d, Player victim) {
        if (!(e instanceof EntityDamageByEntityEvent byEntity)) return true;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Entity shooter) damager = shooter;
        if (!(damager instanceof Player attacker)) return true;
        if (duels.duelOf(attacker.getUniqueId()) != d || !d.isAlive(attacker.getUniqueId())) return false;
        if (attacker.getUniqueId().equals(victim.getUniqueId())) return true;
        return plugin.getConfig().getBoolean("rules.friendly-fire", false)
                || d.teamOf(attacker.getUniqueId()) != d.teamOf(victim.getUniqueId());
    }

    private static boolean hasTotem(Player p) {
        return p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING
                || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        duels.onDeath(e);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        duels.onRespawn(e);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.requests().playerQuit(p);
        plugin.menus().forget(p.getUniqueId());
        plugin.kitEdits().forget(p.getUniqueId());
        duels.onQuit(p);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!states.hasPending(p.getUniqueId())) return;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> duels.restoreIfIdle(p), 10L);
    }

    // ------------------------------------------------------------------ building rules

    /** True if this block change should be blocked. */
    private boolean denyBlock(Player p, Location loc) {
        Duel d = duels.duelOf(p.getUniqueId());
        if (d != null) {
            return d.phase != Duel.Phase.FIGHTING || !d.isAlive(p.getUniqueId()) || !d.arena.contains(loc);
        }
        if (p.hasPermission("duelevent.bypass")) return false;
        return duels.protectedArenaAt(loc) != null; // outsiders can't touch a running arena (or any arena, with idle protection)
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (denyBlock(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (denyBlock(e.getPlayer(), e.getBlockPlaced().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (denyBlock(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (denyBlock(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent e) {
        handleExplosion(e, e.getLocation(), e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent e) {
        handleExplosion(e, e.getBlock().getLocation(), e.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplosionPrime(ExplosionPrimeEvent e) {
        if (e.isCancelled() && overrideOn() && duels.arenaInUseAt(e.getEntity().getLocation()) != null) {
            e.setCancelled(false);
        }
    }

    /**
     * An explosion inside a running arena only breaks blocks of that arena (and isn't blocked by other
     * protection plugins). Any other explosion can't touch a protected arena (a running one, or any
     * arena when idle protection is on).
     */
    private void handleExplosion(Cancellable event, Location origin, List<Block> blocks) {
        if (duels.isEmpty() && !duels.idleProtection()) return;
        Arena running = duels.arenaInUseAt(origin);
        if (running != null) {
            if (event.isCancelled() && overrideOn()) event.setCancelled(false);
            if (event.isCancelled()) return;
            blocks.removeIf(b -> !running.contains(b.getLocation()));
        } else if (!event.isCancelled()) {
            blocks.removeIf(b -> duels.protectedArenaAt(b.getLocation()) != null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        crossing(e.getBlock().getLocation(), e.getToBlock().getLocation(), e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent e) {
        crossing(e.getSource().getLocation(), e.getBlock().getLocation(), e);
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        if (e.getIgnitingBlock() == null) return;
        crossing(e.getIgnitingBlock().getLocation(), e.getBlock().getLocation(), e);
    }

    /**
     * Stops water, lava and fire from spreading out of a running arena, and (with idle protection on)
     * from flowing or spreading into or inside an arena that has no duel.
     */
    private void crossing(Location from, Location to, Cancellable event) {
        if (duels.isEmpty() && !duels.idleProtection()) return;
        Arena running = duels.arenaInUseAt(from);
        if (running != null && !running.contains(to)) {
            event.setCancelled(true);
            return;
        }
        if (duels.idleLocked(to)) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent e) {
        if (duels.isEmpty() && !duels.idleProtection()) return;
        BlockFace dir = e.getDirection();
        Block head = e.getBlock().getRelative(dir);
        Arena running = duels.arenaInUseAt(e.getBlock().getLocation());
        if (running != null) {
            if (!running.contains(head.getLocation())) {
                e.setCancelled(true);
                return;
            }
            for (Block moved : e.getBlocks()) {
                if (!running.contains(moved.getRelative(dir).getLocation())) {
                    e.setCancelled(true);
                    return;
                }
            }
        }
        if (duels.idleLocked(head.getLocation())) {
            e.setCancelled(true);
            return;
        }
        for (Block moved : e.getBlocks()) {
            if (duels.idleLocked(moved.getLocation()) || duels.idleLocked(moved.getRelative(dir).getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (!duels.idleProtection()) return;
        for (Block moved : e.getBlocks()) {
            if (duels.idleLocked(moved.getLocation())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ idle protection
    // With arena.protect-when-idle on, an arena with no duel can't be changed by the usual ways:
    // players, explosions, fluids, fire, growth, decay, mobs and falling blocks. Plugin-made changes
    // (WorldEdit, etc.) can't be stopped here, which is why repair-before-fight is the real safety net.

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onGrow(BlockGrowEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent e) {
        if (!duels.idleProtection()) return;
        e.getBlocks().removeIf(state -> duels.idleLocked(state.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (duels.idleLocked(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() != Action.PHYSICAL) return;
        Block b = e.getClickedBlock();
        if (b == null || (b.getType() != Material.FARMLAND && b.getType() != Material.TURTLE_EGG)) return;
        if (duels.idleLocked(b.getLocation())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ overriding other protection plugins
    // WorldGuard and similar plugins cancel actions inside their regions. Duelists have their own rules, so
    // for a player who is fighting in a running duel, inside that duel's arena, these handlers lift such
    // cancellations (only for what the duel's own rules allow). Other players stay bound by those plugins.
    // Turn this off with rules.override-protection-plugins in config.yml.

    private boolean overrideOn() {
        return plugin.getConfig().getBoolean("rules.override-protection-plugins", true);
    }

    /** True if this player is fighting in a running duel and the location is inside their arena. */
    private boolean fighterInArena(Player p, Location loc) {
        Duel d = duels.duelOf(p.getUniqueId());
        return d != null && d.phase == Duel.Phase.FIGHTING && d.isAlive(p.getUniqueId()) && d.arena.contains(loc);
    }

    /** True if both locations are inside the same running arena. */
    private boolean withinRunningArena(Location from, Location to) {
        Arena a = duels.arenaInUseAt(from);
        return a != null && a.contains(to);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideBreak(BlockBreakEvent e) {
        if (e.isCancelled() && overrideOn() && fighterInArena(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overridePlace(BlockPlaceEvent e) {
        if (e.isCancelled() && overrideOn() && fighterInArena(e.getPlayer(), e.getBlockPlaced().getLocation())) {
            e.setCancelled(false);
            e.setBuild(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideBucketEmpty(PlayerBucketEmptyEvent e) {
        if (e.isCancelled() && overrideOn() && fighterInArena(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideBucketFill(PlayerBucketFillEvent e) {
        if (e.isCancelled() && overrideOn() && fighterInArena(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideInteract(PlayerInteractEvent e) {
        if (!overrideOn()) return;
        Block clicked = e.getClickedBlock();
        Location where = clicked != null ? clicked.getLocation() : e.getPlayer().getLocation();
        if (!fighterInArena(e.getPlayer(), where)) return;
        if (e.useInteractedBlock() == Event.Result.DENY) e.setUseInteractedBlock(Event.Result.DEFAULT);
        if (e.useItemInHand() == Event.Result.DENY) e.setUseItemInHand(Event.Result.DEFAULT);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideIgnite(BlockIgniteEvent e) {
        if (!e.isCancelled() || !overrideOn()) return;
        Location target = e.getBlock().getLocation();
        if (e.getPlayer() != null) {
            if (fighterInArena(e.getPlayer(), target)) e.setCancelled(false);
        } else {
            Location source = e.getIgnitingBlock() != null ? e.getIgnitingBlock().getLocation() : target;
            if (withinRunningArena(source, target)) e.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideBurn(BlockBurnEvent e) {
        Location l = e.getBlock().getLocation();
        if (e.isCancelled() && overrideOn() && withinRunningArena(l, l)) e.setCancelled(false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideFlow(BlockFromToEvent e) {
        if (e.isCancelled() && overrideOn()
                && withinRunningArena(e.getBlock().getLocation(), e.getToBlock().getLocation())) {
            e.setCancelled(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideSpread(BlockSpreadEvent e) {
        if (e.isCancelled() && overrideOn()
                && withinRunningArena(e.getSource().getLocation(), e.getBlock().getLocation())) {
            e.setCancelled(false);
        }
    }

    /** Our own teleports into and out of the arena must not be blocked by region entry/exit rules. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void overrideTeleport(PlayerTeleportEvent e) {
        if (e.isCancelled() && overrideOn() && e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                && duels.duelOf(e.getPlayer().getUniqueId()) != null) {
            e.setCancelled(false);
        }
    }

    // ------------------------------------------------------------------ item and command rules

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (plugin.getConfig().getBoolean("rules.allow-item-drops", false)) return;
        if (duels.duelOf(e.getPlayer().getUniqueId()) != null) e.setCancelled(true);
    }

    /** Blocks ender chests so kit items can't be stashed and kept after the duel. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (duels.duelOf(p.getUniqueId()) == null) return;
        if (e.getInventory().getType() == InventoryType.ENDER_CHEST) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (duels.duelOf(p.getUniqueId()) == null || p.hasPermission("duelevent.bypass")) return;
        String raw = e.getMessage();
        if (raw.length() < 2) return;
        String label = raw.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (label.equals("duel") || label.equals("ff") || label.equals("forfeit")) return; // always allowed
        List<String> allowed = plugin.getConfig().getStringList("rules.allowed-commands");
        if (allowed.contains(label)) return;
        e.setCancelled(true);
        Msg.send(p, Msg.PREFIX + "<red>You can't use commands during a duel. Use <white>/ff</white> to vote to forfeit.");
    }
}
