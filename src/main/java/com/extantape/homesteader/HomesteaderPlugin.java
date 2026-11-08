package com.extantape.homesteader;

import com.extantape.homesteader.claims.ClaimCheck;
import com.extantape.homesteader.claims.GriefPreventionClaims;
import com.extantape.homesteader.command.HomesteaderCommand;
import com.extantape.homesteader.config.GroupSettings;
import com.extantape.homesteader.config.Settings;
import com.extantape.homesteader.group.Groups;
import com.extantape.homesteader.record.RecordStore;
import com.extantape.homesteader.spot.SpotFinder;
import org.bukkit.Bukkit;
import org.bukkit.World;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class HomesteaderPlugin extends JavaPlugin {
    private Settings settings;
    private Groups groups;
    private RecordStore store;
    private SpotFinder finder;
    private ClaimCheck claims = ClaimCheck.NONE;
    private Steward steward;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new RecordStore(getDataFolder().toPath().resolve("records.yml"));
        finder = new SpotFinder(this);
        steward = new Steward(this);
        reload();
        getServer().getPluginManager().registerEvents(steward, this);
        Bukkit.getScheduler().runTaskTimer(this, steward::retryWaiting, 100, 100);

        HomesteaderCommand cmd = new HomesteaderCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register(cmd.node(), "Homesteader admin commands"));
        // Players already online after a reload or a late enable.
        Bukkit.getScheduler().runTask(this, () -> Bukkit.getOnlinePlayers().forEach(steward::arrive));
    }

    @Override
    public void onDisable() {
        if (finder != null) {
            finder.shutdown();
        }
    }

    /** Re-reads config.yml and records.yml. Returns a one-line summary. */
    public String reload() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger()::warning);
        groups = new Groups(settings.groups(), getLogger());
        boolean wasDamaged = store.problem() != null;
        String problem = store.load();
        if (problem != null) {
            getLogger().severe(problem);
        } else if (wasDamaged && steward != null) {
            Bukkit.getScheduler().runTask(this, steward::recordsRecovered);
        }
        claims = pickClaims();
        groups.report();
        boolean moveOn = false;
        for (GroupSettings g : settings.groups().values()) {
            if (!g.enabled() || !g.move().enabled()) {
                continue;
            }
            World w = Bukkit.getWorld(g.move().world());
            if (w != null && w.hasCeiling()) {
                getLogger().warning("Group '" + g.name() + "' moves players in a world with a ceiling. "
                        + "There's no open sky there, so no spot will pass.");
            }
            moveOn = true;
        }
        if (moveOn && claims == ClaimCheck.NONE) {
            getLogger().warning("No claims plugin is in use, so moves don't avoid claims.");
        }
        long enabled = settings.groups().values().stream().filter(GroupSettings::enabled).count();
        String summary = enabled + " group(s) on, claims: " + claims.name()
                + (Groups.mvInvPresent() ? ", Multiverse-Inventories found" : ", no Multiverse-Inventories")
                + (problem != null ? ". RECORDS DAMAGED: giving nothing." : ".");
        getLogger().info(summary);
        return summary;
    }

    private ClaimCheck pickClaims() {
        String want = settings.claims();
        if ("none".equalsIgnoreCase(want)) {
            return ClaimCheck.NONE;
        }
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp != null && gp.isEnabled()) {
            try {
                return GriefPreventionClaims.create(gp);
            } catch (ReflectiveOperationException | RuntimeException e) {
                getLogger().log(Level.SEVERE, "GriefPrevention is installed but its claims can't be read, so "
                        + "every spot counts as near a claim and nobody is moved.", e);
                return new ClaimCheck() {
                    @Override
                    public boolean nearClaim(org.bukkit.Location loc, int buffer) {
                        return true;
                    }

                    @Override
                    public String name() {
                        return "GriefPrevention (unreadable, moves off)";
                    }
                };
            }
        }
        if ("griefprevention".equalsIgnoreCase(want)) {
            getLogger().severe("claims is set to GriefPrevention, but it isn't running, so nobody is moved.");
            return new ClaimCheck() {
                @Override
                public boolean nearClaim(org.bukkit.Location loc, int buffer) {
                    return true;
                }

                @Override
                public String name() {
                    return "GriefPrevention (missing, moves off)";
                }
            };
        }
        return ClaimCheck.NONE;
    }

    public void rearrive(Player p) {
        steward.arrive(p);
    }

    public Settings settings() {
        return settings;
    }

    public Groups groups() {
        return groups;
    }

    public RecordStore store() {
        return store;
    }

    public SpotFinder finder() {
        return finder;
    }

    public ClaimCheck claims() {
        return claims;
    }
}
