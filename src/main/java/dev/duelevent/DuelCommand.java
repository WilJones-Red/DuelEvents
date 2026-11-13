package dev.duelevent;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class DuelCommand implements CommandExecutor, TabCompleter {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final List<String> ARENA_OPS = List.of("create", "delete", "set", "snapshot", "regen", "list", "info");
    private static final List<String> POINTS = List.of("pos1", "pos2", "spawn1", "spawn2");
    private static final List<String> ARENA_SET = List.of("pos1", "pos2", "spawn1", "spawn2", "maxplayers");
    private static final List<String> KIT_OPS = List.of("set", "delete", "list", "edit", "confirm", "cancel");
    private static final int PER_PAGE = 5;
    private static final long RESET_CONFIRM_MILLIS = 30_000L;

    private final DuelPlugin plugin;
    /** Who has asked to reset the leaderboard, and until when they can confirm. */
    private final Map<String, Long> resetRequests = new HashMap<>();

    DuelCommand(DuelPlugin plugin) {
        this.plugin = plugin;
    }

    private static void say(CommandSender to, String text) {
        Msg.send(to, Msg.PREFIX + text);
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ff")) {
            forfeit(sender);
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player p) openMenu(p);
            else help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("forfeit") || sub.equals("ff")) {
            forfeit(sender);
            return true;
        }

        // Commands anyone can use.
        switch (sub) {
            case "help" -> {
                help(sender);
                return true;
            }
            case "leave" -> {
                leave(sender);
                return true;
            }
            case "kitlayout" -> {
                kitLayout(sender, args);
                return true;
            }
            case "accept" -> {
                respond(sender, args, true);
                return true;
            }
            case "decline" -> {
                respond(sender, args, false);
                return true;
            }
            case "cancel" -> {
                if (args.length == 1) { // withdraw your own request; "/duel cancel <player>" below is the operator's stop
                    cancelRequest(sender);
                    return true;
                }
            }
            default -> {
            }
        }

        String needed = switch (sub) {
            case "start", "stop", "cancel", "list" -> "duelevent.start";
            case "arena" -> "duelevent.arena";
            case "kit" -> "duelevent.kit";
            case "reload" -> "duelevent.reload";
            case "top" -> "duelevent.top";
            default -> null;
        };
        if (needed != null && !sender.hasPermission(needed)) {
            say(sender, "<red>You don't have permission to do that.");
            return true;
        }

        switch (sub) {
            case "start" -> start(sender, args);
            case "stop", "cancel" -> stop(sender, args);
            case "list" -> list(sender);
            case "arena" -> arena(sender, args);
            case "kit" -> kit(sender, args);
            case "top" -> top(sender, args);
            case "reload" -> {
                plugin.reloadConfig();
                plugin.kits().load();
                plugin.layouts().purgeStale(plugin.kits());
                plugin.duels().reloadSettings();
                say(sender, "<green>Config and kits reloaded.");
            }
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender s) {
        say(s, "<yellow>Duel commands:");
        if (s instanceof Player) Msg.send(s, "<gray>/duel   <dark_gray>- open the duel menu");
        if (s.hasPermission("duelevent.layout")) {
            Msg.send(s, "<gray>/duel kitlayout [kit]   <dark_gray>- rearrange a kit's items just for you");
        }
        Msg.send(s, "<gray>/duel accept|decline [player]   <dark_gray>- answer a duel invite");
        Msg.send(s, "<gray>/duel cancel   <dark_gray>- cancel the duel request you sent");
        Msg.send(s, "<gray>/duel leave   <dark_gray>- stop watching a duel after you were eliminated");
        if (s.hasPermission("duelevent.start")) {
            Msg.send(s, "<gray>/duel start [arena <name>] [kit <name>] blue <players...> red <players...>");
            Msg.send(s, "<dark_gray>  teams can be any size, up to " + plugin.duels().maxPlayers() + " players in total");
            Msg.send(s, "<gray>/duel stop <player>   <dark_gray>- cancel the duel that player is in (no winner)");
            Msg.send(s, "<gray>/duel list");
        }
        if (s.hasPermission("duelevent.kit")) {
            Msg.send(s, "<gray>/duel kit set|delete|list [name]   <dark_gray>- set copies YOUR inventory");
            Msg.send(s, "<gray>/duel kit edit <name>   <dark_gray>- put a kit in your inventory to change it, then /duel kit confirm or cancel");
        }
        if (s.hasPermission("duelevent.arena")) {
            Msg.send(s, "<gray>/duel arena create|delete|info|snapshot|regen <name>");
            Msg.send(s, "<gray>/duel arena set <name> pos1|pos2|spawn1|spawn2   <dark_gray>- uses where you stand");
            Msg.send(s, "<gray>/duel arena set <name> maxplayers <number>   <dark_gray>- 0 means no limit");
        }
        if (s.hasPermission("duelevent.reload")) {
            Msg.send(s, "<gray>/duel reload");
        }
        if (s.hasPermission("duelevent.top")) {
            Msg.send(s, "<gray>/duel top [page]   <dark_gray>- most duel wins");
        }
        if (s.hasPermission("duelevent.reset")) {
            Msg.send(s, "<gray>/duel top reset   <dark_gray>- wipe all win stats (asks you to confirm)");
        }
        Msg.send(s, "<gray>/ff   <dark_gray>- vote with your team to forfeit your current duel");
    }

    private void openMenu(Player p) {
        Duel d = plugin.duels().duelOf(p.getUniqueId());
        if (d != null && d.isAlive(p.getUniqueId())) {
            say(p, "<gray>You're in a duel. Use <white>/ff</white> to vote to forfeit.");
            return;
        }
        plugin.menus().openMain(p);
    }

    // ------------------------------------------------------------------ leaving, layouts, requests

    private void leave(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            say(sender, "<red>Only players can do that.");
            return;
        }
        String err = plugin.duels().leaveSpectating(p);
        if (err != null) say(sender, "<red>" + Msg.esc(err));
    }

    private void kitLayout(CommandSender sender, String[] a) {
        if (!(sender instanceof Player p)) {
            say(sender, "<red>Only players can do that.");
            return;
        }
        if (!p.hasPermission("duelevent.layout")) {
            say(sender, "<red>You don't have permission to do that.");
            return;
        }
        if (plugin.duels().duelOf(p.getUniqueId()) != null) {
            say(sender, "<red>You can't do that during a duel.");
            return;
        }
        if (plugin.kitEdits().isEditing(p.getUniqueId())) {
            say(sender, "<red>Finish editing the kit first: /duel kit confirm or /duel kit cancel.");
            return;
        }
        if (plugin.kits().size() == 0) {
            say(sender, "<gray>There are no kits yet.");
            return;
        }
        if (a.length >= 2) {
            Kit kit = plugin.kits().get(a[1]);
            if (kit == null) {
                say(sender, "<red>No kit named " + Msg.esc(a[1]) + ".");
                return;
            }
            plugin.menus().openLayout(p, kit);
        } else if (plugin.kits().size() == 1) {
            plugin.menus().openLayout(p, plugin.kits().only());
        } else {
            plugin.menus().openKitPicker(p);
        }
    }

    private void respond(CommandSender sender, String[] a, boolean accept) {
        if (!(sender instanceof Player p)) {
            say(sender, "<red>Only players can do that.");
            return;
        }
        plugin.requests().respond(p, a.length >= 2 ? a[1] : null, accept);
    }

    private void cancelRequest(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            say(sender, "<red>Only players send duel requests. To stop a running duel use /duel stop <player>.");
            return;
        }
        plugin.requests().cancelOwn(p);
    }

    // ------------------------------------------------------------------ forfeit

    private void forfeit(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            say(sender, "<red>Only players can forfeit.");
            return;
        }
        if (!p.hasPermission("duelevent.participate")) {
            say(sender, "<red>You don't have permission to do that.");
            return;
        }
        String err = plugin.duels().forfeitVote(p);
        if (err != null) say(sender, "<red>" + Msg.esc(err));
    }

    // ------------------------------------------------------------------ start / stop / list

    private void startUsage(CommandSender s) {
        say(s, "<red>Usage: /duel start [arena <name>] [kit <name>] blue <players...> red <players...>");
        Msg.send(s, "<gray>Example: <white>/duel start arena colosseum kit starter blue Alice Bob red Carol Dave</white>");
    }

    /**
     * /duel start [arena name] [kit name] blue players... red players...
     * Arena and kit come first and are optional. Once "blue" or "red" appears, every word is a player
     * name for that team until the other keyword.
     */
    private void start(CommandSender s, String[] a) {
        String arenaName = null;
        String kitName = null;
        List<String> blueNames = new ArrayList<>();
        List<String> redNames = new ArrayList<>();
        List<String> section = null;
        int i = 1;
        while (i < a.length) {
            String word = a[i].toLowerCase(Locale.ROOT);
            if (word.equals("blue")) {
                section = blueNames;
                i++;
            } else if (word.equals("red")) {
                section = redNames;
                i++;
            } else if (section != null) {
                section.add(a[i]);
                i++;
            } else if (word.equals("arena") || word.equals("kit")) {
                if (i + 1 >= a.length) {
                    startUsage(s);
                    return;
                }
                if (word.equals("arena")) {
                    if (arenaName != null) {
                        say(s, "<red>The arena was given twice.");
                        return;
                    }
                    arenaName = a[i + 1];
                } else {
                    if (kitName != null) {
                        say(s, "<red>The kit was given twice.");
                        return;
                    }
                    kitName = a[i + 1];
                }
                i += 2;
            } else {
                startUsage(s);
                return;
            }
        }
        if (blueNames.isEmpty() || redNames.isEmpty()) {
            say(s, "<red>Both teams need at least one player.");
            startUsage(s);
            return;
        }
        int max = plugin.duels().maxPlayers();
        if (blueNames.size() + redNames.size() > max) {
            say(s, "<red>A duel can have at most " + max + " players in total.");
            return;
        }

        List<Player> blue = new ArrayList<>();
        List<Player> red = new ArrayList<>();
        for (String name : blueNames) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) {
                say(s, "<red>" + Msg.esc(name) + " is not online.");
                return;
            }
            blue.add(p);
        }
        for (String name : redNames) {
            Player p = Bukkit.getPlayerExact(name);
            if (p == null) {
                say(s, "<red>" + Msg.esc(name) + " is not online.");
                return;
            }
            red.add(p);
        }
        Arena arena = null;
        if (arenaName != null) {
            arena = plugin.arenas().get(arenaName);
            if (arena == null) {
                say(s, "<red>No arena named " + Msg.esc(arenaName) + ".");
                return;
            }
        }
        Kit kit = null;
        if (kitName != null) {
            kit = plugin.kits().get(kitName);
            if (kit == null) {
                say(s, "<red>No kit named " + Msg.esc(kitName) + ".");
                return;
            }
        }
        plugin.duels().start(s, blue, red, arena, kit);
    }

    private void stop(CommandSender s, String[] a) {
        if (a.length < 2) {
            say(s, "<red>Usage: /duel stop <any player in the duel>");
            return;
        }
        Player p = Bukkit.getPlayerExact(a[1]);
        Duel d = p == null ? null : plugin.duels().duelOf(p.getUniqueId());
        if (d == null) {
            say(s, "<red>" + Msg.esc(a[1]) + " is not in a duel.");
            return;
        }
        plugin.duels().stopDuel(d, "The duel was stopped by an operator.");
        say(s, "<green>Duel stopped.");
    }

    private void list(CommandSender s) {
        if (plugin.duels().isEmpty()) {
            say(s, "<gray>No duels are running.");
            return;
        }
        for (Duel d : plugin.duels().all()) {
            Msg.send(s, "<yellow>" + Msg.esc(d.title())
                    + "</yellow> <gray>on " + d.arena.name + " (" + d.phase.name().toLowerCase(Locale.ROOT) + ", kit " + d.kit.name() + ")");
        }
    }

    // ------------------------------------------------------------------ leaderboard

    private void top(CommandSender s, String[] a) {
        if (a.length >= 2 && a[1].equalsIgnoreCase("reset")) {
            reset(s, a);
            return;
        }
        int page = 1;
        if (a.length >= 2) {
            try {
                page = Integer.parseInt(a[1]);
            } catch (NumberFormatException ex) {
                say(s, "<red>Usage: /duel top [page]");
                return;
            }
        }
        List<StatsManager.Row> rows = plugin.stats().ranked();
        if (rows.isEmpty()) {
            say(s, "<gray>Nobody has won a duel yet.");
            return;
        }
        int pages = (rows.size() + PER_PAGE - 1) / PER_PAGE;
        page = Math.max(1, Math.min(page, pages));
        Msg.send(s, "<gold>Top duelists</gold> <gray>(page " + page + " of " + pages + ")");
        for (int i = (page - 1) * PER_PAGE; i < Math.min(rows.size(), page * PER_PAGE); i++) {
            StatsManager.Row r = rows.get(i);
            Msg.send(s, "<yellow>" + (i + 1) + ".</yellow> <white>" + Msg.esc(r.name()) + "</white> <gray>- "
                    + r.wins() + (r.wins() == 1 ? " win" : " wins"));
        }
        if (page < pages) Msg.send(s, "<dark_gray>/duel top " + (page + 1) + " for the next page");
    }

    private void reset(CommandSender s, String[] a) {
        if (!s.hasPermission("duelevent.reset")) {
            say(s, "<red>You don't have permission to do that.");
            return;
        }
        String key = s instanceof Player p ? p.getUniqueId().toString() : "console";
        if (a.length >= 3 && a[2].equalsIgnoreCase("confirm")) {
            Long until = resetRequests.remove(key);
            if (until == null || System.currentTimeMillis() > until) {
                say(s, "<red>Nothing to confirm. Run <white>/duel top reset</white> first.");
                return;
            }
            plugin.stats().reset();
            plugin.getLogger().info(s.getName() + " reset all duel win stats.");
            say(s, "<green>All duel win stats have been reset.");
            return;
        }
        resetRequests.put(key, System.currentTimeMillis() + RESET_CONFIRM_MILLIS);
        say(s, "<yellow>This permanently deletes every player's duel wins.</yellow> To go ahead, run "
                + "<white>/duel top reset confirm</white> within 30 seconds.");
    }

    // ------------------------------------------------------------------ kits

    private void kit(CommandSender s, String[] a) {
        if (a.length < 2) {
            say(s, "<red>Usage: /duel kit set|delete|edit|confirm|cancel|list [name]");
            return;
        }
        String op = a[1].toLowerCase(Locale.ROOT);
        switch (op) {
            case "list" -> {
                if (plugin.kits().size() == 0) say(s, "<gray>No kits yet. Fill your inventory and run /duel kit set <name>.");
                else say(s, "<yellow>Kits: <white>" + String.join(", ", plugin.kits().names()));
            }
            case "set" -> {
                if (!(s instanceof Player p)) {
                    say(s, "<red>Run this as a player: it copies your inventory.");
                    return;
                }
                if (a.length < 3 || !NAME.matcher(a[2]).matches()) {
                    say(s, "<red>Usage: /duel kit set <name>  (letters, numbers, - and _ only)");
                    return;
                }
                String err = plugin.kits().save(a[2], p.getInventory());
                if (err != null) say(s, "<red>Could not save the kit: " + Msg.esc(err));
                else say(s, "<green>Saved your inventory (items, armor and offhand) as kit <white>" + a[2].toLowerCase(Locale.ROOT) + "</white>.");
            }
            case "delete" -> {
                if (a.length < 3) {
                    say(s, "<red>Usage: /duel kit delete <name>");
                    return;
                }
                if (plugin.kits().delete(a[2])) say(s, "<green>Kit deleted.");
                else say(s, "<red>No kit named " + Msg.esc(a[2]) + ".");
            }
            case "edit" -> {
                if (!(s instanceof Player p)) {
                    say(s, "<red>Run this as a player: the kit goes into your inventory.");
                    return;
                }
                if (a.length < 3) {
                    say(s, "<red>Usage: /duel kit edit <name>");
                    return;
                }
                Kit kit = plugin.kits().get(a[2]);
                if (kit == null) {
                    say(s, "<red>No kit named " + Msg.esc(a[2]) + ". (Make a new one with /duel kit set <name>.)");
                    return;
                }
                String err = plugin.kitEdits().begin(p, kit);
                if (err != null) {
                    say(s, "<red>" + Msg.esc(err));
                    return;
                }
                say(s, "<green>Editing kit <white>" + Msg.esc(kit.name()) + "</white>. Change the items however you like "
                        + "(switch to creative to grab new ones). Your own items are saved safely.");
                Msg.send(s, "<click:run_command:'/duel kit confirm'><hover:show_text:'Save the kit and get your own items back'>"
                        + "<green><bold>[CONFIRM]</bold></green></hover></click>  "
                        + "<click:run_command:'/duel kit cancel'><hover:show_text:'Discard the changes and get your own items back'>"
                        + "<red><bold>[CANCEL]</bold></red></hover></click>");
            }
            case "confirm" -> {
                if (!(s instanceof Player p)) {
                    say(s, "<red>Only players edit kits.");
                    return;
                }
                plugin.kitEdits().confirm(p);
            }
            case "cancel" -> {
                if (!(s instanceof Player p)) {
                    say(s, "<red>Only players edit kits.");
                    return;
                }
                plugin.kitEdits().cancel(p);
            }
            default -> say(s, "<red>Usage: /duel kit set|delete|edit|confirm|cancel|list [name]");
        }
    }

    // ------------------------------------------------------------------ arenas

    private void arena(CommandSender s, String[] a) {
        if (a.length < 2) {
            say(s, "<red>Usage: /duel arena create|delete|set|snapshot|regen|list|info <name>");
            return;
        }
        String op = a[1].toLowerCase(Locale.ROOT);
        if (op.equals("list")) {
            if (plugin.arenas().all().isEmpty()) {
                say(s, "<gray>No arenas yet. Start with /duel arena create <name>.");
                return;
            }
            for (Arena ar : plugin.arenas().all()) {
                Msg.send(s, "<yellow>" + ar.name + "</yellow> <gray>- " + (ar.isReady() ? "ready" : "not set up")
                        + ", " + ar.state.name().toLowerCase(Locale.ROOT) + (ar.dirty ? ", damaged" : ""));
            }
            return;
        }
        if (a.length < 3) {
            say(s, "<red>Usage: /duel arena " + op + " <name>");
            return;
        }
        String name = a[2];

        if (op.equals("create")) {
            if (!NAME.matcher(name).matches()) {
                say(s, "<red>Arena names can only use letters, numbers, - and _.");
                return;
            }
            Arena created = plugin.arenas().create(name);
            if (created == null) {
                say(s, "<red>An arena with that name already exists.");
                return;
            }
            say(s, "<green>Created arena <white>" + created.name + "</white>. Next: stand at two opposite corners and run "
                    + "<white>/duel arena set " + created.name + " pos1</white> and <white>pos2</white>, set both spawns, "
                    + "then run <white>/duel arena snapshot " + created.name + "</white>.");
            return;
        }

        Arena arena = plugin.arenas().get(name);
        if (arena == null) {
            say(s, "<red>No arena named " + Msg.esc(name) + ".");
            return;
        }

        switch (op) {
            case "delete" -> {
                if (plugin.arenas().delete(arena)) say(s, "<green>Arena deleted.");
                else say(s, "<red>That arena is in use right now.");
            }
            case "set" -> {
                if (a.length >= 4 && a[3].equalsIgnoreCase("maxplayers")) {
                    setMaxPlayers(s, arena, a);
                    return;
                }
                if (!(s instanceof Player p)) {
                    say(s, "<red>Run this as a player: it uses where you are standing.");
                    return;
                }
                if (a.length < 4 || !POINTS.contains(a[3].toLowerCase(Locale.ROOT))) {
                    say(s, "<red>Usage: /duel arena set " + arena.name + " pos1|pos2|spawn1|spawn2|maxplayers <number>");
                    return;
                }
                if (arena.state != Arena.State.IDLE) {
                    say(s, "<red>That arena is busy right now.");
                    return;
                }
                String which = a[3].toLowerCase(Locale.ROOT);
                String err = null;
                switch (which) {
                    case "pos1" -> err = plugin.arenas().setRegionPoint(arena, true, p.getLocation());
                    case "pos2" -> err = plugin.arenas().setRegionPoint(arena, false, p.getLocation());
                    case "spawn1" -> plugin.arenas().setSpawn(arena, true, p.getLocation());
                    default -> plugin.arenas().setSpawn(arena, false, p.getLocation());
                }
                if (err != null) {
                    say(s, "<red>" + Msg.esc(err));
                    return;
                }
                String extra = which.startsWith("pos") ? " <gray>(the snapshot must be taken again)" : "";
                say(s, "<green>Set " + which + " of " + arena.name + " to your position." + extra);
            }
            case "snapshot" -> {
                say(s, "<gray>Saving the arena's current blocks as its baseline. This may take a few seconds...");
                plugin.arenas().capture(arena, err -> {
                    if (err != null) say(s, "<red>Snapshot failed: " + Msg.esc(err));
                    else say(s, "<green>Snapshot saved (" + arena.volume() + " blocks). The arena will be restored to this layout.");
                });
            }
            case "regen" -> {
                if (arena.state != Arena.State.IDLE) {
                    say(s, "<red>That arena is busy right now.");
                    return;
                }
                if (!arena.hasSnapshot) {
                    say(s, "<red>That arena has no snapshot yet.");
                    return;
                }
                say(s, "<gray>Regenerating " + arena.name + "...");
                plugin.arenas().regenerate(arena, err -> say(s, err == null
                        ? "<green>Arena regenerated."
                        : "<red>Regeneration failed: " + Msg.esc(err)));
            }
            case "info" -> {
                Msg.send(s, "<yellow>Arena " + arena.name + "</yellow> <gray>(" + arena.state.name().toLowerCase(Locale.ROOT) + ")");
                if (arena.hasRegion()) {
                    Msg.send(s, "<gray>Region: " + arena.worldName() + ", " + arena.sizeX() + " x " + arena.sizeY() + " x " + arena.sizeZ()
                            + " = " + arena.volume() + " blocks");
                } else {
                    Msg.send(s, "<gray>Region: <red>pos1/pos2 not set");
                }
                Msg.send(s, "<gray>Spawn 1: " + (arena.spawn1 == null ? "<red>not set" : arena.spawn1.pretty()));
                Msg.send(s, "<gray>Spawn 2: " + (arena.spawn2 == null ? "<red>not set" : arena.spawn2.pretty()));
                Msg.send(s, "<gray>Max players: <white>" + (arena.maxPlayers > 0 ? String.valueOf(arena.maxPlayers) : "no limit"));
                Msg.send(s, "<gray>Snapshot: " + (arena.hasSnapshot ? "<green>saved" : "<red>missing")
                        + "<gray>, damaged since last repair: " + (arena.dirty ? "<red>yes" : "<green>no"));
            }
            default -> say(s, "<red>Usage: /duel arena create|delete|set|snapshot|regen|list|info <name>");
        }
    }

    private void setMaxPlayers(CommandSender s, Arena arena, String[] a) {
        int n = -1;
        if (a.length >= 5) {
            try {
                n = Integer.parseInt(a[4]);
            } catch (NumberFormatException ignored) {
                // handled below
            }
        }
        if (n < 0) {
            say(s, "<red>Usage: /duel arena set " + arena.name + " maxplayers <number>  (0 means no limit)");
            return;
        }
        arena.maxPlayers = n;
        plugin.arenas().save();
        if (n == 0) say(s, "<green>Arena <white>" + arena.name + "</white> has no player limit now.");
        else say(s, "<green>Arena <white>" + arena.name + "</white> now holds at most " + n + " players.");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("ff")) return List.of();
        if (args.length == 1) {
            List<String> roots = new ArrayList<>();
            roots.addAll(List.of("help", "forfeit", "leave", "accept", "decline", "cancel"));
            if (sender.hasPermission("duelevent.layout")) roots.add("kitlayout");
            if (sender.hasPermission("duelevent.top")) roots.add("top");
            if (sender.hasPermission("duelevent.start")) roots.addAll(List.of("start", "stop", "list"));
            if (sender.hasPermission("duelevent.arena")) roots.add("arena");
            if (sender.hasPermission("duelevent.kit")) roots.add("kit");
            if (sender.hasPermission("duelevent.reload")) roots.add("reload");
            return filter(roots, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        String current = args[args.length - 1];

        if (sub.equals("start") && sender.hasPermission("duelevent.start")) {
            return startCompletions(args, current);
        } else if ((sub.equals("stop") || sub.equals("cancel")) && sender.hasPermission("duelevent.start")) {
            if (args.length == 2) return filter(onlineNames(), current);
        } else if (sub.equals("kitlayout") && sender.hasPermission("duelevent.layout")) {
            if (args.length == 2) return filter(new ArrayList<>(plugin.kits().names()), current);
        } else if ((sub.equals("accept") || sub.equals("decline")) && sender instanceof Player p) {
            if (args.length == 2) return filter(plugin.requests().incomingNames(p.getUniqueId()), current);
        } else if (sub.equals("top")) {
            if (args.length == 2 && sender.hasPermission("duelevent.reset")) return filter(List.of("reset"), current);
            if (args.length == 3 && args[1].equalsIgnoreCase("reset") && sender.hasPermission("duelevent.reset")) {
                return filter(List.of("confirm"), current);
            }
        } else if (sub.equals("kit") && sender.hasPermission("duelevent.kit")) {
            if (args.length == 2) {
                boolean editing = sender instanceof Player ep && plugin.kitEdits().isEditing(ep.getUniqueId());
                List<String> ops = new ArrayList<>(KIT_OPS);
                if (!editing) ops.removeAll(List.of("confirm", "cancel"));
                return filter(ops, current);
            }
            if (args.length == 3 && (args[1].equalsIgnoreCase("delete") || args[1].equalsIgnoreCase("edit"))) {
                return filter(new ArrayList<>(plugin.kits().names()), current);
            }
        } else if (sub.equals("arena") && sender.hasPermission("duelevent.arena")) {
            if (args.length == 2) return filter(ARENA_OPS, current);
            if (args.length == 3 && !args[1].equalsIgnoreCase("create") && !args[1].equalsIgnoreCase("list")) {
                return filter(arenaNames(), current);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("set")) return filter(ARENA_SET, current);
        }
        return List.of();
    }

    /** Suggestions for /duel start [arena x] [kit y] blue ... red ... based on the words typed so far. */
    private List<String> startCompletions(String[] args, String current) {
        String section = null;
        String pending = null; // "arena" or "kit" while its value is being typed
        boolean arenaSet = false;
        boolean kitSet = false;
        Set<String> used = new HashSet<>();
        for (int i = 1; i < args.length - 1; i++) {
            String word = args[i].toLowerCase(Locale.ROOT);
            if (pending != null) {
                pending = null;
            } else if (word.equals("blue") || word.equals("red")) {
                section = word;
            } else if (section != null) {
                used.add(word);
            } else if (word.equals("arena")) {
                arenaSet = true;
                pending = "arena";
            } else if (word.equals("kit")) {
                kitSet = true;
                pending = "kit";
            }
        }
        if (pending != null) {
            return filter(pending.equals("arena") ? arenaNames() : new ArrayList<>(plugin.kits().names()), current);
        }
        List<String> options = new ArrayList<>();
        if (section != null) {
            for (String name : onlineNames()) {
                if (!used.contains(name.toLowerCase(Locale.ROOT))) options.add(name);
            }
        } else {
            if (!arenaSet) options.add("arena");
            if (!kitSet) options.add("kit");
        }
        options.add("blue");
        options.add("red");
        return filter(options, current);
    }

    private List<String> onlineNames() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        return out;
    }

    private List<String> arenaNames() {
        List<String> out = new ArrayList<>();
        for (Arena ar : plugin.arenas().all()) out.add(ar.name);
        return out;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }
}
