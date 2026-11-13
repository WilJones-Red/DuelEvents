package dev.duelevent;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

/** A menu where nothing can be picked up: every click is cancelled and just presses a button. */
abstract class ButtonMenu implements Menu {

    protected final DuelPlugin plugin;
    protected Inventory inv;

    ButtonMenu(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    protected void create(int size, String miniTitle) {
        Component title = Msg.parse(miniTitle);
        inv = Bukkit.createInventory(this, size, title);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    @Override
    public final void onClick(Player player, InventoryClickEvent event) {
        event.setCancelled(true);
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= inv.getSize()) return;
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) return;
        press(player, raw, click == ClickType.RIGHT);
    }

    abstract void press(Player player, int slot, boolean rightClick);

    void open(Player player) {
        player.openInventory(inv);
    }

    /** Run on the next tick: opening or closing inventories from inside a click handler is fragile. */
    protected void later(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
