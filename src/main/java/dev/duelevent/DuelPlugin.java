package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;

public final class DuelPlugin extends JavaPlugin {

    private StateManager states;
    private KitManager kits;
    private LayoutManager layouts;
    private ArenaManager arenas;
    private StatsManager stats;
    private DuelManager duels;
    private DuelRequests requests;
    private DuelMenus menus;
    private KitEdits kitEdits;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        states = new StateManager(this);
        layouts = new LayoutManager(this);
        kits = new KitManager(this);
        layouts.purgeStale(kits); // drop layouts of kits that were edited or removed while the server was off
        arenas = new ArenaManager(this);
        stats = new StatsManager(this);
        duels = new DuelManager(this, arenas, kits, states, stats);
        requests = new DuelRequests(this);
        menus = new DuelMenus(this);
        kitEdits = new KitEdits(this);
        arenas.load();

        PluginCommand command = Objects.requireNonNull(getCommand("duel"), "duel command is missing from plugin.yml");
        DuelCommand handler = new DuelCommand(this);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
        PluginCommand ff = Objects.requireNonNull(getCommand("ff"), "ff command is missing from plugin.yml");
        ff.setExecutor(handler);

        getServer().getPluginManager().registerEvents(new DuelListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        // Repair anything a crash or restart left behind.
        arenas.regenerateDirtyArenas();
        for (Player p : Bukkit.getOnlinePlayers()) {
            duels.restoreIfIdle(p);
        }
    }

    @Override
    public void onDisable() {
        // Close our menus so nobody is left with a window that no longer has a plugin behind it.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                p.setItemOnCursor(null);
                p.closeInventory();
            }
        }
        if (kitEdits != null) kitEdits.cancelAll();
        if (duels != null) duels.shutdown();
    }

    StateManager states() {
        return states;
    }

    KitManager kits() {
        return kits;
    }

    ArenaManager arenas() {
        return arenas;
    }

    LayoutManager layouts() {
        return layouts;
    }

    DuelRequests requests() {
        return requests;
    }

    KitEdits kitEdits() {
        return kitEdits;
    }

    DuelMenus menus() {
        return menus;
    }

    StatsManager stats() {
        return stats;
    }

    DuelManager duels() {
        return duels;
    }
}
