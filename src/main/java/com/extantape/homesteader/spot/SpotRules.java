package com.extantape.homesteader.spot;

import java.util.OptionalInt;

/**
 * Decides whether a column is a safe landing. Pure logic, so it's tested without a server.
 *
 * <p>Safe means: the top block is solid ground (not leaves, liquid or a hazard), there are three
 * open blocks above it, nothing within four blocks sideways is liquid or a hazard, from two below the
 * ground to six above it,
 * and the ground around it doesn't drop or rise by more than {@link #MAX_STEP} blocks, so it isn't
 * a cliff edge or a pillar. Leaves beside the landing are looked through, so a drop under a canopy
 * still counts. The top block is found from the sky down, so a landing is never in a cave.
 */
public final class SpotRules {
    /** How far around the landing to check the ground's height, in blocks. */
    public static final int RADIUS = 2;
    /**
     * How far around the landing no liquid or hazard may be. Wider than {@link #RADIUS}, and checked
     * up to {@link #LIQUID_ABOVE} blocks above the ground, because a new chunk's water only starts to
     * flow a few ticks after it's generated, so a spring nearby is still dry when the spot is checked.
     */
    public static final int LIQUID_RADIUS = 4;
    public static final int LIQUID_ABOVE = 6;
    /** The biggest height difference to a neighbouring column that still counts as flat enough. */
    public static final int MAX_STEP = 2;
    /** Open blocks needed above the ground: feet, head and one spare. */
    public static final int HEADROOM = 3;

    private SpotRules() {
    }

    /** The topmost non-open block in a column, or empty if the column is all open. */
    public static OptionalInt top(BlockView view, int x, int z) {
        for (int y = view.maxY() - 1; y >= view.minY(); y--) {
            if (view.kind(x, y, z) != Kind.OPEN) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Returns the ground Y if the column at x, z is a safe landing, or empty if not. The player
     * stands at ground + 1. The view must cover {@link #RADIUS} blocks around x, z.
     */
    public static OptionalInt evaluate(BlockView view, int x, int z) {
        OptionalInt top = top(view, x, z);
        if (top.isEmpty()) {
            return OptionalInt.empty();
        }
        int g = top.getAsInt();
        if (g <= view.minY() || g + HEADROOM >= view.maxY()) {
            return OptionalInt.empty();
        }
        if (view.kind(x, g, z) != Kind.SOLID) {
            return OptionalInt.empty();
        }
        for (int dy = 1; dy <= HEADROOM; dy++) {
            if (view.kind(x, g + dy, z) != Kind.OPEN) {
                return OptionalInt.empty();
            }
        }
        for (int dx = -LIQUID_RADIUS; dx <= LIQUID_RADIUS; dx++) {
            for (int dz = -LIQUID_RADIUS; dz <= LIQUID_RADIUS; dz++) {
                for (int y = g - 2; y <= g + LIQUID_ABOVE; y++) {
                    Kind k = view.kind(x + dx, y, z + dz);
                    if (k == Kind.LIQUID || k == Kind.HAZARD) {
                        return OptionalInt.empty();
                    }
                }
            }
        }
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                OptionalInt nt = top(view, x + dx, z + dz);
                if (nt.isEmpty()) {
                    return OptionalInt.empty();
                }
                int ny = nt.getAsInt();
                Kind nk = view.kind(x + dx, ny, z + dz);
                // A canopy beside the landing is fine, but what's under it counts: a drop can hide there.
                while (nk == Kind.LEAVES || (nk == Kind.OPEN && ny > view.minY())) {
                    ny--;
                    nk = view.kind(x + dx, ny, z + dz);
                }
                if (nk == Kind.LIQUID || nk == Kind.HAZARD) {
                    return OptionalInt.empty();
                }
                if (Math.abs(ny - g) > MAX_STEP) {
                    return OptionalInt.empty();
                }
            }
        }
        return OptionalInt.of(g);
    }
}
