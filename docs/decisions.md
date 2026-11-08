# Decisions

The owner's decisions about Homesteader, with dates. Group and world names are left out on purpose.

## 2026-11-07

- **What it is.** A starter kit on a player's first visit to each inventory group, with an optional one-time move to a safe spot. It never claims land and never changes blocks. GriefPrevention creates a claim when the first chest is placed, if it's set up to.
- **The starter.** A chest and a little food by default, set per group in config.
- **No safe spot.** The player stays where they are and is told. They still get the starter. The move isn't recorded as used, so it's tried again on their next visit.
- **Wiped groups.** No automatic reset. An admin resets a player, or a whole group, with a command.
- **Starter commands.** A starter can run console commands with `{player}` filled in, once, under the same once-per-player-and-group record, after the inventory swap. A failing command is logged and doesn't stop the rest. This exists for items another plugin must give itself; the first use is a magic plugin's wand box, for one group.
- **Commands never run twice.** Each command is recorded just before it runs. If a crash cuts one off, it shows in the log and an admin runs it by hand. The decided rule is never two starters, and that includes command items.
- **Mark as done.** An admin command marks a player done for a group without giving anything, for players who got their starter by hand earlier. It survives restarts.
- **Versions.** Minecraft 26.1 and up only, on Java 25, tested on 26.1, 26.2 and 26.3. No 1.21.x. Modern Paper APIs where they make the code simpler or sturdier, and a short audit in `docs/26x-audit.md`.
- **Paper only.** paper-api and `paper-plugin.yml`, not Spigot.
- **Repo.** `ExtantApe/Homesteader`, private until the owner says otherwise.
- **Testing.** Throwaway local servers on 127.0.0.1, one at a time, deleted after each run. The owner accepted the Minecraft EULA for them.
- **Done well, not just done.** Clear, welcoming messages, sensible defaults, and wording that doesn't rely on colour.
