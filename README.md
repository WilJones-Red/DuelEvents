# DuelEvent

A Paper plugin for running duels at server events, with teams of any size up to a limit you set (16 players in
total by default, so 8v8, 10v6 or 15v1 all work). An operator picks the players, or players set duels up themselves
from a menu; everyone is teleported into a preset arena with a kit, then returned with their original items afterwards. The arena can be
destroyed during the fight and is rebuilt automatically.

Built for Paper 26.x (Java 25). Version 1.2.4 compiled successfully against Paper 26.3. Live server testing is still required.

The compiled plugin is available at [`releases/DuelEvent-1.2.4.jar`](releases/DuelEvent-1.2.4.jar). The editable Java source is in `src/`. RamCore 1.5.1 adds a Duels button that opens this separate plugin's menu.

## Building

You need JDK 25. Use the included Gradle wrapper from this folder:

    ./gradlew build

On Windows, use `gradlew.bat build`.

The plugin jar is `build/libs/DuelEvent-1.2.4.jar`. Put it in your server's `plugins` folder and restart.

If Gradle can't find the 26.3 API, edit `gradle.properties` and set `paperApi=26.2.build.+`.
The plugin declares `api-version: 26.2`, so it still loads on a 26.3 server.

## First-time setup (needs the arena and kit permissions, which ops have by default)

1. **Create an arena:** `/duel arena create arena1`
2. **Mark the region.** Stand at one corner, then the opposite corner (include floor, walls and some air above):
   `/duel arena set arena1 pos1` and `/duel arena set arena1 pos2`
3. **Set the starting spots:** stand where player 1 should start and run `/duel arena set arena1 spawn1`,
   then the same for `spawn2` (it saves your facing direction too).
4. **Save the layout:** build the arena exactly how it should always look, then run `/duel arena snapshot arena1`.
   Run this again whenever you change the build.
5. **Make a kit:** put the items you want in your own inventory (armor and offhand included),
   then run `/duel kit set starter`. This copies your inventory but does not remove it.

## Running a duel

    /duel start [arena <name>] [kit <name>] blue <players...> red <players...>

Arena and kit come first and are both optional, in either order. After `blue`, every name is on the blue team until
you type `red` (and the other way round), and the two team sections can be in either order. Teams can be any size as
long as both have at least one player and the total is at most `max-team-size` in `config.yml` (16 by default).

    /duel start blue Alice red Bob
    /duel start blue Alice Bob red Carol Dave
    /duel start arena arena1 kit starter blue Alice Bob Carol red Dave Eve
    /duel start red Dave Eve blue Alice Bob Carol

Leave out the arena to pick the first free one that fits. Leave out the kit to use `default-kit` from
`config.yml` (or the only kit, if you have just one). Everyone gets the same kit. (Don't give a player a name
that is also one of the words `blue`, `red`, `arena` or `kit`.)

What happens: the arena is repaired first (by default), then everyone's inventory, location, health, hunger, XP,
game mode and potion effects are saved to disk, they get the kit and are teleported in, frozen for a countdown,
then fight. Each team spawns in a grid: the arena's spawn point for that side, then the blocks to its right, then the next
row behind it, and so on. (If a spot is blocked or outside the arena, that player spawns on the spawn point
itself.) Make sure there's open space around each spawn point, and for big teams set `maxplayers` on small arenas
(see below).

When a player would die, they aren't killed. They become a **spectator** where they fell and can't leave the
arena (they can hop to players through the spectator menu). A team loses when all its players are eliminated.
Then everyone is sent back with everything restored: items, position, game mode and health.

### Team colours

The two teams are **blue** (the first one listed) and **red**, whatever their sizes. The armor from the kit gets a
trim in the team's colour. By default blue uses diamond trims and red uses redstone trims, with the `flow` pattern on
helmet and chestplate, `tide` on boots and nothing on leggings. Change any of it under `team-trims:` in `config.yml`
(a pattern of `none` skips that piece). Trims are only applied to armor in the kit's armor slots.

### Kit layouts (everyone)

`/duel kitlayout [kit]` opens a window showing the kit: main inventory, hotbar, the (fixed) armor and the offhand
slot. Players can move items around however they like, then **Save**, **Reset to default** or **Cancel**. Only the
kit's own items can be moved, nothing can be added or removed, and the result is checked before it's saved. A saved
layout is used instead of the kit's normal arrangement in that player's duels. Without a kit name, a kit picker opens.

Layouts are saved in `plugins/DuelEvent/layouts/<kit>/<player>.yml`. If a kit is changed (`/duel kit set` on an
existing name) or deleted, every player's saved layout of it, files included, is wiped at once. If a kit file is
edited by hand, the outdated layouts are wiped on the next startup or `/duel reload`.

### Setting up duels as a player

`/duel` with no arguments opens a menu with everything the player may do. **Set up a duel** shows blue on the left
and red on the right: use the add buttons to pick online players (shown as heads) for either team in any order, and click
a head to remove a player. The kit and arena buttons open their own menus: the kit menu has an icon per kit, and the
arena menu starts with "Any free arena" and then lists every arena with its status (available, in use, too small for
the players you picked, or not set up). **Send invites** then invites everyone you added (you start on the blue team,
and can remove yourself to organise a duel for others).

* Invites show up in chat with clickable **[ACCEPT]** and **[DECLINE]** buttons; each one names who sent it, so
  with several invites you click the one you want. `/duel accept [player]` and `/duel decline [player]` do the same.
* The duel starts once everyone has accepted. Invites expire after `player-duels.invite-timeout-seconds` (60).
* A player can have only one request out at a time. `/duel cancel` withdraws it.
* A request is cancelled if anyone in it goes offline or joins another duel.
* `player-duels.cooldown-seconds` (0 by default) is a wait after a finished player-run duel before the next request.
* Teams can be any size, in either direction. The menu fits up to 20 players per team.
* Player-run duels don't count on the leaderboard, don't run `winner-commands` and aren't announced to the server
  unless you turn those on under `player-duels:` in `config.yml`. `player-duels.enabled: false` turns the feature off.

### Arena size limits

`/duel arena set <name> maxplayers <number>` sets how many players an arena holds in total (`0` means no limit).
Duels with more players refuse to use it, and "any free arena" skips arenas that are too small. The setup menu warns
when the chosen arena is too small.

### Editing a kit

`/duel kit edit <kit>` (needs `duelevent.kit`) saves your own items to disk and puts the kit in your inventory exactly
as stored. Change it however you like (switch to creative to grab new items), then:

* `/duel kit confirm` saves your inventory as the kit and gives your own items back. Saved player layouts of that
  kit are reset, as with any kit change. If nothing changed, nothing is saved.
* `/duel kit cancel` gives your own items back and leaves the kit alone.

Chat shows clickable [CONFIRM] and [CANCEL] buttons. If you die, disconnect or the server stops while editing, it counts
as a cancel and your own items come back (on respawn, or when you rejoin). You can't start duels, send or accept
requests, or use the layout editor while editing. `/duel kit set <name>` is still how you create a new kit.

### Watching and leaving

An eliminated player becomes a spectator. `/duel leave` (or the button in `/duel`) lets them stop watching early and
get their items and position back right away. It doesn't change the result.

### Forfeiting

`/ff` (also `/forfeit` and `/duel forfeit`) starts a vote. Only your own team sees the messages. Once every
living player on the team has typed `/ff`, the whole team forfeits at once. A vote that isn't finished within 30
seconds (`forfeit.vote-timeout-seconds`) is dropped. Eliminated players don't vote. A solo player's `/ff`
forfeits immediately.

If a player leaves the server mid-fight, they count as eliminated (the rest of their team keeps going).

### Stopping a duel (operators)

`/duel stop <any player in the duel>` cancels that duel with no winner and no win recorded.

### Leaderboard

`/duel top` shows the 5 players with the most duel wins. `/duel top 2` shows the next 5, and so on.
Every player on a winning team gets a win, including teammates who were eliminated earlier. Cancelled duels
count for nothing. Wins are saved in `plugins/DuelEvent/stats.yml` and survive restarts.

`/duel top reset` (needs `duelevent.reset`, ops by default) asks you to confirm. Run `/duel top reset confirm`
within 30 seconds to permanently wipe the stats.

### Several duels at once

Each duel needs its own arena and its own players, so you can run as many at the same time as you have arenas.
Arenas can't overlap (the plugin refuses). Without an arena name, `/duel start` picks a free one.

## Keeping the arena intact

Three options in `config.yml` under `arena:`, each on or off:

| Option | Default | What it does |
|---|---|---|
| `repair-before-fight` | on | Rebuilds the arena from its snapshot right before each duel starts, so any damage since the last duel doesn't matter |
| `repair-after-fight` | off | Also rebuilds it as soon as a duel ends, so it looks right between fights |
| `protect-when-idle` | off | Stops players, explosions, fluids, fire, growth, decay and mobs from changing an arena with no duel running |

Repairs are spread over several ticks so the server doesn't lag. Before a duel, you'll see "Repairing arena..."
and the duel starts as soon as it finishes. Changes from other plugins (WorldEdit, etc.) can't be stopped by idle
protection, which is why repair-before-fight is the one to rely on. To build or edit an arena while idle protection
is on, you need `duelevent.bypass` (ops have it).

After editing the config, run `/duel reload`.

| Command | What it does |
|---|---|
| `/duel` | Open the duel menu |
| `/duel kitlayout [kit]` | Rearrange a kit's items just for you |
| `/duel accept [player]` / `/duel decline [player]` | Answer a duel invite |
| `/duel cancel` | Cancel the duel request you sent |
| `/duel leave` | Stop watching a duel after being eliminated |
| `/duel help` | List the commands you can use |
| `/duel start [arena <name>] [kit <name>] blue <players...> red <players...>` | Start a duel of any size up to `max-team-size` (operator) |
| `/duel stop <player>` | Cancel the duel that player is in (no winner) |
| `/duel list` | Show running duels |
| `/ff` or `/forfeit` | Vote with your team to forfeit |
| `/duel top [page]` | Show the win leaderboard |
| `/duel top reset` then `/duel top reset confirm` | Wipe the leaderboard (admin) |
| `/duel kit set <name>` / `delete <name>` / `list` | Manage kits |
| `/duel kit edit <name>` then `/duel kit confirm` or `/duel kit cancel` | Edit a kit in your inventory, then save or discard |
| `/duel arena create / delete / info / snapshot / regen <name>` | Manage arenas |
| `/duel arena set <name> pos1\|pos2\|spawn1\|spawn2` | Set points from where you stand |
| `/duel arena set <name> maxplayers <number>` | Limit how many players the arena holds (0 = no limit) |
| `/duel reload` | Reload `config.yml` and kits |

## Permissions

Admin permissions default to ops only; the player features (`/duel`, forfeit, kit layouts, setting up duels, leaderboard) default to everyone. Use LuckPerms to hand out individual pieces:

| Permission | Allows | Default |
|---|---|---|
| `duelevent.admin` | All the admin permissions below | op |
| `duelevent.start` | `/duel start`, `stop`, `list` | no one (via admin) |
| `duelevent.arena` | all `/duel arena ...` commands | no one (via admin) |
| `duelevent.kit` | all `/duel kit ...` commands | no one (via admin) |
| `duelevent.reload` | `/duel reload` | no one (via admin) |
| `duelevent.reset` | `/duel top reset` | no one (via admin) |
| `duelevent.top` | `/duel top` | everyone |
| `duelevent.participate` | `/ff`, `/forfeit` | everyone |
| `duelevent.create` | Set up duels and send requests from the menu | everyone |
| `duelevent.layout` | `/duel kitlayout` | everyone |
| `duelevent.bypass` | use any command during a duel, build near a running arena | op |

Example "duel host" group that can run events but can't change arenas or kits:

    /lp creategroup duelhost
    /lp group duelhost permission set duelevent.start true
    /lp user <name> parent add duelhost

## Using it with WorldGuard

Duelists get their own rules, so you don't have to model the arena as a WorldGuard region. Make one simple
region around the whole arena and viewing area with building and PvP denied (and `tnt` etc. as you like).
Inside a running duel's arena, the plugin lifts those denials for the players who are fighting: they can break and
place blocks, use items, and hurt each other (subject to friendly fire). Everyone else, including anyone watching,
stays bound by WorldGuard, and the plugin's own arena protection applies on top.

It does this by reversing the other plugin's cancellations for those fighters, so it works with other protection
plugins too. It only applies to players in the fight, inside their own arena box, while the duel is running. Turn
it off with `rules.override-protection-plugins: false`. A plugin that handles things after this one, or that
removes blocks from an explosion instead of cancelling it, can still get in the way.

## Safety

- A player's items are written to `plugins/DuelEvent/playerdata/` **before** anything is taken. If saving fails,
  the duel doesn't start and nothing changes.
- If a player disconnects or the server crashes mid-duel, their saved file stays and they get everything back
  the next time they join.
- An arena that has been used is flagged as damaged until it's repaired. If a repair fails, the duel doesn't start
  and the console says why; run `/duel arena regen <name>` to retry. With `repair-after-fight` on, an arena left
  damaged by a crash is also repaired when the server starts.

## Rules enforced during a duel

- Only players in the same duel can hurt each other, and teammates can't hurt each other unless
  `rules.friendly-fire` is on (it covers melee and projectiles; splash potions and explosions from a teammate may
  still hurt). Nobody else can build in or damage a running arena.
- Duelists can only build and break inside the arena region, can't leave it, and can't use commands except
  `/duel`, `/ff` and `/forfeit` (more can be allowed in the config). Ender pearls and chorus fruit work inside the arena only. Nether portals are blocked.
- Explosions, water, lava, fire and pistons can't affect blocks outside a running arena.
- Item drops and ender chests are blocked, so kit items can't be smuggled out.
- Dropped items, arrows, TNT, falling blocks and XP orbs inside the arena are cleared at the end.
- A totem of undying in hand is respected (it can save a player instead of ending the duel).

## Config

See `plugins/DuelEvent/config.yml`: the three arena options above, countdown length, delay before players are
sent back, default kit, whether results are broadcast, console commands to run for the winner (`{winner}` and
`{loser}` are replaced with names), allowed commands, and repair speed.

If you already have a `config.yml` from an earlier version, it isn't overwritten. The new `arena:` options use
their defaults until you add them, or delete the old file so a fresh one is created.

## Known limits

- **Block contents aren't restored.** Blocks are put back exactly, but the contents of chests, furnaces
  and similar blocks are not. Keep containers out of the arena (or accept that they reset empty).
- Mobs inside the arena are not removed or restored. Keep the arena mob-free.
- Players not in the duel have no built-in way to spectate. Operators can fly around in creative, and everyone
  else can watch from outside the region.
- One duel per arena at a time, and a player can only be in one duel.
- Splash potions and explosions from a teammate aren't covered by the friendly-fire setting.
- If a `/ff` clashes with another plugin's command, use `/duelevent:ff`.
- Very large arenas take a while to snapshot and rebuild (default limit is 4,000,000 blocks).
