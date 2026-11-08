package com.extantape.homesteader;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.extantape.homesteader.claims.ClaimCheck;
import com.extantape.homesteader.config.GroupSettings;
import com.extantape.homesteader.config.MoveSettings;
import com.extantape.homesteader.config.Settings;
import com.extantape.homesteader.group.Groups;
import com.extantape.homesteader.record.Record;
import com.extantape.homesteader.record.RecordStore;
import com.extantape.homesteader.spot.SpotFinder;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Watches players arrive in a group's worlds and, once per player and group, moves them (optional)
 * and gives the starter.
 *
 * <p><b>Swap timing.</b> Multiverse-Inventories swaps inventories inside its world-change and join
 * handlers. Homesteader waits {@code settle-ticks} after an arrival, then checks the player is still
 * online, alive and in the same world. If they've moved on, nothing happens for that arrival; the
 * next arrival schedules its own check. So items only go into the inventory of the group the player
 * is standing in.
 *
 * <p><b>Order, so a crash never gives two starters or none for good.</b>
 * <ol>
 *   <li>Write the record as PENDING with a fresh grant id.</li>
 *   <li>Give the items, each tagged with that id.</li>
 *   <li>For each command: count it in the record, write, then run it. A command never runs twice;
 *       one cut off by a crash shows in the log.</li>
 *   <li>When the player leaves the group or logs out, Multiverse-Inventories saves that inventory.
 *       Then the record says DONE. On their next visit the tags are removed so the items stack.</li>
 * </ol>
 * While PENDING, each visit looks for the tagged items. Found means they arrived. Not found means a
 * crash rolled the inventory back before it was saved, so the items are given again. Only the
 * inventory counts as evidence: with Multiverse-Inventories, a vanilla player save isn't what's
 * loaded after a crash.
 */
public final class Steward implements Listener {
    private final HomesteaderPlugin plugin;
    private final NamespacedKey grantKey;
    private final Map<UUID, BukkitTask> scheduled = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, String> waitingForRoom = new HashMap<>();
    /** Groups whose starter items this player was given, or was seen holding, since they logged in. */
    private final Map<UUID, Set<String>> holding = new HashMap<>();
    /** Players who arrived in a group while the record file was damaged, so they get nothing yet. */
    private final Map<UUID, Set<String>> visitedWhileDamaged = new HashMap<>();

    // Test-only pauses, in ticks, so the acceptance tests can kill the server between steps.
    private final int pauseAfterMove = Integer.getInteger("homesteader.test.pause-after-move", 0);
    private final int pauseAfterGive = Integer.getInteger("homesteader.test.pause-after-give", 0);

    public Steward(HomesteaderPlugin plugin) {
        this.plugin = plugin;
        this.grantKey = new NamespacedKey(plugin, "grant");
        if (pauseAfterMove + pauseAfterGive > 0) {
            plugin.getLogger().warning("Test pauses are on (after move " + pauseAfterMove + ", after give "
                    + pauseAfterGive + " ticks). Don't use this on a real server.");
        }
    }

    private Settings settings() {
        return plugin.settings();
    }

    private Groups groups() {
        return plugin.groups();
    }

    private RecordStore store() {
        return plugin.store();
    }

    // ---- Events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (store().problem() != null && p.hasPermission("homesteader.admin")) {
            send(p, settings().messages().recordProblem());
        }
        arrive(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent e) {
        GroupSettings from = groups().forWorld(e.getFrom().getName());
        if (from != null && from != groups().forWorld(e.getPlayer().getWorld().getName())) {
            settle(e.getPlayer(), from);
        }
        arrive(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerPostRespawnEvent e) {
        arrive(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p && waitingForRoom.containsKey(p.getUniqueId())) {
            arrive(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        GroupSettings g = groups().forWorld(e.getPlayer().getWorld().getName());
        if (g != null) {
            settle(e.getPlayer(), g);
        }
        UUID id = e.getPlayer().getUniqueId();
        holding.remove(id);
        BukkitTask t = scheduled.remove(id);
        if (t != null) {
            t.cancel();
        }
        waitingForRoom.remove(id);
    }

    /**
     * After the record file is readable again: players first seen while it was damaged get an empty
     * record, so their new Multiverse-Inventories inventory isn't mistaken for an old one. Kept in
     * memory only; after a restart, use /homesteader reset for anyone it missed.
     */
    public void recordsRecovered() {
        int n = 0;
        for (Map.Entry<UUID, Set<String>> e : visitedWhileDamaged.entrySet()) {
            for (String group : e.getValue()) {
                if (!store().has(e.getKey(), group)) {
                    try {
                        store().put(e.getKey(), group, Record.EMPTY);
                        n++;
                    } catch (IOException ex) {
                        plugin.getLogger().warning("Couldn't record a visit from while the file was damaged: " + ex.getMessage());
                    }
                }
            }
        }
        visitedWhileDamaged.clear();
        if (n > 0) {
            plugin.getLogger().info(n + " visit(s) from while the record file was damaged now count as first visits.");
        }
        Bukkit.getOnlinePlayers().forEach(this::arrive);
    }

    /** Called every few seconds: players waiting for inventory room get another try. */
    public void retryWaiting() {
        for (UUID id : List.copyOf(waitingForRoom.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                waitingForRoom.remove(id);
            } else if (!scheduled.containsKey(id)) {
                process(id, p.getWorld().getUID(), false);
            }
        }
    }

    // ---- Flow ----

    /** Schedules a check once the inventory swap has settled. */
    public void arrive(Player p) {
        if (groups().forWorld(p.getWorld().getName()) == null) {
            return;
        }
        UUID id = p.getUniqueId();
        UUID world = p.getWorld().getUID();
        BukkitTask old = scheduled.remove(id);
        if (old != null) {
            old.cancel();
        }
        scheduled.put(id, Bukkit.getScheduler().runTaskLater(plugin, () -> {
            scheduled.remove(id);
            process(id, world, true);
        }, settings().settleTicks()));
    }

    private void process(UUID id, UUID worldId, boolean announce) {
        Player p = Bukkit.getPlayer(id);
        if (p == null || !p.isOnline() || p.isDead() || !p.getWorld().getUID().equals(worldId)) {
            return; // Gone, dead (respawn brings them back here) or moved on (that arrival has its own check).
        }
        if (busy.contains(id)) {
            return;
        }
        GroupSettings g = groups().forWorld(p.getWorld().getName());
        if (g == null) {
            return;
        }
        if (store().problem() != null) {
            // Damaged record file: give nothing to anyone. Remember the visit, because Multiverse-Inventories
            // now saves an inventory for them here, which would later look like an existing player.
            if (!store().has(id, g.name())) {
                visitedWhileDamaged.computeIfAbsent(id, k -> new HashSet<>()).add(g.name());
            }
            return;
        }
        try {
            if (!store().has(id, g.name())) {
                if (g.skipExisting() && lived(p)) {
                    store().put(id, g.name(), new Record(Record.Starter.EXISTING, null, 0, Record.Move.SKIPPED,
                            System.currentTimeMillis()));
                    plugin.getLogger().info(p.getName() + " arrived in '" + g.name() + "' with items or XP already, "
                            + "so they're marked done without a starter or move.");
                    return;
                }
            }
            Record rec = store().get(id, g.name());
            MoveSettings mv = g.move();
            if (mv.enabled() && !rec.move().finished()) {
                if (p.getWorld().getName().equals(mv.world())) {
                    move(p, g);
                    return;
                }
                if (rec.starter() == Record.Starter.NONE) {
                    // First visit wasn't to the move world. Moving them later, mid-game, would be a surprise.
                    store().put(id, g.name(), rec.withMove(Record.Move.SKIPPED));
                }
            }
            starter(p, g, announce);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't write the record for " + p.getName()
                    + "; gave nothing. " + e.getMessage(), e);
        }
    }

    private void move(Player p, GroupSettings g) {
        UUID id = p.getUniqueId();
        World world = p.getWorld();
        busy.add(id);
        plugin.finder().find(world, g.move(), plugin.claims()).whenComplete((result, err) -> {
            busy.remove(id);
            Player now = Bukkit.getPlayer(id);
            if (now == null || !now.isOnline()) {
                return; // Left during the search. Nothing recorded, so it's tried again next visit.
            }
            if (!now.getWorld().getUID().equals(world.getUID()) || now.isDead()) {
                arrive(now);
                return;
            }
            if (err != null || result == null || result.spot() == null) {
                String why = err != null ? err.toString() : result == null ? "no result" : result.reason();
                plugin.getLogger().info("No safe spot for " + now.getName() + " in '" + g.name() + "' (" + why
                        + "). They stay where they are; the move is tried again on their next visit.");
                send(now, g.messages().noSpot());
                starterSafely(now, g);
                return;
            }
            now.teleportAsync(result.spot()).whenComplete((ok, terr) -> Bukkit.getScheduler().runTask(plugin, () -> {
                Player after = Bukkit.getPlayer(id);
                if (after == null) {
                    return;
                }
                if (Boolean.TRUE.equals(ok) && terr == null) {
                    try {
                        store().put(id, g.name(), store().get(id, g.name()).withMove(Record.Move.DONE));
                    } catch (IOException e) {
                        plugin.getLogger().log(Level.SEVERE, "Moved " + after.getName()
                                + " but couldn't record it: " + e.getMessage(), e);
                    }
                    plugin.getLogger().info("Moved " + after.getName() + " in '" + g.name() + "' to "
                            + result.spot().getBlockX() + ", " + result.spot().getBlockY() + ", "
                            + result.spot().getBlockZ() + ", " + Math.round(result.distance()) + " blocks from the centre, after "
                            + result.attempts() + " tries.");
                    send(after, g.messages().moved());
                } else {
                    plugin.getLogger().warning("Teleport for " + after.getName() + " was refused or failed; "
                            + "the move is tried again next visit.");
                }
                later(pauseAfterMove, () -> {
                    Player q = Bukkit.getPlayer(id);
                    if (q != null) {
                        starterSafely(q, g);
                    }
                });
            }));
        });
    }

    private void starterSafely(Player p, GroupSettings g) {
        if (p.isDead() || groups().forWorld(p.getWorld().getName()) != g) {
            arrive(p);
            return;
        }
        try {
            starter(p, g, true);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't write the record for " + p.getName()
                    + "; gave nothing. " + e.getMessage(), e);
        }
    }

    private void starter(Player p, GroupSettings g, boolean announce) throws IOException {
        UUID id = p.getUniqueId();
        Record rec = store().get(id, g.name());
        if (rec.starter().finished()) {
            waitingForRoom.remove(id);
            if (rec.starter() == Record.Starter.DONE) {
                stripTags(p, rec.grantId()); // Settled: the items can stack normally now.
            }
            return;
        }
        boolean pending = rec.starter() == Record.Starter.PENDING;
        // The inventory is the only evidence the items arrived. With Multiverse-Inventories, a crash can
        // roll back to the group's last saved inventory, whatever the record or a vanilla save says.
        boolean itemsHere = g.items().isEmpty() || (pending && hasTagged(p, rec.grantId()));
        int commandsLeft = Math.max(0, g.commands().size() - rec.commandsRun());
        if (itemsHere) {
            holding.computeIfAbsent(id, k -> new HashSet<>()).add(g.name());
        }
        if (pending && itemsHere && commandsLeft == 0) {
            return; // Given and still waiting to be saved; settled when they leave the group or log out.
        }
        if (pending && !itemsHere) {
            plugin.getLogger().info("Giving " + p.getName() + " the items of their '" + g.name()
                    + "' starter again: they weren't in the inventory, so they were lost before it was saved.");
        }
        int short_ = slotsShort(p.getInventory(), itemsHere ? List.of() : g.items(), commandsLeft);
        if (short_ > 0) {
            boolean first = waitingForRoom.put(id, g.name()) == null;
            if (first || announce) {
                send(p, g.messages().noRoom().replace("{slots}", Integer.toString(short_)));
            }
            return;
        }
        waitingForRoom.remove(id);
        boolean fresh = rec.starter() == Record.Starter.NONE;
        if (!itemsHere) {
            String grant = UUID.randomUUID().toString();
            // Pending first, then the items. The command count is kept: a command never runs twice.
            store().put(id, g.name(), rec.withStarter(Record.Starter.PENDING, grant));
            for (ItemStack it : g.items()) {
                ItemStack copy = it.clone();
                copy.editPersistentDataContainer(pdc -> pdc.set(grantKey, PersistentDataType.STRING, grant));
                for (ItemStack l : p.getInventory().addItem(copy).values()) {
                    // Shouldn't happen: room was checked this same tick. Never lose an item.
                    p.getWorld().dropItem(p.getLocation(), l);
                    plugin.getLogger().warning("Inventory filled unexpectedly; dropped " + l.getAmount() + " "
                            + l.getType() + " at " + p.getName() + "'s feet.");
                }
            }
            p.updateInventory();
            holding.computeIfAbsent(id, k -> new HashSet<>()).add(g.name());
        } else if (fresh) {
            store().put(id, g.name(), rec.withStarter(Record.Starter.PENDING, null));
        }
        busy.add(id);
        later(pauseAfterGive, () -> {
            busy.remove(id);
            Player q = Bukkit.getPlayer(id);
            if (q == null) {
                return; // Stays pending; the next visit looks for the items and runs what's left.
            }
            try {
                runCommands(q, g);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't record a command for " + q.getName()
                        + "; stopped before running it. " + e.getMessage(), e);
                return;
            }
            if (fresh) {
                plugin.getLogger().info("Gave " + q.getName() + " the starter for '" + g.name() + "'.");
                send(q, g.messages().starter());
            }
        });
    }

    /**
     * Marks a pending starter done once Multiverse-Inventories (or the server) has saved the
     * inventory it's in: when the player leaves the group or logs out. Called after those plugins'
     * own handlers, at MONITOR priority.
     */
    private void settle(Player p, GroupSettings g) {
        Record rec = store().get(p.getUniqueId(), g.name());
        // By the time a world change is seen, the inventory plugin has already swapped inventories,
        // so this goes by what was seen while they were here, not by what they're holding now.
        Set<String> held = holding.get(p.getUniqueId());
        if (rec.starter() != Record.Starter.PENDING || busy.contains(p.getUniqueId())
                || rec.commandsRun() < g.commands().size() || held == null || !held.contains(g.name())) {
            return; // Not given yet, still running, or not seen this session: the next visit sorts it out.
        }
        try {
            store().put(p.getUniqueId(), g.name(), rec.withStarter(Record.Starter.DONE, rec.grantId()));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Couldn't mark " + p.getName() + "'s starter done; it stays "
                    + "pending and is settled next time. " + e.getMessage(), e);
        }
    }

    private void runCommands(Player p, GroupSettings g) throws IOException {
        UUID id = p.getUniqueId();
        List<String> cmds = g.commands();
        for (int i = store().get(id, g.name()).commandsRun(); i < cmds.size(); i++) {
            store().put(id, g.name(), store().get(id, g.name()).withCommandsRun(i + 1));
            String cmd = cmds.get(i).replace("{player}", p.getName()).replace("{uuid}", id.toString());
            try {
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                plugin.getLogger().info("Starter command for " + p.getName() + " in '" + g.name() + "': /" + cmd
                        + (ok ? "" : " (the server said it isn't a known command)"));
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Starter command for " + p.getName() + " in '" + g.name()
                        + "' failed: /" + cmd + ". Carrying on with the rest.", e);
            }
        }
    }

    // ---- Helpers ----

    /**
     * True if the inventory the player arrived with (after the swap) shows they've played in this
     * group: any item, anything in the ender chest, or any XP. A new arrival's group inventory is
     * empty. Checked once, only when there's no record yet.
     */
    static boolean lived(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && !it.isEmpty()) {
                return true;
            }
        }
        for (ItemStack it : p.getEnderChest().getContents()) {
            if (it != null && !it.isEmpty()) {
                return true;
            }
        }
        return p.getLevel() > 0 || p.getExp() > 0 || p.getTotalExperience() > 0;
    }

    private boolean hasTagged(Player p, String grant) {
        if (grant == null) {
            return false;
        }
        for (ItemStack it : p.getInventory().getContents()) {
            if (tagged(it, grant)) {
                return true;
            }
        }
        return tagged(p.getItemOnCursor(), grant);
    }

    private boolean tagged(ItemStack it, String grant) {
        return it != null && !it.isEmpty()
                && grant.equals(it.getPersistentDataContainer().get(grantKey, PersistentDataType.STRING));
    }

    private void stripTags(Player p, String grant) {
        if (grant == null) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (ItemStack it : contents) {
            if (tagged(it, grant)) {
                it.editPersistentDataContainer(pdc -> pdc.remove(grantKey));
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
        }
    }

    /**
     * How many more free slots the starter needs; 0 if it fits. Changes nothing. Each command gets
     * one spare slot too, because a command that gives an item drops it if there's no room.
     */
    static int slotsShort(PlayerInventory inv, List<ItemStack> items, int commands) {
        if (items.isEmpty() && commands == 0) {
            return 0;
        }
        Inventory sim = Bukkit.createInventory(null, 36);
        ItemStack[] storage = inv.getStorageContents();
        ItemStack[] copy = new ItemStack[36];
        for (int i = 0; i < Math.min(36, storage.length); i++) {
            copy[i] = storage[i] == null ? null : storage[i].clone();
        }
        sim.setContents(copy);
        int needed = 0;
        for (ItemStack it : items) {
            if (!sim.addItem(it.clone()).isEmpty()) {
                needed++;
            }
        }
        int empty = 0;
        for (ItemStack it : sim.getContents()) {
            if (it == null || it.getType().isAir()) {
                empty++;
            }
        }
        return needed + Math.max(0, commands - empty);
    }

    private void later(int ticks, Runnable r) {
        if (ticks <= 0) {
            r.run();
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
        }
    }

    void send(Player p, String miniMessage) {
        if (miniMessage != null && !miniMessage.isBlank()) {
            p.sendMessage(MiniMessage.miniMessage().deserialize(miniMessage));
        }
    }
}
