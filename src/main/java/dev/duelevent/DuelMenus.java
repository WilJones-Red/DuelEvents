package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Opens the menus and remembers each player's half-built duel while they browse. */
final class DuelMenus {

    private final DuelPlugin plugin;
    private final Map<UUID, Setup> setups = new HashMap<>();

    DuelMenus(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    void openMain(Player p) {
        new MainMenu(plugin, p).open(p);
    }

    /** Pick a kit whose layout the player wants to rearrange. */
    void openKitPicker(Player p) {
        new KitPicker(plugin, p, "Choose a kit", null, true,
                kit -> openLayout(p, kit), () -> openMain(p), 0).open(p);
    }

    void openLayout(Player p, Kit kit) {
        try {
            new LayoutEditor(plugin, p, kit).open(p);
        } catch (RuntimeException ex) {
            Msg.send(p, Msg.PREFIX + "<red>Kit " + Msg.esc(kit.name()) + " could not be loaded.");
            plugin.getLogger().warning("Could not open the layout editor for kit " + kit.name() + ": " + ex);
        }
    }

    void openSetup(Player p) {
        Setup setup = setups.computeIfAbsent(p.getUniqueId(), id -> new Setup(p, plugin.duels().defaultKitName()));
        tidy(setup);
        new SetupMenu(plugin, setup).open(p);
    }

    void clearSetup(UUID id) {
        setups.remove(id);
    }

    void forget(UUID id) {
        setups.remove(id);
    }

    /** Drops players who went offline or entered a duel, and fixes a kit or arena that was removed meanwhile. */
    private void tidy(Setup setup) {
        setup.blue.removeIf(this::unavailable);
        setup.red.removeIf(this::unavailable);
        if (setup.kit == null || plugin.kits().get(setup.kit) == null) setup.kit = plugin.duels().defaultKitName();
        if (setup.arena != null && plugin.arenas().get(setup.arena) == null) setup.arena = null;
    }

    private boolean unavailable(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p == null || plugin.duels().isBusy(id);
    }
}
