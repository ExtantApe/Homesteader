package com.extantape.homesteader.spot;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotRulesTest {

    @Test
    void flatGroundIsSafe() {
        assertEquals(OptionalInt.of(64), SpotRules.evaluate(new Grid(64), 0, 0));
    }

    @Test
    void waterSurfaceIsNotGround() {
        Grid g = new Grid(60);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 61; y <= 64; y++) {
                    g.set(x, y, z, Kind.LIQUID);
                }
            }
        }
        assertTrue(SpotRules.evaluate(g, 0, 0).isEmpty());
    }

    @Test
    void lavaUnderfootOrBesideIsRejected() {
        assertTrue(SpotRules.evaluate(new Grid(64).set(0, 64, 0, Kind.HAZARD), 0, 0).isEmpty());
        assertTrue(SpotRules.evaluate(new Grid(64).set(2, 64, 1, Kind.HAZARD), 0, 0).isEmpty());
        // Lava two blocks below the surface, beside the landing, is still too close.
        assertTrue(SpotRules.evaluate(new Grid(64).set(1, 62, 0, Kind.HAZARD), 0, 0).isEmpty());
        // Five blocks away it doesn't matter.
        assertEquals(OptionalInt.of(64), SpotRules.evaluate(new Grid(64).set(5, 64, 0, Kind.HAZARD), 0, 0));
    }

    @Test
    void aSpringAboveAndNearbyIsRejected() {
        // Water four blocks over and five up, as in a fresh chunk whose water hasn't started to flow.
        assertTrue(SpotRules.evaluate(new Grid(64).set(4, 69, 0, Kind.LIQUID), 0, 0).isEmpty());
    }

    @Test
    void lavaDeepBelowIsFine() {
        assertEquals(OptionalInt.of(64), SpotRules.evaluate(new Grid(64).set(0, 40, 0, Kind.HAZARD), 0, 0));
    }

    @Test
    void cliffEdgeIsRejected() {
        Grid g = new Grid(64);
        for (int z = -3; z <= 3; z++) {
            g.height(1, z, 50).height(2, z, 50);
        }
        assertTrue(SpotRules.evaluate(g, 0, 0).isEmpty());
    }

    @Test
    void gentleSlopeIsFine() {
        Grid g = new Grid(64);
        for (int z = -3; z <= 3; z++) {
            g.height(1, z, 65).height(2, z, 66);
        }
        assertEquals(OptionalInt.of(64), SpotRules.evaluate(g, 0, 0));
    }

    @Test
    void pillarIsRejected() {
        assertTrue(SpotRules.evaluate(new Grid(64).height(0, 0, 70), 0, 0).isEmpty());
    }

    @Test
    void treeTopIsNotGround() {
        Grid g = new Grid(64);
        for (int y = 65; y <= 70; y++) {
            g.set(0, y, 0, Kind.LEAVES);
        }
        assertTrue(SpotRules.evaluate(g, 0, 0).isEmpty());
    }

    @Test
    void treeBesideTheLandingIsFine() {
        Grid g = new Grid(64);
        for (int y = 67; y <= 71; y++) {
            g.set(2, y, 2, Kind.LEAVES);
        }
        assertEquals(OptionalInt.of(64), SpotRules.evaluate(g, 0, 0));
    }

    @Test
    void aDropHiddenUnderLeavesIsRejected() {
        // A plateau edge with a canopy below it, level with the landing.
        Grid g = new Grid(64);
        for (int z = -3; z <= 3; z++) {
            g.height(1, z, 50).height(2, z, 50);
            g.set(1, 64, z, Kind.LEAVES).set(2, 64, z, Kind.LEAVES);
        }
        assertTrue(SpotRules.evaluate(g, 0, 0).isEmpty());
    }

    @Test
    void lowCeilingIsRejected() {
        assertTrue(SpotRules.evaluate(new Grid(64).set(0, 67, 0, Kind.SOLID), 0, 0).isEmpty());
    }

    @Test
    void aCaveIsNeverChosen() {
        // A mountain with a cave inside: the landing is on top, never in the hollow.
        Grid g = new Grid(100);
        for (int y = 70; y <= 73; y++) {
            g.set(0, y, 0, Kind.OPEN);
        }
        assertEquals(OptionalInt.of(100), SpotRules.evaluate(g, 0, 0));
    }

    @Test
    void aWorldWithACeilingHasNoSpot() {
        // Bedrock roof, like the Nether: the top block is a hazard.
        Grid g = new Grid(64);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                g.set(x, 127, z, Kind.HAZARD);
            }
        }
        assertTrue(SpotRules.evaluate(g, 0, 0).isEmpty());
    }

    /** Hundreds of random terrains: anything accepted must meet every rule, checked independently. */
    @Test
    void randomTerrainOnlyEverYieldsSafeSpots() {
        Random r = new Random(42);
        int accepted = 0;
        for (int trial = 0; trial < 2000; trial++) {
            int base = 60 + r.nextInt(10);
            Grid g = new Grid(base);
            for (int x = -5; x <= 5; x++) {
                for (int z = -5; z <= 5; z++) {
                    if (r.nextInt(4) == 0) {
                        g.height(x, z, base - 4 + r.nextInt(9));
                    }
                    if (r.nextInt(120) == 0) {
                        g.set(x, 60 + r.nextInt(10), z, Kind.values()[2 + r.nextInt(3)]);
                    }
                }
            }
            OptionalInt res = SpotRules.evaluate(g, 0, 0);
            if (res.isEmpty()) {
                continue;
            }
            accepted++;
            int y = res.getAsInt();
            assertEquals(Kind.SOLID, g.kind(0, y, 0));
            for (int dy = 1; dy <= 3; dy++) {
                assertEquals(Kind.OPEN, g.kind(0, y + dy, 0));
            }
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    for (int yy = y - 2; yy <= y + 6; yy++) {
                        Kind k = g.kind(dx, yy, dz);
                        assertTrue(k != Kind.LIQUID && k != Kind.HAZARD, "liquid or hazard near landing");
                    }
                }
            }
        }
        assertTrue(accepted > 50, "the random terrain should allow some landings, got " + accepted);
    }
}
