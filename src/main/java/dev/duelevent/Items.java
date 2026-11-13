package dev.duelevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/** Small helpers for building the items shown in menus. */
final class Items {

    private Items() {
    }

    /** MiniMessage text without the default italics that item names and lore get. */
    static Component text(String mini) {
        return Msg.parse(mini).decoration(TextDecoration.ITALIC, false);
    }

    private static List<Component> lines(String[] lore) {
        List<Component> out = new ArrayList<>();
        for (String line : lore) out.add(text(line));
        return out;
    }

    static ItemStack of(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(text(name));
            if (lore.length > 0) meta.lore(lines(lore));
        });
        return item;
    }

    static ItemStack filler() {
        return of(Material.GRAY_STAINED_GLASS_PANE, " ");
    }

    static ItemStack head(OfflinePlayer player, String name, String... lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        item.editMeta(SkullMeta.class, meta -> {
            meta.setOwningPlayer(player);
            meta.displayName(text(name));
            if (lore.length > 0) meta.lore(lines(lore));
        });
        return item;
    }
}
