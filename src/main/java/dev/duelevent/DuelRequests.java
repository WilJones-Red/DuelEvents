package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Duel requests sent by players. A player can have one request out at a time. Everyone invited has to accept
 * before it starts, and a request is dropped when it expires, is declined or cancelled, or when someone in it
 * goes offline or joins another duel.
 */
final class DuelRequests {

    private static final class Request {
        final UUID creator;
        final String creatorName;
        final List<UUID> blue;
        final List<UUID> red;
        final Map<UUID, String> names;
        final String kit;
        final String arena;
        final Set<UUID> accepted = new HashSet<>();
        final Set<UUID> invited = new HashSet<>();
        BukkitTask expiry;

        Request(Player creator, Setup s, String kit, String arena) {
            this.creator = creator.getUniqueId();
            this.creatorName = creator.getName();
            this.blue = new ArrayList<>(s.blue);
            this.red = new ArrayList<>(s.red);
            this.names = new HashMap<>();
            for (UUID id : blue) names.put(id, s.nameOf(id));
            for (UUID id : red) names.put(id, s.nameOf(id));
            this.kit = kit;
            this.arena = arena;
            invited.addAll(blue);
            invited.addAll(red);
            invited.remove(this.creator);
            accepted.add(this.creator);
        }

        boolean involves(UUID id) {
            return creator.equals(id) || blue.contains(id) || red.contains(id);
        }

        String format() {
            return blue.size() + "v" + red.size();
        }

        String teamNames(List<UUID> team) {
            List<String> out = new ArrayList<>();
            for (UUID id : team) out.add(Msg.esc(names.getOrDefault(id, "?")));
            return String.join(", ", out);
        }
    }

    private final DuelPlugin plugin;
    private final Map<UUID, Request> byCreator = new LinkedHashMap<>();

    DuelRequests(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    int timeoutSeconds() {
        return Math.max(5, plugin.getConfig().getInt("player-duels.invite-timeout-seconds", 60));
    }

    boolean hasPending(UUID creator) {
        return byCreator.containsKey(creator);
    }

    /** Names of the players who invited this player and are still waiting for an answer. */
    List<String> incomingNames(UUID id) {
        List<String> out = new ArrayList<>();
        for (Request r : incoming(id)) out.add(r.creatorName);
        return out;
    }

    private List<Request> incoming(UUID id) {
        List<Request> out = new ArrayList<>();
        for (Request r : byCreator.values()) {
            if (r.invited.contains(id) && !r.accepted.contains(id)) out.add(r);
        }
        return out;
    }

    int incomingCount(UUID id) {
        return incoming(id).size();
    }

    // ------------------------------------------------------------------ messaging

    private static void say(Player p, String text) {
        Msg.send(p, Msg.PREFIX + text);
    }

    private Collection<Player> online(Request r) {
        List<Player> out = new ArrayList<>();
        for (UUID id : r.names.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) out.add(p);
        }
        Player creator = Bukkit.getPlayer(r.creator);
        if (creator != null && !out.contains(creator)) out.add(creator);
        return out;
    }

    private void tellAll(Request r, String text) {
        for (Player p : online(r)) say(p, text);
    }

    private String arenaText(Request r) {
        return r.arena == null ? "any free arena" : Msg.esc(r.arena);
    }

    private void sendInvite(Player to, Request r) {
        String who = Msg.esc(r.creatorName);
        Msg.send(to, Msg.PREFIX + "<yellow>" + who + "</yellow> invited you to a <white>" + r.format() + "</white> duel.");
        Msg.send(to, "<blue>Blue:</blue> <white>" + r.teamNames(r.blue) + "</white>  <red>Red:</red> <white>" + r.teamNames(r.red));
        Msg.send(to, "<gray>Kit: " + Msg.esc(r.kit) + ", arena: " + arenaText(r) + ". Expires in " + timeoutSeconds() + "s.");
        Msg.send(to, "<click:run_command:'/duel accept " + r.creatorName + "'><hover:show_text:'Accept this duel'>"
                + "<green><bold>[ACCEPT]</bold></green></hover></click>  "
                + "<click:run_command:'/duel decline " + r.creatorName + "'><hover:show_text:'Decline this duel'>"
                + "<red><bold>[DECLINE]</bold></red></hover></click>");
    }

    /** Shows the player every request waiting for their answer, each with its own buttons. */
    void resend(Player p) {
        List<Request> list = incoming(p.getUniqueId());
        if (list.isEmpty()) {
            say(p, "<gray>You have no duel invites right now.");
            return;
        }
        for (Request r : list) sendInvite(p, r);
    }

    // ------------------------------------------------------------------ creating

    /** Validates a setup and sends the invites. Returns an error message, or null on success. */
    String create(Player creator, Setup s) {
        FileConfiguration cfg = plugin.getConfig();
        DuelManager duels = plugin.duels();
        if (!cfg.getBoolean("player-duels.enabled", true)) return "Duels set up by players are turned off on this server.";
        UUID cid = creator.getUniqueId();
        if (duels.isBusy(cid)) return "You can't set up a duel while you're in one.";
        if (plugin.kitEdits().isEditing(cid)) return "Finish editing the kit first (/duel kit confirm or /duel kit cancel).";
        if (byCreator.containsKey(cid)) {
            return "You already have a pending duel request. Cancel it with /duel cancel before sending another.";
        }
        long wait = duels.cooldownLeft(cid);
        if (wait > 0) return "You can send another duel request in " + wait + "s.";
        if (s.blue.isEmpty() || s.red.isEmpty()) return "Each team needs at least one player.";
        int total = s.total();
        if (total > duels.maxPlayers()) return "A duel can have at most " + duels.maxPlayers() + " players in total.";

        List<Player> everyone = new ArrayList<>();
        for (UUID id : s.blue) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) return s.nameOf(id) + " is not online.";
            everyone.add(p);
        }
        for (UUID id : s.red) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) return s.nameOf(id) + " is not online.";
            everyone.add(p);
        }
        String err = duels.checkPlayers(everyone);
        if (err != null) return err;

        Kit kit = s.kit == null ? null : plugin.kits().get(s.kit);
        if (kit == null) return "There is no kit to use. Ask an operator to create one.";
        String arenaName = null;
        if (s.arena != null) {
            Arena a = plugin.arenas().get(s.arena);
            if (a == null || !a.isReady()) return "Arena " + s.arena + " isn't available.";
            if (a.maxPlayers > 0 && total > a.maxPlayers) return "Arena " + a.name + " only fits " + a.maxPlayers + " players.";
            arenaName = a.name;
        } else if (!duels.anyArenaFits(total)) {
            return "No arena is big enough for " + total + " players.";
        }

        Request r = new Request(creator, s, kit.name(), arenaName);
        byCreator.put(cid, r);
        r.expiry = Bukkit.getScheduler().runTaskLater(plugin,
                () -> cancel(r, "it expired after " + timeoutSeconds() + " seconds."), timeoutSeconds() * 20L);

        for (UUID id : r.invited) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) sendInvite(p, r);
        }
        Msg.send(creator, Msg.PREFIX + "<green>Invites sent to " + r.invited.size() + (r.invited.size() == 1 ? " player" : " players")
                + ". The duel starts when everyone accepts. "
                + "<click:run_command:'/duel cancel'><hover:show_text:'Cancel your duel request'>"
                + "<red><bold>[CANCEL]</bold></red></hover></click>");
        return null;
    }

    // ------------------------------------------------------------------ answering

    /** Accepts or declines. With no creator name, uses the only pending invite, or lists them if there are several. */
    void respond(Player p, String creatorName, boolean accept) {
        UUID id = p.getUniqueId();
        if (plugin.duels().isBusy(id)) {
            say(p, "<red>You can't do that while you're in a duel.");
            return;
        }
        if (accept && plugin.kitEdits().isEditing(id)) {
            say(p, "<red>Finish editing the kit first: /duel kit confirm or /duel kit cancel.");
            return;
        }
        List<Request> mine = new ArrayList<>();
        for (Request r : byCreator.values()) {
            if (r.invited.contains(id)) mine.add(r);
        }
        Request chosen = null;
        if (creatorName == null) {
            if (mine.isEmpty()) {
                say(p, "<gray>You have no duel invites right now.");
                return;
            }
            if (mine.size() > 1) {
                say(p, "<yellow>You have " + mine.size() + " duel invites. Pick one:");
                for (Request r : mine) sendInvite(p, r);
                return;
            }
            chosen = mine.get(0);
        } else {
            for (Request r : mine) {
                if (r.creatorName.equalsIgnoreCase(creatorName)) chosen = r;
            }
            if (chosen == null) {
                say(p, "<red>You have no duel invite from " + Msg.esc(creatorName) + ".");
                return;
            }
        }

        if (!accept) {
            cancel(chosen, "<white>" + Msg.esc(p.getName()) + "</white> declined it.");
            return;
        }
        if (chosen.accepted.contains(id)) {
            say(p, "<gray>You already accepted. Waiting for the others.");
            return;
        }
        chosen.accepted.add(id);
        int waiting = chosen.invited.size() + 1 - chosen.accepted.size();
        tellAll(chosen, "<white>" + Msg.esc(p.getName()) + "</white> <green>accepted</green>"
                + (waiting > 0 ? " <gray>(waiting for " + waiting + " more)" : "") + ".");
        if (waiting == 0) startNow(chosen);
    }

    /** The creator withdraws their own request. */
    void cancelOwn(Player p) {
        Request r = byCreator.get(p.getUniqueId());
        if (r == null) {
            say(p, "<gray>You have no pending duel request.");
            return;
        }
        cancel(r, "<white>" + Msg.esc(p.getName()) + "</white> cancelled it.");
    }

    // ------------------------------------------------------------------ cancelling

    private void remove(Request r) {
        byCreator.remove(r.creator);
        if (r.expiry != null) r.expiry.cancel();
    }

    /** reason is MiniMessage and finishes the sentence "The duel request from X was cancelled: ...". */
    private void cancel(Request r, String reason) {
        if (byCreator.get(r.creator) != r) return;
        remove(r);
        tellAll(r, "<red>The duel request from <white>" + Msg.esc(r.creatorName) + "</white> was cancelled: <gray>" + reason);
    }

    /** A player left the server. */
    void playerQuit(Player p) {
        for (Request r : new ArrayList<>(byCreator.values())) {
            if (r.involves(p.getUniqueId())) {
                cancel(r, "<white>" + Msg.esc(p.getName()) + "</white> left the server.");
            }
        }
    }

    /** These players just joined a duel, so every request they are part of is dropped. */
    void playersJoinedDuel(Collection<UUID> ids) {
        for (Request r : new ArrayList<>(byCreator.values())) {
            for (UUID id : ids) {
                if (r.involves(id)) {
                    cancel(r, "<white>" + Msg.esc(r.names.getOrDefault(id, "a player")) + "</white> joined another duel.");
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ starting

    private void startNow(Request r) {
        remove(r);
        Player creator = Bukkit.getPlayer(r.creator);
        if (creator == null) {
            tellAll(r, "<red>The duel could not start: <white>" + Msg.esc(r.creatorName) + "</white> is no longer online.");
            return;
        }
        List<Player> blue = new ArrayList<>();
        List<Player> red = new ArrayList<>();
        for (UUID id : r.blue) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                tellAll(r, "<red>The duel could not start: <white>" + Msg.esc(r.names.get(id)) + "</white> is no longer online.");
                return;
            }
            blue.add(p);
        }
        for (UUID id : r.red) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                tellAll(r, "<red>The duel could not start: <white>" + Msg.esc(r.names.get(id)) + "</white> is no longer online.");
                return;
            }
            red.add(p);
        }
        Kit kit = plugin.kits().get(r.kit);
        Arena arena = r.arena == null ? null : plugin.arenas().get(r.arena);
        if (kit == null || (r.arena != null && arena == null)) {
            tellAll(r, "<red>The duel could not start: the kit or arena was removed.");
            return;
        }
        plugin.duels().start(creator, blue, red, arena, kit, true);
    }
}
