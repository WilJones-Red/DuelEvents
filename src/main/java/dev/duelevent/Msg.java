package dev.duelevent;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

final class Msg {

    static final String PREFIX = "<gold>[Duel]</gold> ";
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msg() {
    }

    static Component parse(String text) {
        return MM.deserialize(text);
    }

    static void send(CommandSender to, String text) {
        to.sendMessage(parse(text));
    }

    /** Use for anything that isn't a fixed string (error messages, names). */
    static String esc(String text) {
        return MM.escapeTags(text == null ? "" : text);
    }
}
