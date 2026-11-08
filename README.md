# Homesteader

A Paper plugin that gives players a starter kit on their first visit to each world group, and can move them once to a safe spot away from spawn. It's a starter kit, not a land claim plugin: it never claims land and never changes a block.

**Status:** pre-release. It has passed an automated test suite on throwaway Paper 26.1.2, 26.2 and 26.3 servers with Multiverse-Core, Multiverse-Inventories and GriefPrevention, driven by protocol bots. It hasn't been seen in a real game client yet, so nobody has judged how a landing looks while chunks load, or how the messages read. See [Testing](#testing).

## What it does

A server-wide first-join kit only fires once per server. Homesteader works per world group instead. With Multiverse-Inventories, each group has its own inventory, so a player arriving in a new group starts empty, and Homesteader gives that group's starter there.

- **Once per player and group.** Leaving and coming back, dying, relogging and restarts never repeat it. The record is a plain YAML file.
- **After the inventory swap.** It waits a few ticks after a player arrives, then checks they're still in the same world. A player who hops straight through a group gets nothing there, and items never land in another group's inventory.
- **Items, commands, or both.** Items are listed in config. Console commands run once with `{player}` filled in, for items another plugin has to give itself. A failing command is logged and the rest still run.
- **Full inventories.** If the items don't fit, nothing is given or dropped. Each command also needs one free slot, because a command that gives an item drops it when there's no room. The player is told how many slots to free, and the starter arrives within a few seconds of making room.
- **An optional one-time move.** Only on the first visit, and only in the world you name. Players land between a minimum and maximum distance from spawn, or from a point you choose.

A safe landing has solid ground with three open blocks above it, and the ground may not be leaves. Nothing within four blocks may be water, lava, magma, fire, cactus, powder snow or similar, from two blocks below the ground to six above it. That's wider than it looks like it needs to be, because water in a newly generated chunk only starts to flow a few ticks later, and a spring nearby can still be dry when the spot is checked. Within two blocks, the ground may not rise or drop by more than two, so it isn't a cliff edge or a pillar, and that includes ground hidden under leaves. The ground is found from the sky down, so a landing is never in a cave. It also stays inside the world border, and a set distance away from any GriefPrevention claim.

The search loads one chunk per try, prefers chunks that already exist, and does the block checks off the main thread. Only two searches run at once. If no safe spot turns up, the player stays where they are and is told, they still get the starter, and the move is tried again on their next visit.

## Limits

- **Crashes.** A crash rolls a player's inventory back to its last save, and Multiverse-Inventories saves a group's inventory when the player leaves the group or logs out. So the record stays "pending" until then, and only says "done" once the starter is saved. While it's pending, each visit looks for the starter's items, which carry a hidden tag. If a crash took them, they're given again, so a crash never leaves a player without them, and never gives them twice. Commands are different: each is recorded just before it runs and never runs twice, so an item a command gave can be lost in a crash and isn't given again. The log shows every command that ran, so an admin can run a lost one by hand.
- **Existing players.** With `skip-existing-players: true`, a player who has no record yet and arrives with anything in that group's inventory, anything in its ender chest, or any XP, is marked done with nothing given. That covers players from before Homesteader was turned on. An existing player with an empty inventory and no XP gets the starter. Turn the setting off if existing players should get it too.
- **Ceilings.** Worlds with a roof, like the Nether, have no open sky, so no spot passes there.
- **Claims.** Only GriefPrevention is supported. If it's installed but its claims can't be read, nobody is moved.
- **First chest.** GriefPrevention, not Homesteader, decides whether placing the starter chest creates a claim.

## Install

1. Put `Homesteader-<version>.jar` in `plugins/`. Multiverse-Inventories and GriefPrevention are optional.
2. Start the server once, then edit `plugins/Homesteader/config.yml`. Every group starts turned off.
3. Run `/homesteader reload`.

Requires Paper 26.1 or newer and Java 25. It's tested on 26.1.2, 26.2 and 26.3. Minecraft 1.21.x isn't supported.

## Config

```yaml
settle-ticks: 10          # wait after arriving, so the inventory swap is finished
claims: auto              # auto, griefprevention or none

groups:
  example-survival:       # with no 'worlds', the Multiverse-Inventories group of this name
    enabled: true
    worlds: []
    skip-existing-players: true
    starter:
      items: [CHEST, BREAD 8]
      commands: []        # e.g. "give {player} stick"
    move:
      enabled: true
      world: example_world
      center: spawn       # or "x,z"
      min-distance: 300
      max-distance: 1500
      claim-buffer: 48
      border-margin: 16
      attempts: 16        # chunks to try, at most one loaded per try
```

Messages are in MiniMessage format, under `messages`, and a group can override any of them under its own `messages`. A group whose starter is only a command, for example, can say what actually arrived. Leave one empty to send nothing. The words carry the meaning on their own, so they read the same for players who can't tell the colours apart.

## Commands

All need `homesteader.admin`, which ops have by default.

| Command | What it does |
| --- | --- |
| `/homesteader status <player>` | What the player has had, per group. |
| `/homesteader reset <player> <group>` | Gives the starter, and the move if it's on, again on the next visit. |
| `/homesteader reset-all <group>` | The same for everyone with a record in the group. Use it after wiping a group. |
| `/homesteader done <player> <group>` | Marks a player as done without giving anything, for someone who got their starter by hand. |
| `/homesteader findspot <group> [count]` | Runs the move search without moving anyone, and logs each spot. |
| `/homesteader reload` | Re-reads `config.yml` and `records.yml`. |

## The record file

`plugins/Homesteader/records.yml` holds one entry per player and group. Every change is written to a temporary file, flushed to disk and moved into place, so the file on disk is always a complete version.

If the file can't be read, Homesteader copies it to `records.yml.corrupt-<time>`, leaves the original where it is, and gives nothing to anyone until you fix or remove it and run `/homesteader reload`. Admins see a warning when they join. A damaged file never re-gifts everyone.

## Testing

`./gradlew build` runs the unit tests: the landing rules against made-up terrain, including 2,000 random layouts, and the record file, including damaged files.

`test/suite.mjs` runs the acceptance tests on a throwaway server built from downloaded jars, with made-up world and group names, on 127.0.0.1. Port 25614 by default, or set `HS_TEST_PORT`.

```sh
cd test && npm ci
HS_TEST_JAVA=/path/to/java25 HS_TEST_WORK=/tmp/hs-work HS_TEST_JARS=/path/to/jars node suite.mjs 26.2
```

The version is `26.1.2`, `26.2` or `26.3`. `HS_TEST_JARS` needs `paper-<version>.jar`, Multiverse-Core 5.8.2, Multiverse-Inventories 5.3.6, GriefPrevention 16.18.7, and ViaVersion and ViaBackwards. The bots speak 1.21.11 through ViaBackwards; Homesteader runs entirely on the server, so that only changes how the bots see the world. On 26.3, Paper kicks a bot whose movement packets come through ViaBackwards, so there the bots send none and only the server moves them. The suite writes `eula=true` into the servers it creates, which accepts Mojang's EULA for them, so only run it if you accept it.

It checks once-only across worlds, relogs, deaths and restarts; separate groups; groups it doesn't know; hopping in and out before the swap and during the search; full inventories; starter commands; mark as done; existing inventories; disconnects and `kill -9` between each step; hundreds of searches over made-up water, lava, leaves, magma and cliffs, plus generated terrain, with a bot checking every landing itself; a small border and claims; eight players arriving at once; and a damaged record file. The `homesteader.test.*` system properties add pauses between steps for the crash tests. Don't set them on a real server.

What the suite can't show: a real client's view of a landing, and how long chunks take to appear for a real player. [docs/26x-audit.md](docs/26x-audit.md) lists what was modernised for 26.x and what stayed.

## License

MIT. See [LICENSE](LICENSE).
