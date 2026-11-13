package dev.duelevent;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** Saves item arrays as Base64 using Paper's own item serializer, which keeps all item data. */
final class ItemCodec {

    private ItemCodec() {
    }

    static String encode(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            copy[i] = items[i] == null ? new ItemStack(Material.AIR) : items[i];
        }
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(copy));
    }

    static ItemStack[] decode(String data) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(data));
    }
}
