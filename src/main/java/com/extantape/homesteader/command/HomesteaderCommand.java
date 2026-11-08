package com.extantape.homesteader.command;

import com.extantape.homesteader.HomesteaderPlugin;
import com.extantape.homesteader.config.GroupSettings;
import com.extantape.homesteader.record.Record;
import com.extantape.homesteader.spot.SpotFinder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * /homesteader, registered with Brigadier through Paper's lifecycle API: status, reset, reset-all,
 * done, findspot, reload. All admin-only.
 */
public final class HomesteaderCommand {
    private final HomesteaderPlugin plugin;

    public HomesteaderCommand(HomesteaderPlugin plugin) {
        this.plugin = plugin;
    }

    /** The command tree. Each branch hands its arguments to the same handlers as before. */
    public LiteralCommandNode<CommandSourceStack> node() {
        SuggestionProvider<CommandSourceStack> groups = (ctx, b) -> {
            plugin.groups().all().forEach(g -> b.suggest(g.name()));
            return b.buildFuture();
        };
        SuggestionProvider<CommandSourceStack> players = (ctx, b) -> {
            Bukkit.getOnlinePlayers().forEach(p -> b.suggest(p.getName()));
            return b.buildFuture();
        };
        return Commands.literal("homesteader")
                .requires(src -> src.getSender().hasPermission("homesteader.admin"))
                .executes(ctx -> run(ctx, a -> help(ctx.getSource().getSender())))
                .then(Commands.literal("reload")
                        .executes(ctx -> run(ctx, a -> ctx.getSource().getSender().sendMessage("Homesteader: " + plugin.reload()))))
                .then(Commands.literal("status")
                        .then(player(players).executes(ctx -> run(ctx, a -> status(sender(ctx), args(ctx, "status", "player"))))))
                .then(Commands.literal("reset")
                        .then(player(players).then(group(groups)
                                .executes(ctx -> run(ctx, a -> reset(sender(ctx), args(ctx, "reset", "player", "group")))))))
                .then(Commands.literal("reset-all")
                        .then(group(groups).executes(ctx -> run(ctx, a -> reset(sender(ctx),
                                new String[] {"reset", "*", StringArgumentType.getString(ctx, "group")})))))
                .then(Commands.literal("done")
                        .then(player(players).then(group(groups)
                                .executes(ctx -> run(ctx, a -> done(sender(ctx), args(ctx, "done", "player", "group")))))))
                .then(Commands.literal("findspot")
                        .then(group(groups)
                                .executes(ctx -> run(ctx, a -> findspot(sender(ctx), args(ctx, "findspot", "group"))))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 1000))
                                        .executes(ctx -> run(ctx, a -> findspot(sender(ctx), new String[] {"findspot",
                                                StringArgumentType.getString(ctx, "group"),
                                                Integer.toString(IntegerArgumentType.getInteger(ctx, "count"))}))))))
                .build();
    }

    private static ArgumentBuilder<CommandSourceStack, ?> player(SuggestionProvider<CommandSourceStack> s) {
        return Commands.argument("player", StringArgumentType.word()).suggests(s);
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> group(
            SuggestionProvider<CommandSourceStack> s) {
        return Commands.argument("group", StringArgumentType.word()).suggests(s);
    }

    private static CommandSender sender(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getSender();
    }

    private static String[] args(CommandContext<CommandSourceStack> ctx, String sub, String... names) {
        String[] out = new String[names.length + 1];
        out[0] = sub;
        for (int i = 0; i < names.length; i++) {
            out[i + 1] = StringArgumentType.getString(ctx, names[i]);
        }
        return out;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, java.util.function.Consumer<Void> body) {
        body.accept(null);
        return Command.SINGLE_SUCCESS;
    }

    private void help(CommandSender s) {
        s.sendMessage("/homesteader status <player>  - what they've had, per group");
        s.sendMessage("/homesteader reset <player> <group>  - give the starter and move again on the next visit");
        s.sendMessage("/homesteader reset-all <group>  - the same for everyone with a record in the group");
        s.sendMessage("/homesteader done <player> <group>  - mark as done without giving anything");
        s.sendMessage("/homesteader findspot <group> [count]  - try the move search without moving anyone");
        s.sendMessage("/homesteader reload  - re-read config.yml and records.yml");
    }

    private OfflinePlayer player(CommandSender s, String arg) {
        Player online = Bukkit.getPlayerExact(arg);
        if (online != null) {
            return online;
        }
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(arg));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID.
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(arg);
        if (cached == null) {
            s.sendMessage("No player called '" + arg + "' has been on this server. A UUID works too.");
        }
        return cached;
    }

    private GroupSettings group(CommandSender s, String arg) {
        GroupSettings g = plugin.groups().byName(arg);
        if (g == null) {
            s.sendMessage("No group called '" + arg + "' in config.yml.");
        }
        return g;
    }

    private boolean refuseIfDamaged(CommandSender s) {
        if (plugin.store().problem() != null) {
            s.sendMessage(plugin.store().problem());
            return true;
        }
        return false;
    }

    private void status(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage("/homesteader status <player>");
            return;
        }
        OfflinePlayer p = player(s, a[1]);
        if (p == null) {
            return;
        }
        Map<String, Record> all = plugin.store().all(p.getUniqueId());
        if (all.isEmpty()) {
            s.sendMessage(a[1] + ": no records.");
            return;
        }
        all.forEach((group, r) -> s.sendMessage(a[1] + " in " + group + ": starter " + word(r.starter())
                + ", commands run " + r.commandsRun() + ", move " + word(r.move())));
    }

    private static String word(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private void reset(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage("/homesteader reset <player> <group>");
            return;
        }
        if (refuseIfDamaged(s)) {
            return;
        }
        GroupSettings g = group(s, a[2]);
        if (g == null) {
            return;
        }
        List<UUID> targets = new ArrayList<>();
        if (a[1].equals("*")) {
            for (UUID id : plugin.store().players()) {
                if (plugin.store().has(id, g.name())) {
                    targets.add(id);
                }
            }
        } else {
            OfflinePlayer p = player(s, a[1]);
            if (p == null) {
                return;
            }
            targets.add(p.getUniqueId());
        }
        try {
            for (UUID id : targets) {
                // An empty record, not none, so the "already has an inventory" check is skipped next time.
                plugin.store().put(id, g.name(), Record.EMPTY.withStarter(Record.Starter.NONE, null));
            }
        } catch (IOException e) {
            s.sendMessage("Couldn't write the records: " + e.getMessage());
            return;
        }
        s.sendMessage("Reset " + targets.size() + " player(s) in '" + g.name()
                + "'. They get the starter (and the move, if on) on their next visit.");
        for (UUID id : targets) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.rearrive(p);
            }
        }
    }

    private void done(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage("/homesteader done <player> <group>");
            return;
        }
        if (refuseIfDamaged(s)) {
            return;
        }
        GroupSettings g = group(s, a[2]);
        OfflinePlayer p = g == null ? null : player(s, a[1]);
        if (p == null) {
            return;
        }
        Record old = plugin.store().get(p.getUniqueId(), g.name());
        if (old.starter() == Record.Starter.DONE) {
            s.sendMessage(a[1] + " already had their starter in '" + g.name() + "'. Nothing changed.");
            return;
        }
        try {
            plugin.store().put(p.getUniqueId(), g.name(), new Record(Record.Starter.MARKED, null,
                    g.commands().size(), old.move().finished() ? old.move() : Record.Move.SKIPPED,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            s.sendMessage("Couldn't write the record: " + e.getMessage());
            return;
        }
        s.sendMessage("Marked " + a[1] + " as done in '" + g.name() + "'. They get no starter or move there.");
    }

    private void findspot(CommandSender s, String[] a) {
        if (a.length < 2) {
            s.sendMessage("/homesteader findspot <group> [count]");
            return;
        }
        GroupSettings g = group(s, a[1]);
        if (g == null) {
            return;
        }
        World w = Bukkit.getWorld(g.move().world());
        if (w == null) {
            s.sendMessage("move.world '" + g.move().world() + "' isn't loaded.");
            return;
        }
        int count = 1;
        if (a.length >= 3) {
            try {
                count = Math.max(1, Math.min(1000, Integer.parseInt(a[2])));
            } catch (NumberFormatException e) {
                s.sendMessage("count must be a number");
                return;
            }
        }
        int total = count;
        long loaded0 = plugin.finder().chunksLoaded.get();
        long gen0 = plugin.finder().chunksGenerated.get();
        long t0 = System.nanoTime();
        AtomicInteger found = new AtomicInteger();
        AtomicInteger finished = new AtomicInteger();
        AtomicInteger tries = new AtomicInteger();
        s.sendMessage("Searching " + total + " time(s) in " + w.getName() + ". Nobody is moved and no block is changed.");
        for (int i = 0; i < total; i++) {
            plugin.finder().find(w, g.move(), plugin.claims()).whenComplete((r, err) -> {
                tries.addAndGet(r == null ? 0 : r.attempts());
                if (r != null && r.spot() != null) {
                    found.incrementAndGet();
                    Location l = r.spot();
                    plugin.getLogger().info("findspot " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()
                            + " distance=" + Math.round(r.distance()) + " tries=" + r.attempts());
                } else {
                    plugin.getLogger().info("findspot none: " + (r == null ? String.valueOf(err) : r.reason()));
                }
                if (finished.incrementAndGet() == total) {
                    long ms = (System.nanoTime() - t0) / 1_000_000;
                    s.sendMessage("findspot: " + found.get() + "/" + total + " found, " + tries.get() + " tries, "
                            + (plugin.finder().chunksLoaded.get() - loaded0) + " chunks loaded, "
                            + (plugin.finder().chunksGenerated.get() - gen0) + " generated, " + ms + " ms. "
                            + "Spots are in the server log.");
                }
            });
        }
    }
}
