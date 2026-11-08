package com.extantape.homesteader.group;

import org.mvplugins.multiverse.inventories.MultiverseInventoriesApi;
import org.mvplugins.multiverse.inventories.profile.group.WorldGroup;

import java.util.Set;

/**
 * The only class that touches Multiverse-Inventories. It's loaded only when that plugin is enabled,
 * so Homesteader still runs without it.
 */
final class MvInvBridge {
    private MvInvBridge() {
    }

    /** The worlds in an MV-Inventories group, or null if there's no such group. */
    static Set<String> worldsOf(String group) {
        WorldGroup g = MultiverseInventoriesApi.get().getWorldGroupManager().getGroup(group);
        return g == null ? null : g.getApplicableWorlds();
    }
}
