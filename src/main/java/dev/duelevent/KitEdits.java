package dev.duelevent;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lets an operator edit a kit in their own inventory. Their real gear is saved to disk first (the same
 * safe backup duels use), so cancelling, dying, disconnecting or a crash always gives it back and never
 * changes the kit. Only /duel kit confirm writes the kit.
 */
final class KitEdits {

    private final DuelPlugin plugin;
    /** Player -> name of the kit they are editing. Only counts while their backup file still exists. */
    private final Map<UUID, String> editing = new HashMap<>();

    KitEdits(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /** True while the player is editing. If their backup was restored some other way (e.g. they died), the session is over. */
    boolean isEditing(UUID id) {
        if (!editing.containsKey(id)) return false;
        if (plugin.states().hasPending(id)) return true;
        editing.remove(id);
        return false;
    }

    /** The player went offline: the session ends and their backup is restored when they rejoin. */
    void forget(UUID id) {
        editing.remove(id);
    }

    /** Returns an error message, or null if the kit is now in the player's inventory. */
    String begin(Player p, Kit kit) {
        UUID id = p.getUniqueId();
        if (isEditing(id)) {
            return "You're already editing kit " + editing.get(id) + ". Finish with /duel kit confirm or /duel kit cancel first.";
        }
        if (plugin.duels().isBusy(id)) return "You can't edit a kit while you're in a duel.";
        if (plugin.states().hasPending(id)) {
            return "You still have saved items waiting to be restored. Wait a moment or rejoin, then try again.";
        }
        if (plugin.requests().hasPending(id)) return "Cancel your pending duel request first (/duel cancel).";

        ItemStack[] items;
        try {
            items = kit.contents();
        } catch (RuntimeException ex) {
            return "Kit " + kit.name() + " could not be loaded: " + ex.getMessage();
        }
        if (!plugin.states().save(p)) return "Could not save your own items safely, so nothing was changed.";

        p.closeInventory();
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setContents(Arrays.copyOf(items, Math.min(items.length, inv.getSize())));
        p.updateInventory();
        editing.put(id, kit.name());
        return null;
    }

    /** Saves the player's current inventory as the kit and gives their own gear back. */
    void confirm(Player p) {
        UUID id = p.getUniqueId();
        if (!isEditing(id)) {
            Msg.send(p, Msg.PREFIX + "<red>You're not editing a kit. Start with /duel kit edit <name>. "
                    + "(If you just died or rejoined, your gear was already restored and nothing was saved.)");
            return;
        }
        String name = editing.get(id);
        Kit old = plugin.kits().get(name);
        boolean changed = true;
        try {
            changed = old == null || !ItemCodec.encode(p.getInventory().getContents()).equals(old.data());
        } catch (RuntimeException ignored) {
            // treat it as changed
        }

        if (changed) {
            String err = plugin.kits().save(name, p.getInventory());
            if (err != null) {
                Msg.send(p, Msg.PREFIX + "<red>Could not save the kit: " + Msg.esc(err)
                        + " You're still editing; try /duel kit confirm again, or /duel kit cancel.");
                return;
            }
        }
        editing.remove(id);
        boolean restored = plugin.states().restore(p);
        if (!restored) {
            Msg.send(p, Msg.PREFIX + "<red>Your own items could not be restored right now. They are safe on disk "
                    + "and come back when you rejoin.");
        }
        if (changed) {
            Msg.send(p, Msg.PREFIX + "<green>Saved kit <white>" + Msg.esc(name) + "</white>. Players' saved layouts of it were reset."
                    + (restored ? " Your own items are back." : ""));
        } else {
            Msg.send(p, Msg.PREFIX + "<gray>No changes to kit " + Msg.esc(name) + "." + (restored ? " Your own items are back." : ""));
        }
    }

    /** Throws the edits away and gives the player's own gear back. */
    void cancel(Player p) {
        UUID id = p.getUniqueId();
        if (!isEditing(id)) {
            Msg.send(p, Msg.PREFIX + "<red>You're not editing a kit.");
            return;
        }
        String name = editing.remove(id);
        if (plugin.states().restore(p)) {
            Msg.send(p, Msg.PREFIX + "<green>Cancelled. Kit <white>" + Msg.esc(name) + "</white> was not changed, and your own items are back.");
        } else {
            Msg.send(p, Msg.PREFIX + "<red>Your own items could not be restored right now. They are safe on disk "
                    + "and come back when you rejoin. Kit " + Msg.esc(name) + " was not changed.");
        }
    }

    /** Plugin shutdown: everyone still editing gets their own gear back. */
    void cancelAll() {
        for (UUID id : java.util.List.copyOf(editing.keySet())) {
            editing.remove(id);
            Player p = org.bukkit.Bukkit.getPlayer(id);
            if (p != null && plugin.states().hasPending(id)) plugin.states().restore(p);
        }
    }
}
