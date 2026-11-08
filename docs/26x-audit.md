# 26.x audit

Homesteader targets Paper 26.1 and up, on Java 25. It was first written against the 1.21.11 API, then moved before its first release. 1.21.x is no longer supported. The code compiles against the 26.1.2 API with deprecation and removal warnings turned on, and there are none.

Checked against Paper's docs on 2026-11-07: Paper plugins, Brigadier command registration, persistent data containers, and the data component API.

## Modernised

- **`paper-plugin.yml` instead of `plugin.yml`.** It declares Multiverse-Core, Multiverse-Inventories and GriefPrevention as optional server dependencies that load first. Multiverse-Inventories joins the classpath so Homesteader can read its groups. GriefPrevention is read by reflection, so it doesn't. Paper's docs still mark Paper plugins as experimental. That's a small risk, and the acceptance suite runs on every supported version to cover it.
- **Brigadier commands through the lifecycle API.** `/homesteader` is a command tree with typed arguments, a number range for `findspot`'s count, and suggestions for groups and online players. Permission checks happen in the tree, so players without `homesteader.admin` don't see the command. `reset <player|*>` became `reset <player>` and `reset-all <group>`, because Brigadier's word argument doesn't accept `*`. The commands are registered in `onEnable`, which the docs allow. The bootstrapper is the other option, but it's marked highly experimental, and it only matters for datapack functions, which Homesteader doesn't use.
- **ItemStack's own persistent data.** The tag on starter items is read and written with `ItemStack#getPersistentDataContainer` and `editPersistentDataContainer`, without building an `ItemMeta`.
- **Java 25 toolchain and bytecode,** with `api-version: '26.1'`.

## Kept, and why

- **Persistent data, not data components, for the tag.** The data component API covers vanilla components, says nothing about plugin data, and doesn't promise compatibility between versions. Persistent data does.
- **Adventure and MiniMessage.** Messages already used them, with no legacy colour codes anywhere.
- **Bukkit events** for join, world change, respawn and quit. Paper has no lifecycle replacement for gameplay events.
- **`getChunkAtAsync` and `ChunkSnapshot`** for the spot search. They're still the supported way to load a chunk without blocking and read it off the main thread.
- **No dialogs or display entities.** Homesteader shows one or two chat lines, so a dialog would add a click for nothing. It has nothing to display in the world.
- **Records over classes, and few other newer language features.** The code is small and plain. Rewriting it for newer syntax wouldn't make it simpler.

## Tested

The acceptance suite runs on throwaway Paper 26.1.2, 26.2 and 26.3 servers, with Multiverse-Core 5.8.2, Multiverse-Inventories 5.3.6 and GriefPrevention 16.18.7. The bots are mineflayer clients speaking 1.21.11 through ViaVersion and ViaBackwards 5.12.2, because mineflayer doesn't speak 26.2 or 26.3. Homesteader runs entirely on the server, so the client version only changes how the bots see the world. On 26.3, Paper kicks a bot whose movement packets come through ViaBackwards ("Invalid move player packet received"), so there the bots send none and are moved only by server teleports. Homesteader never needs the client to move, but 26.3 hasn't been tested with a walking player. Nothing in the suite is mocked: the servers, the plugins and the worlds are real, and the players are bots. A real game client hasn't been tried.
