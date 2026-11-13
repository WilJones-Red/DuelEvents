package dev.duelevent;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The duel setup window. Blue is on the left, red on the right, and the controls are in the
 * middle column. Clicking a player's head removes them from their team.
 */
final class SetupMenu extends ButtonMenu {

    /** Heads that fit on one side (4 columns x 5 rows). */
    static final int SIDE_MAX = 20;

    private static final int BLUE_BANNER = 0;
    private static final int BLUE_ADD = 1;
    private static final int BACK = 4;
    private static final int RED_ADD = 7;
    private static final int RED_BANNER = 8;
    private static final int KIT = 13;
    private static final int ARENA = 22;
    private static final int SUMMARY = 31;
    private static final int SEND = 49;
    private static final int[] BLUE_SLOTS = grid(0);
    private static final int[] RED_SLOTS = grid(5);

    private final Setup setup;

    SetupMenu(DuelPlugin plugin, Setup setup) {
        super(plugin);
        this.setup = setup;
        create(54, "<dark_gray>Set up a duel");
        render();
    }

    private static int[] grid(int firstColumn) {
        int[] slots = new int[SIDE_MAX];
        int i = 0;
        for (int row = 1; row <= 5; row++) {
            for (int col = 0; col < 4; col++) slots[i++] = row * 9 + firstColumn + col;
        }
        return slots;
    }

    private static int indexOf(int[] slots, int slot) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing

    private void render() {
        inv.clear();
        for (int i = 0; i < 9; i++) inv.setItem(i, Items.filler());
        inv.setItem(40, Items.filler());

        inv.setItem(BLUE_BANNER, Items.of(Material.BLUE_BANNER, "<blue><bold>Blue team",
                "<gray>Players: <white>" + setup.blue.size()));
        inv.setItem(BLUE_ADD, Items.of(Material.LIME_DYE, "<green>Add a player to blue"));
        inv.setItem(RED_BANNER, Items.of(Material.RED_BANNER, "<red><bold>Red team",
                "<gray>Players: <white>" + setup.red.size()));
        inv.setItem(RED_ADD, Items.of(Material.LIME_DYE, "<green>Add a player to red"));
        inv.setItem(BACK, Items.of(Material.ARROW, "<white>Back to the menu"));

        draw(setup.blue, BLUE_SLOTS, "<blue>");
        draw(setup.red, RED_SLOTS, "<red>");

        inv.setItem(KIT, Items.of(Material.CHEST,
                "<yellow>Kit: " + (setup.kit == null ? "<red>none available" : "<white>" + Msg.esc(setup.kit)),
                "<gray>Click to choose a kit"));
        inv.setItem(ARENA, Items.of(Material.MAP,
                "<yellow>Arena: <white>" + (setup.arena == null ? "any free arena" : Msg.esc(setup.arena)),
                "<gray>Click to choose an arena"));

        String problem = problem();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Format: <white>" + setup.format());
        lore.add("<gray>Players: <white>" + setup.total() + " / " + plugin.duels().maxPlayers());
        lore.add(problem == null ? "<green>Ready to send" : "<red>" + Msg.esc(problem));
        inv.setItem(SUMMARY, Items.of(Material.PAPER, "<yellow>Summary", lore.toArray(new String[0])));

        int timeout = plugin.requests().timeoutSeconds();
        if (problem == null) {
            inv.setItem(SEND, Items.of(Material.LIME_CONCRETE, "<green><bold>Send invites",
                    "<gray>Everyone you added gets an invite", "<gray>that expires after " + timeout + "s"));
        } else {
            inv.setItem(SEND, Items.of(Material.GRAY_CONCRETE, "<gray><bold>Can't send yet", "<red>" + Msg.esc(problem)));
        }
    }

    private void draw(List<UUID> members, int[] slots, String color) {
        for (int i = 0; i < members.size() && i < slots.length; i++) {
            UUID id = members.get(i);
            inv.setItem(slots[i], Items.head(org.bukkit.Bukkit.getOfflinePlayer(id),
                    color + Msg.esc(setup.nameOf(id)), "<gray>Click to remove"));
        }
    }

    /** What stops this setup from being sent right now, or null. */
    private String problem() {
        if (setup.blue.isEmpty() || setup.red.isEmpty()) return "Each team needs at least one player.";
        if (setup.kit == null) return "There are no kits yet.";
        int total = setup.total();
        if (setup.arena != null) {
            Arena a = plugin.arenas().get(setup.arena);
            if (a == null || !a.isReady()) return "That arena isn't available.";
            if (a.maxPlayers > 0 && total > a.maxPlayers) {
                return "Arena " + a.name + " only fits " + a.maxPlayers + " players.";
            }
        } else if (!plugin.duels().anyArenaFits(total)) {
            return "No arena is big enough for " + total + " players.";
        }
        return null;
    }

    // ------------------------------------------------------------------ clicks

    @Override
    void press(Player player, int slot, boolean rightClick) {
        if (slot == BACK) {
            later(() -> plugin.menus().openMain(player));
        } else if (slot == BLUE_ADD) {
            later(() -> new PlayerPicker(plugin, setup, true, 0).open(player));
        } else if (slot == RED_ADD) {
            later(() -> new PlayerPicker(plugin, setup, false, 0).open(player));
        } else if (slot == KIT) {
            later(() -> new KitPicker(plugin, player, "Choose a kit", setup.kit, false, kit -> {
                setup.kit = kit.name();
                plugin.menus().openSetup(player);
            }, () -> plugin.menus().openSetup(player), 0).open(player));
        } else if (slot == ARENA) {
            later(() -> new ArenaPicker(plugin, setup, 0).open(player));
        } else if (slot == SEND) {
            send(player);
        } else {
            int b = indexOf(BLUE_SLOTS, slot);
            int r = indexOf(RED_SLOTS, slot);
            if (b >= 0 && b < setup.blue.size()) removeMember(player, setup.blue.get(b));
            else if (r >= 0 && r < setup.red.size()) removeMember(player, setup.red.get(r));
        }
    }

    private void removeMember(Player player, UUID id) {
        setup.remove(id);
        render();
    }

    private void send(Player player) {
        String err = plugin.requests().create(player, setup);
        if (err != null) {
            Msg.send(player, Msg.PREFIX + "<red>" + Msg.esc(err));
            return;
        }
        plugin.menus().clearSetup(player.getUniqueId());
        later(player::closeInventory);
    }
}
