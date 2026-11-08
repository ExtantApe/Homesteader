package com.extantape.homesteader.spot;

import java.util.random.RandomGenerator;

/** Picks points between two distances from a centre, evenly by area, and keeps them inside a square border. */
public final class Annulus {
    private Annulus() {
    }

    /**
     * Returns {x, z}, or null if no point in the ring fits inside the border with the margin.
     *
     * @param borderCenterX border centre
     * @param borderHalf    half the border's side length
     * @param margin        blocks to keep from the border edge
     */
    public static double[] pick(RandomGenerator rnd, double cx, double cz, double min, double max,
                                double borderCenterX, double borderCenterZ, double borderHalf, double margin) {
        double usable = borderHalf - margin;
        if (usable <= 0 || max < min) {
            return null;
        }
        // A ring far bigger than the border would almost never hit it. Cap it at the farthest corner.
        double fx = Math.abs(cx - borderCenterX) + usable;
        double fz = Math.abs(cz - borderCenterZ) + usable;
        max = Math.min(max, Math.hypot(fx, fz));
        if (max < min) {
            return null;
        }
        for (int i = 0; i < 64; i++) {
            double angle = rnd.nextDouble() * Math.PI * 2;
            // Square root keeps points evenly spread by area, not bunched near the inner edge.
            double r = Math.sqrt(min * min + rnd.nextDouble() * (max * max - min * min));
            double x = cx + Math.cos(angle) * r;
            double z = cz + Math.sin(angle) * r;
            if (Math.abs(x - borderCenterX) <= usable && Math.abs(z - borderCenterZ) <= usable) {
                return new double[] {x, z};
            }
        }
        return null;
    }
}
