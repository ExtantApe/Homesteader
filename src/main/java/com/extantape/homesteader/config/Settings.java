package com.extantape.homesteader.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Everything read from config.yml. A group with an error is turned off and the error logged. */
public record Settings(int settleTicks, String claims, Map<String, GroupSettings> groups, Messages messages) {

    public record Messages(String starter, String moved, String noSpot, String noRoom, String recordProblem) {
    }

    public static Settings load(FileConfiguration c, Consumer<String> warn) {
        int settle = Math.max(1, c.getInt("settle-ticks", 10));
        String claims = c.getString("claims", "auto");
        Messages msg = new Messages(
                c.getString("messages.starter", ""),
                c.getString("messages.moved", ""),
                c.getString("messages.no-spot", ""),
                c.getString("messages.no-room", ""),
                c.getString("messages.record-problem", ""));

        Map<String, GroupSettings> groups = new LinkedHashMap<>();
        ConfigurationSection gs = c.getConfigurationSection("groups");
        if (gs != null) {
            for (String name : gs.getKeys(false)) {
                ConfigurationSection g = gs.getConfigurationSection(name);
                if (g == null) {
                    warn.accept("groups." + name + " isn't a section; ignored");
                    continue;
                }
                try {
                    groups.put(name, group(name, g, msg));
                } catch (IllegalArgumentException e) {
                    warn.accept("Group '" + name + "' is off because of a config error: " + e.getMessage());
                }
            }
        }
        return new Settings(settle, claims, groups, msg);
    }

    private static GroupSettings group(String name, ConfigurationSection g, Messages fallback) {
        List<ItemStack> items = new ArrayList<>();
        for (String line : g.getStringList("starter.items")) {
            ItemSpec.Parsed p = ItemSpec.parse(line);
            Material m = Material.matchMaterial(p.material());
            if (m == null || !m.isItem() || m.isAir()) {
                throw new IllegalArgumentException("'" + p.material() + "' isn't an item");
            }
            int left = p.amount();
            while (left > 0) {
                int n = Math.min(left, m.getMaxStackSize());
                items.add(new ItemStack(m, n));
                left -= n;
            }
        }
        List<String> commands = new ArrayList<>();
        for (String cmd : g.getStringList("starter.commands")) {
            String t = cmd.trim();
            if (t.startsWith("/")) {
                t = t.substring(1);
            }
            if (!t.isEmpty()) {
                commands.add(t);
            }
        }

        ConfigurationSection mv = g.getConfigurationSection("move");
        MoveSettings move;
        if (mv == null) {
            move = new MoveSettings(false, "", null, null, 0, 0, 0, 0, 0);
        } else {
            int min = mv.getInt("min-distance", 300);
            int max = mv.getInt("max-distance", 1500);
            if (min < 0 || max < min) {
                throw new IllegalArgumentException("move: min-distance must be 0 or more, and no more than max-distance");
            }
            Double cx = null;
            Double cz = null;
            String center = mv.getString("center", "spawn");
            if (center != null && !center.equalsIgnoreCase("spawn")) {
                String[] p = center.split(",");
                if (p.length != 2) {
                    throw new IllegalArgumentException("move.center must be 'spawn' or 'x,z'");
                }
                try {
                    cx = Double.parseDouble(p[0].trim());
                    cz = Double.parseDouble(p[1].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("move.center must be 'spawn' or 'x,z'");
                }
            }
            move = new MoveSettings(mv.getBoolean("enabled", false), mv.getString("world", ""), cx, cz, min, max,
                    Math.max(0, mv.getInt("claim-buffer", 48)), Math.max(0, mv.getInt("border-margin", 16)),
                    Math.max(1, Math.min(64, mv.getInt("attempts", 16))));
        }
        Messages own = new Messages(
                g.getString("messages.starter", fallback.starter()),
                g.getString("messages.moved", fallback.moved()),
                g.getString("messages.no-spot", fallback.noSpot()),
                g.getString("messages.no-room", fallback.noRoom()),
                fallback.recordProblem());
        return new GroupSettings(name, g.getBoolean("enabled", false), g.getStringList("worlds"),
                g.getBoolean("skip-existing-players", true), List.copyOf(items), List.copyOf(commands), move, own);
    }
}
