package dev.duelevent;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A menu listing the kits, 45 per page. Used for choosing the kit of a duel and for choosing which kit's
 * layout to edit; what happens on a click is up to the caller.
 */
final class KitPicker extends ButtonMenu {

    private static final int PER_PAGE = 45;

    private final String title;
    private final String selected;
    private final boolean layoutMode;
    private final Consumer<Kit> onPick;
    private final Runnable onBack;
    private final int page;
    private final boolean hasNext;
    private final List<String> names = new ArrayList<>();

    /**
     * @param selected   kit to mark as the current choice, or null
     * @param layoutMode true when picking a kit to rearrange (shows whether the player has a saved layout)
     */
    KitPicker(DuelPlugin plugin, Player viewer, String title, String selected, boolean layoutMode,
              Consumer<Kit> onPick, Runnable onBack, int page) {
        super(plugin);
        this.title = title;
        this.selected = selected;
        this.layoutMode = layoutMode;
        this.onPick = onPick;
        this.onBack = onBack;

        List<String> all = new ArrayList<>(plugin.kits().names());
        Collections.sort(all);
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        this.page = Math.max(0, Math.min(page, pages - 1));
        int from = this.page * PER_PAGE;
        names.addAll(all.subList(from, Math.min(all.size(), from + PER_PAGE)));
        this.hasNext = this.page < pages - 1;

        int rows = Math.max(1, (names.size() + 8) / 9);
        create((rows + 1) * 9, "<dark_gray>" + title);
        int base = inv.getSize() - 9;
        for (int s = base; s < inv.getSize(); s++) inv.setItem(s, Items.filler());

        UUID id = viewer.getUniqueId();
        for (int i = 0; i < names.size(); i++) {
            Kit kit = plugin.kits().get(names.get(i));
            if (kit == null) continue;
            boolean isSelected = kit.name().equalsIgnoreCase(selected);
            String line1;
            String line2 = "";
            if (layoutMode) {
                line1 = "<gray>Click to rearrange this kit";
                line2 = plugin.layouts().has(id, kit) ? "<green>You have a saved layout" : "<dark_gray>Default layout";
            } else {
                line1 = isSelected ? "<green>Currently selected" : "<gray>Click to choose this kit";
            }
            String name = (isSelected ? "<green>" : "<yellow>") + Msg.esc(kit.name());
            inv.setItem(i, line2.isEmpty() ? Items.of(icon(kit), name, line1) : Items.of(icon(kit), name, line1, line2));
        }
        if (names.isEmpty()) {
            inv.setItem(4, Items.of(Material.PAPER, "<gray>There are no kits yet"));
        }
        if (this.page > 0) inv.setItem(base, Items.of(Material.ARROW, "<white>Previous page"));
        inv.setItem(base + 4, Items.of(Material.BARRIER, "<white>Back"));
        if (hasNext) inv.setItem(base + 8, Items.of(Material.ARROW, "<white>Next page"));
    }

    private static Material icon(Kit kit) {
        try {
            for (ItemStack item : kit.contents()) {
                if (item != null && !item.getType().isAir()) return item.getType();
            }
        } catch (RuntimeException ignored) {
            // fall through to the generic icon
        }
        return Material.CHEST;
    }

    @Override
    void press(Player player, int slot, boolean rightClick) {
        int base = inv.getSize() - 9;
        if (slot == base + 4) {
            later(onBack);
        } else if (slot == base && page > 0) {
            later(() -> reopen(player, page - 1));
        } else if (slot == base + 8 && hasNext) {
            later(() -> reopen(player, page + 1));
        } else if (slot < names.size()) {
            Kit kit = plugin.kits().get(names.get(slot));
            if (kit != null) later(() -> onPick.accept(kit));
        }
    }

    private void reopen(Player player, int newPage) {
        new KitPicker(plugin, player, title, selected, layoutMode, onPick, onBack, newPage).open(player);
    }
}
