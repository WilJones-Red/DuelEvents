package dev.duelevent;

import org.bukkit.inventory.ItemStack;

/** A kit is a full copy of an operator's inventory (storage, armor and offhand). */
record Kit(String name, String data) {

    ItemStack[] contents() {
        return ItemCodec.decode(data);
    }
}
