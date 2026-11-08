package com.extantape.homesteader.group;

import com.extantape.homesteader.config.GroupSettings;
import org.bukkit.Bukkit;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Which Homesteader group a world is in. A group lists its worlds in config, or, with an empty
 * list, uses the Multiverse-Inventories group of the same name, read live so MV-Inventories edits
 * apply without a reload.
 */
public final class Groups {
    private final Map<String, GroupSettings> groups;
    private final Logger log;

    public Groups(Map<String, GroupSettings> groups, Logger log) {
        this.groups = groups;
        this.log = log;
    }

    public static boolean mvInvPresent() {
        return Bukkit.getPluginManager().isPluginEnabled("Multiverse-Inventories");
    }

    public Collection<GroupSettings> all() {
        return groups.values();
    }

    public GroupSettings byName(String name) {
        return groups.get(name);
    }

    /** The enabled group containing this world, or null. */
    public GroupSettings forWorld(String world) {
        for (GroupSettings g : groups.values()) {
            if (g.enabled() && worlds(g).contains(world)) {
                return g;
            }
        }
        return null;
    }

    public Set<String> worlds(GroupSettings g) {
        if (!g.worlds().isEmpty()) {
            return Set.copyOf(g.worlds());
        }
        if (!mvInvPresent()) {
            return Set.of();
        }
        try {
            Set<String> w = MvInvBridge.worldsOf(g.name());
            return w == null ? Set.of() : w;
        } catch (RuntimeException | LinkageError e) {
            log.warning("Couldn't read Multiverse-Inventories group '" + g.name() + "': " + e);
            return Set.of();
        }
    }

    /** Problems worth logging at startup: unknown groups, empty groups, overlaps. */
    public void report() {
        Map<String, String> seen = new java.util.HashMap<>();
        for (GroupSettings g : groups.values()) {
            if (!g.enabled()) {
                continue;
            }
            Set<String> w = worlds(g);
            if (w.isEmpty()) {
                log.warning("Group '" + g.name() + "' has no worlds. List them under 'worlds', or name a "
                        + "Multiverse-Inventories group. It does nothing until then.");
            }
            for (String world : w) {
                String other = seen.putIfAbsent(world.toLowerCase(Locale.ROOT), g.name());
                if (other != null) {
                    log.warning("World '" + world + "' is in both '" + other + "' and '" + g.name()
                            + "'. Only '" + other + "' applies there.");
                }
            }
            if (g.move().enabled() && !w.contains(g.move().world())) {
                log.warning("Group '" + g.name() + "' has move.world '" + g.move().world()
                        + "', which isn't in the group, so nobody is moved.");
            }
        }
    }
}
