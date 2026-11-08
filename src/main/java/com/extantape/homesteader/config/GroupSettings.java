package com.extantape.homesteader.config;

import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Settings for one world group.
 *
 * @param worlds       worlds in the group from config. Empty means: ask Multiverse-Inventories.
 * @param skipExisting treat players who already have an inventory in this group as done
 * @param items        the starter's items
 * @param commands     console commands run once with the starter, with {player} filled in
 * @param messages     this group's messages, with the server-wide ones filled in where it has none
 */
public record GroupSettings(String name, boolean enabled, List<String> worlds, boolean skipExisting,
                            List<ItemStack> items, List<String> commands, MoveSettings move,
                            Settings.Messages messages) {

    public boolean hasStarter() {
        return !items.isEmpty() || !commands.isEmpty();
    }
}
