package com.extantape.homesteader.claims;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** If GriefPrevention's claims can't be read, every spot counts as near a claim, so nobody is moved. */
class GriefPreventionClaimsTest {
    static final Location SPOT = new Location(null, 100, 70, 100);

    public static final class Store {
        public final Collection<?> claims;
        public Store(Collection<?> claims) { this.claims = claims; }
        public Collection<?> getClaims() { return claims; }
    }

    public static final class Broken {
        public Collection<?> getClaims() { throw new IllegalStateException("not loaded"); }
    }

    /** A claim from a GriefPrevention version that renamed its corner methods. */
    public static final class OddClaim {
        public Location getLowerCorner() { return SPOT; }
    }

    @Test void controlNoClaimsMeansNotNearOne() throws Exception {
        assertFalse(GriefPreventionClaims.fromDataStore(new Store(List.of())).nearClaim(SPOT, 12));
    }

    @Test void aDataStoreWithoutGetClaimsFailsAtStartNotLater() {
        assertThrows(NoSuchMethodException.class, () -> GriefPreventionClaims.fromDataStore(new Object()));
    }

    @Test void claimsThatCantBeListedFailAtStart() {
        assertThrows(ReflectiveOperationException.class, () -> GriefPreventionClaims.fromDataStore(new Broken()));
    }

    @Test void aClaimThatCantBeReadCountsAsNear() throws Exception {
        assertTrue(GriefPreventionClaims.fromDataStore(new Store(List.of(new OddClaim()))).nearClaim(SPOT, 12));
    }

    @Test void somethingThatIsntAClaimCountsAsNear() throws Exception {
        assertTrue(GriefPreventionClaims.fromDataStore(new Store(List.of("not a claim"))).nearClaim(SPOT, 12));
    }
}
