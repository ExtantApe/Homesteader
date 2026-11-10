package com.extantape.homesteader.claims;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

/**
 * Reads GriefPrevention's claims by reflection, so Homesteader doesn't need its jar to build and
 * works across GriefPrevention versions that keep these public names: {@code dataStore},
 * {@code getClaims()}, {@code getLesserBoundaryCorner()} and {@code getGreaterBoundaryCorner()}.
 * If any is missing, {@link #create} fails and the move is turned off rather than guessing.
 */
public final class GriefPreventionClaims implements ClaimCheck {
    private final Object dataStore;
    private final Method getClaims;

    private GriefPreventionClaims(Object dataStore, Method getClaims) {
        this.dataStore = dataStore;
        this.getClaims = getClaims;
    }

    public static GriefPreventionClaims create(Plugin gp) throws ReflectiveOperationException {
        Field f = gp.getClass().getField("dataStore");
        return fromDataStore(f.get(gp));
    }

    /** The part of {@link #create} after finding the data store, so it can be tested without GriefPrevention. */
    static GriefPreventionClaims fromDataStore(Object ds) throws ReflectiveOperationException {
        Method m = ds.getClass().getMethod("getClaims");
        GriefPreventionClaims c = new GriefPreventionClaims(ds, m);
        c.claims(); // Fail now, not on the first player.
        return c;
    }

    private Collection<?> claims() throws ReflectiveOperationException {
        return (Collection<?>) getClaims.invoke(dataStore);
    }

    @Override
    public boolean nearClaim(Location loc, int buffer) {
        try {
            for (Object claim : claims()) {
                Location lo = (Location) claim.getClass().getMethod("getLesserBoundaryCorner").invoke(claim);
                Location hi = (Location) claim.getClass().getMethod("getGreaterBoundaryCorner").invoke(claim);
                if (lo.getWorld() == null || !lo.getWorld().equals(loc.getWorld())) {
                    continue;
                }
                if (loc.getBlockX() >= lo.getBlockX() - buffer && loc.getBlockX() <= hi.getBlockX() + buffer
                        && loc.getBlockZ() >= lo.getBlockZ() - buffer && loc.getBlockZ() <= hi.getBlockZ() + buffer) {
                    return true;
                }
            }
            return false;
        } catch (ReflectiveOperationException | ClassCastException e) {
            // If the claims can't be read, the safe answer is "near a claim".
            return true;
        }
    }

    @Override
    public String name() {
        return "GriefPrevention";
    }
}
