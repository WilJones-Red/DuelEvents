package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Lets a player rearrange a kit's items. Only the kit's own items can be moved around inside this window,
 * and the result is checked against the kit before it is saved, so nothing can be added or removed.
 *
 * Window layout: rows 1-3 are the main inventory, row 4 the hotbar, row 5 holds the armor (shown, fixed)
 * and the offhand slot, row 6 has the Save, Reset and Cancel buttons.
 */
final class LayoutEditor implements Menu {

    private static final int SIZE = 54;
    private static final int INV_SIZE = 41;
    private static final int SAVE = 45;
    private static final int RESET = 49;
    private static final int CANCEL = 53;
    private static final int OFFHAND_GUI = 41;
    // Helmet, chestplate, leggings, boots: where they are shown, and where they live in the inventory array.
    private static final int[] ARMOR_GUI = {36, 37, 38, 39};
    private static final int[] ARMOR_INV = {39, 38, 37, 36};
    private static final String[] ARMOR_NAMES = {"helmet", "chestplate", "leggings", "boots"};
    private static final Set<InventoryAction> MOVES = EnumSet.of(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_SOME,
            InventoryAction.PICKUP_ONE, InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME,
            InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR);

    private final DuelPlugin plugin;
    private final Kit kit;
    private final ItemStack[] kitContents;
    private final Inventory inv;

    LayoutEditor(DuelPlugin plugin, Player player, Kit kit) {
        this.plugin = plugin;
        this.kit = kit;
        this.kitContents = Arrays.copyOf(kit.contents(), INV_SIZE);
        ItemStack[] saved = plugin.layouts().load(player.getUniqueId(), kit);
        ItemStack[] current = saved == null ? kitContents : Arrays.copyOf(saved, INV_SIZE);

        this.inv = Bukkit.createInventory(this, SIZE, Msg.parse("<dark_gray>Layout: " + Msg.esc(kit.name())));
        for (int g = 0; g < SIZE; g++) {
            if (movable(g)) inv.setItem(g, copy(current[invSlot(g)]));
        }
        for (int i = 0; i < ARMOR_GUI.length; i++) {
            ItemStack armor = kitContents[ARMOR_INV[i]];
            inv.setItem(ARMOR_GUI[i], isEmpty(armor)
                    ? Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>No " + ARMOR_NAMES[i])
                    : armor.clone());
        }
        inv.setItem(40, Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "<gray>Offhand slot", "<dark_gray>(to the right)"));
        for (int g : new int[]{42, 43, 44, 46, 47, 48, 50, 51, 52}) inv.setItem(g, Items.filler());
        inv.setItem(SAVE, Items.of(Material.LIME_CONCRETE, "<green><bold>Save layout", "<gray>Keep this arrangement for your duels"));
        inv.setItem(RESET, Items.of(Material.ORANGE_CONCRETE, "<gold><bold>Reset to default",
                "<gray>Forget your saved layout and use", "<gray>the kit's normal arrangement"));
        inv.setItem(CANCEL, Items.of(Material.RED_CONCRETE, "<red><bold>Cancel", "<gray>Close without saving"));
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    void open(Player player) {
        player.openInventory(inv);
    }

    // ------------------------------------------------------------------ slot mapping

    /** Window slots 0-26 are inventory slots 9-35, 27-35 are the hotbar (0-8), 41 is the offhand (40). */
    private static int invSlot(int gui) {
        if (gui < 27) return gui + 9;
        if (gui < 36) return gui - 27;
        return 40;
    }

    private static boolean movable(int gui) {
        return (gui >= 0 && gui < 36) || gui == OFFHAND_GUI;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private static ItemStack copy(ItemStack item) {
        return isEmpty(item) ? null : item.clone();
    }

    // ------------------------------------------------------------------ clicks

    @Override
    public void onClick(Player player, InventoryClickEvent e) {
        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) { // outside the window, or the player's own inventory below it
            e.setCancelled(true);
            return;
        }
        if (raw == SAVE || raw == RESET || raw == CANCEL) {
            e.setCancelled(true);
            ClickType click = e.getClick();
            if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
            if (raw == SAVE) save(player);
            else if (raw == RESET) reset(player);
            else closeLater(player);
            return;
        }
        ClickType click = e.getClick();
        boolean plain = click == ClickType.LEFT || click == ClickType.RIGHT;
        if (!movable(raw) || !plain || !MOVES.contains(e.getAction())) e.setCancelled(true);
    }

    @Override
    public void onDrag(Player player, InventoryDragEvent e) {
        for (int raw : e.getRawSlots()) {
            if (!movable(raw)) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Whatever is still on the cursor belongs to the layout window, so it must not land in the player's inventory. */
    @Override
    public void onClose(Player player) {
        player.setItemOnCursor(null);
    }

    private void closeLater(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }

    // ------------------------------------------------------------------ buttons

    private void save(Player player) {
        ItemStack[] out = new ItemStack[INV_SIZE];
        for (int g = 0; g < SIZE; g++) {
            if (movable(g)) out[invSlot(g)] = copy(inv.getItem(g));
        }
        for (int slot : ARMOR_INV) out[slot] = copy(kitContents[slot]);

        // The window only lets items be rearranged, but double-check that nothing was added or lost.
        if (!tally(out).equals(tally(kitContents))) {
            Msg.send(player, Msg.PREFIX + "<red>Your layout didn't match the kit's items, so it was not saved.");
            closeLater(player);
            return;
        }
        String err = plugin.layouts().save(player.getUniqueId(), kit, out);
        if (err != null) {
            Msg.send(player, Msg.PREFIX + "<red>Could not save your layout: " + Msg.esc(err));
        } else {
            Msg.send(player, Msg.PREFIX + "<green>Saved your layout for kit <white>" + Msg.esc(kit.name()) + "</white>.");
        }
        closeLater(player);
    }

    private void reset(Player player) {
        plugin.layouts().reset(player.getUniqueId(), kit.name());
        Msg.send(player, Msg.PREFIX + "<green>Your layout for kit <white>" + Msg.esc(kit.name())
                + "</white> was reset to the default.");
        closeLater(player);
    }

    /** Counts the items by type and data (ignoring stack size), so a split or merged stack still adds up. */
    private static Map<String, Integer> tally(ItemStack[] items) {
        Map<String, Integer> out = new HashMap<>();
        for (ItemStack item : items) {
            if (isEmpty(item)) continue;
            ItemStack one = item.clone();
            one.setAmount(1);
            out.merge(ItemCodec.encode(new ItemStack[]{one}), item.getAmount(), Integer::sum);
        }
        return out;
    }
}
