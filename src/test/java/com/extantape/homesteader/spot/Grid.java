package com.extantape.homesteader.spot;

import java.util.HashMap;
import java.util.Map;

/** A made-up world for tests: flat SOLID ground at a given height, with overrides. */
final class Grid implements BlockView {
    private final int ground;
    private final Map<Long, Kind> blocks = new HashMap<>();
    private final Map<Long, Integer> heights = new HashMap<>();

    Grid(int ground) {
        this.ground = ground;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 40) | ((long) (y & 0xFFFF) << 20) | (z & 0xFFFF);
    }

    private static long col(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /** Sets the ground height of one column. */
    Grid height(int x, int z, int h) {
        heights.put(col(x, z), h);
        return this;
    }

    Grid set(int x, int y, int z, Kind k) {
        blocks.put(key(x, y, z), k);
        return this;
    }

    @Override
    public Kind kind(int x, int y, int z) {
        Kind k = blocks.get(key(x, y, z));
        if (k != null) {
            return k;
        }
        int h = heights.getOrDefault(col(x, z), ground);
        return y <= h ? Kind.SOLID : Kind.OPEN;
    }

    @Override
    public int minY() {
        return -64;
    }

    @Override
    public int maxY() {
        return 320;
    }
}
