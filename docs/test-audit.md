# Test audit

Kept by this repo's owning session (Minecraft: Plugins Lead), per `~/Code/CLAUDE.md`, "Test self-audits". Newest first.

## 2026-11-10: first full audit (at 0.1.0)

What Homesteader promises (README and `docs/decisions.md`), and what proves it. "Unit" is `src/test`; "live" is `test/suite.mjs` on throwaway Paper 26.1.2, 26.2 and 26.3 servers with Multiverse-Core, Multiverse-Inventories, GriefPrevention and bots.

### Covered

**Once per player and group**
- **Never repeated by leaving, dying, relogging or restarts:** live "leaving and returning", "relog", "death and respawn", "records survived the restart".
- **Items never land in another group's inventory; hopping straight through gives nothing:** live "beta items never appear in alpha", "in and out of alpha within the settle time".
- **Crashes and disconnects at every step give exactly one starter:** live, four `kill -9` points and two disconnect points.
- **A damaged record file is kept with a copy, nobody gets anything, admin commands won't write over it, and fixing it works:** unit `damagedFileIsKeptReportedAndBlocksChanges` and three more; live.

**Full inventories**
- **Nothing given or dropped until there's room, and the player is told how many slots:** live, with a count of every stone.

**Existing players and admins**
- **`skip-existing-players` detects inventory, ender chest and XP:** live. **New in this audit:** only XP, and only an ender-chest item, each count as existing (they had no checks; the existing-players section passed 9/9 on Paper 26.1.2).
- **Only `homesteader.admin` can use the admin commands:** live "a player without homesteader.admin can't", with an op as the control.

**The one-time move**
- **A safe landing, every rule:** unit `SpotRulesTest`, 15 tests, and `randomTerrainOnlyEverYieldsSafeSpots`; live, 300 searches over hazards, and a bot that checked every landing itself.
- **Inside the ring and the world border:** unit `AnnulusTest`; live.
- **Never in or within 12 blocks of a claim:** live, with real claims.
- **New in this audit: if GriefPrevention's claims can't be read, every spot counts as near a claim, so nobody is moved.** Unit `GriefPreventionClaimsTest` (5 tests, with a positive control): a store with no `getClaims`, claims that can't be listed, a claim from a renamed API, and something that isn't a claim. The code already did this; nothing tested it.
- **No safe spot: they stay, are told, still get the starter, and the move is tried again next visit:** live.
- **Never changes a block:** live, a scan of the plugin for any block-changing call.

### Skipped, with reasons

- **How a landing looks while chunks load, and how the messages read:** real-client list.
- **The fallback `ClaimCheck` in `HomesteaderPlugin` when `create` throws.** It's a two-line class that always answers "near"; the new tests prove `create` throws in each broken case. Testing the plugin's start-up would need a server.
