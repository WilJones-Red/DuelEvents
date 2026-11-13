package dev.duelevent;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/** Anything this plugin shows as an inventory GUI. MenuListener routes the inventory events to it. */
interface Menu extends InventoryHolder {

    /** A click anywhere in the open window (the menu itself or the player's own inventory below it). */
    void onClick(Player player, InventoryClickEvent event);

    default void onDrag(Player player, InventoryDragEvent event) {
        event.setCancelled(true);
    }

    default void onClose(Player player) {
    }
}
