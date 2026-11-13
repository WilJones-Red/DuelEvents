package dev.duelevent;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Menu for choosing the arena of a duel: "any free arena" first, then every arena with its status. */
final class ArenaPicker extends ButtonMenu {

    private static final int PER_PAGE = 45;

    private final Setup setup;
    private final int page;
    private final boolean hasNext;
    /** A null entry stands for "any free arena". */
    private final List<Arena> entries = new ArrayList<>();

    ArenaPicker(DuelPlugin plugin, Setup setup, int page) {
        super(plugin);
        this.setup = setup;

        List<Arena> all = new ArrayList<>();
        all.add(null);
        List<Arena> arenas = new ArrayList<>(plugin.arenas().all());
        arenas.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
        all.addAll(arenas);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.max(0, Math.min(page, pages - 1));
        int from = this.page * PER_PAGE;
        entries.addAll(all.subList(from, Math.min(all.size(), from + PER_PAGE)));
        this.hasNext = this.page < pages - 1;

        int rows = Math.max(1, (entries.size() + 8) / 9);
        create((rows + 1) * 9, "<dark_gray>Choose an arena");
        int base = inv.getSize() - 9;
        for (int s = base; s < inv.getSize(); s++) inv.setItem(s, Items.filler());

        int total = setup.total();
        for (int i = 0; i < entries.size(); i++) {
            Arena a = entries.get(i);
            if (a == null) {
                boolean chosen = setup.arena == null;
                inv.setItem(i, Items.of(Material.COMPASS, (chosen ? "<green>" : "<yellow>") + "Any free arena",
                        "<gray>Uses the first free arena that fits everyone",
                        chosen ? "<green>Currently selected" : "<gray>Click to choose"));
                continue;
            }
            boolean chosen = a.name.equalsIgnoreCase(setup.arena);
            Material icon;
            String status;
            boolean usable = true;
            if (!a.isReady()) {
                icon = Material.GRAY_DYE;
                status = "<red>Not set up yet";
                usable = false;
            } else if (a.maxPlayers > 0 && total > a.maxPlayers) {
                icon = Material.BARRIER;
                status = "<red>Too small for " + total + " players";
                usable = false;
            } else if (a.state != Arena.State.IDLE) {
                icon = Material.CLOCK;
                status = "<yellow>In use right now";
            } else {
                icon = Material.MAP;
                status = "<green>Available";
            }
            String limit = "<gray>Holds: <white>" + (a.maxPlayers > 0 ? a.maxPlayers + " players" : "no player limit");
            String last = chosen ? "<green>Currently selected" : usable ? "<gray>Click to choose" : "<dark_gray>Can't be chosen";
            inv.setItem(i, Items.of(icon, (chosen ? "<green>" : "<yellow>") + Msg.esc(a.name), status, limit, last));
        }
        if (this.page > 0) inv.setItem(base, Items.of(Material.ARROW, "<white>Previous page"));
        inv.setItem(base + 4, Items.of(Material.BARRIER, "<white>Back to the duel setup"));
        if (hasNext) inv.setItem(base + 8, Items.of(Material.ARROW, "<white>Next page"));
    }

    @Override
    void press(Player player, int slot, boolean rightClick) {
        int base = inv.getSize() - 9;
        if (slot == base + 4) {
            later(() -> plugin.menus().openSetup(player));
        } else if (slot == base && page > 0) {
            later(() -> new ArenaPicker(plugin, setup, page - 1).open(player));
        } else if (slot == base + 8 && hasNext) {
            later(() -> new ArenaPicker(plugin, setup, page + 1).open(player));
        } else if (slot < entries.size()) {
            Arena a = entries.get(slot);
            if (a == null) {
                setup.arena = null;
                later(() -> plugin.menus().openSetup(player));
                return;
            }
            if (!a.isReady()) {
                Msg.send(player, Msg.PREFIX + "<red>Arena " + Msg.esc(a.name) + " isn't set up yet.");
                return;
            }
            if (a.maxPlayers > 0 && setup.total() > a.maxPlayers) {
                Msg.send(player, Msg.PREFIX + "<red>Arena " + Msg.esc(a.name) + " only fits " + a.maxPlayers
                        + " players, and you have " + setup.total() + ".");
                return;
            }
            setup.arena = a.name;
            later(() -> plugin.menus().openSetup(player));
        }
    }
}
