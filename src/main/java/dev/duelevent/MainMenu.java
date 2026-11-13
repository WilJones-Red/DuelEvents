package dev.duelevent;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** What /duel opens: everything the player is allowed to do, in one window. */
final class MainMenu extends ButtonMenu {

    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

    MainMenu(DuelPlugin plugin, Player viewer) {
        super(plugin);
        create(27, "<dark_gray>Duels");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, Items.filler());

        // Someone who is eliminated and watching can only stop watching.
        if (plugin.duels().duelOf(viewer.getUniqueId()) != null) {
            put(13, Items.of(Material.ENDER_EYE, "<yellow>Stop watching",
                    "<gray>Leave the duel and get your", "<gray>items and position back"),
                    p -> run(p, "duel leave"));
            return;
        }

        boolean enabled = plugin.getConfig().getBoolean("player-duels.enabled", true);
        if (viewer.hasPermission("duelevent.create")) {
            if (!enabled) {
                inv.setItem(10, Items.of(Material.BARRIER, "<red>Duels are turned off",
                        "<gray>Players can't set up duels right now"));
            } else if (plugin.requests().hasPending(viewer.getUniqueId())) {
                put(10, Items.of(Material.CLOCK, "<yellow>Cancel your duel request",
                        "<gray>You already sent a request.", "<gray>Click to cancel it."),
                        p -> run(p, "duel cancel"));
            } else {
                put(10, Items.of(Material.DIAMOND_SWORD, "<green>Set up a duel",
                        "<gray>Pick the teams, kit and arena,", "<gray>then invite the players"),
                        p -> plugin.menus().openSetup(p));
            }
        }
        if (viewer.hasPermission("duelevent.layout")) {
            put(12, Items.of(Material.CHEST, "<yellow>Kit layouts",
                    "<gray>Rearrange a kit's items the way", "<gray>you like them"),
                    p -> plugin.menus().openKitPicker(p));
        }
        if (viewer.hasPermission("duelevent.top")) {
            put(14, Items.of(Material.GOLD_INGOT, "<gold>Leaderboard", "<gray>Players with the most duel wins"),
                    p -> run(p, "duel top"));
        }
        int invites = plugin.requests().incomingCount(viewer.getUniqueId());
        if (invites > 0) {
            put(16, Items.of(Material.WRITABLE_BOOK, "<aqua>Duel invites (" + invites + ")",
                    "<gray>Click to show them in chat", "<gray>with accept and decline buttons"),
                    p -> {
                        p.closeInventory();
                        plugin.requests().resend(p);
                    });
        }

        if (viewer.hasPermission("duelevent.start")) {
            put(20, Items.of(Material.SPYGLASS, "<yellow>Running duels"), p -> run(p, "duel list"));
        }
        if (hasAdminPermission(viewer)) {
            put(22, Items.of(Material.COMMAND_BLOCK, "<yellow>Operator commands",
                    "<gray>Shows every command you can use"), p -> run(p, "duel help"));
        }
        if (viewer.hasPermission("duelevent.reload")) {
            put(24, Items.of(Material.REDSTONE, "<yellow>Reload config and kits"), p -> run(p, "duel reload"));
        }
    }

    private static boolean hasAdminPermission(Player p) {
        for (String perm : List.of("duelevent.start", "duelevent.arena", "duelevent.kit", "duelevent.reload", "duelevent.reset")) {
            if (p.hasPermission(perm)) return true;
        }
        return false;
    }

    private void put(int slot, ItemStack item, Consumer<Player> action) {
        inv.setItem(slot, item);
        actions.put(slot, action);
    }

    private static void run(Player p, String command) {
        p.closeInventory();
        p.performCommand(command);
    }

    @Override
    void press(Player player, int slot, boolean rightClick) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) later(() -> action.accept(player));
    }
}
