package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Online players that can still be added to a team, 45 per page. */
final class PlayerPicker extends ButtonMenu {

    private static final int PER_PAGE = 45;
    private static final int PREV = 45;
    private static final int BACK = 49;
    private static final int NEXT = 53;

    private final Setup setup;
    private final boolean blueSide;
    private final int page;
    private final List<Player> shown = new ArrayList<>();
    private final boolean hasNext;

    PlayerPicker(DuelPlugin plugin, Setup setup, boolean blueSide, int page) {
        super(plugin);
        this.setup = setup;
        this.blueSide = blueSide;

        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!setup.has(p.getUniqueId()) && !plugin.duels().isBusy(p.getUniqueId())) candidates.add(p);
        }
        candidates.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (candidates.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.max(0, Math.min(page, pages - 1));
        int from = this.page * PER_PAGE;
        shown.addAll(candidates.subList(from, Math.min(candidates.size(), from + PER_PAGE)));
        this.hasNext = this.page < pages - 1;

        String team = blueSide ? "<blue>blue</blue>" : "<red>red</red>";
        create(54, "<dark_gray>Add a player");
        for (int i = 0; i < shown.size(); i++) {
            Player p = shown.get(i);
            inv.setItem(i, Items.head(p, "<white>" + Msg.esc(p.getName()), "<gray>Click to add to the " + team + " team"));
        }
        for (int s = 45; s < 54; s++) inv.setItem(s, Items.filler());
        if (this.page > 0) inv.setItem(PREV, Items.of(Material.ARROW, "<white>Previous page"));
        if (hasNext) inv.setItem(NEXT, Items.of(Material.ARROW, "<white>Next page"));
        inv.setItem(BACK, Items.of(Material.BARRIER, "<white>Back to the duel setup"));
        if (shown.isEmpty()) {
            inv.setItem(22, Items.of(Material.PAPER, "<gray>No one else is available",
                    "<dark_gray>Players already in a duel or on a team are hidden"));
        }
    }

    @Override
    void press(Player player, int slot, boolean rightClick) {
        if (slot == BACK) {
            later(() -> plugin.menus().openSetup(player));
        } else if (slot == PREV && page > 0) {
            later(() -> new PlayerPicker(plugin, setup, blueSide, page - 1).open(player));
        } else if (slot == NEXT && hasNext) {
            later(() -> new PlayerPicker(plugin, setup, blueSide, page + 1).open(player));
        } else if (slot < shown.size()) {
            Player target = shown.get(slot);
            UUID id = target.getUniqueId();
            if (!target.isOnline() || setup.has(id) || plugin.duels().isBusy(id)) {
                Msg.send(player, Msg.PREFIX + "<red>" + Msg.esc(target.getName()) + " can't be added right now.");
                later(() -> new PlayerPicker(plugin, setup, blueSide, page).open(player));
                return;
            }
            String err = setup.canAdd(blueSide, plugin.duels().maxPlayers(), SetupMenu.SIDE_MAX);
            if (err != null) {
                Msg.send(player, Msg.PREFIX + "<red>" + Msg.esc(err));
                return;
            }
            setup.add(blueSide, target);
            later(() -> plugin.menus().openSetup(player));
        }
    }
}
