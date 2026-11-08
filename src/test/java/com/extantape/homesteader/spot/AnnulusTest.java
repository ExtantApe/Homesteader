package com.extantape.homesteader.spot;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnulusTest {
    @Test
    void pointsStayInTheRingAndInsideTheBorder() {
        Random r = new Random(1);
        for (int i = 0; i < 5000; i++) {
            double[] p = Annulus.pick(r, 0, 0, 300, 1500, 0, 0, 1000, 16);
            assertNotNull(p);
            double d = Math.hypot(p[0], p[1]);
            assertTrue(d >= 299.999 && d <= 1500.001, "distance " + d);
            assertTrue(Math.abs(p[0]) <= 984 && Math.abs(p[1]) <= 984, "outside border");
        }
    }

    @Test
    void ringOutsideTheBorderGivesNothing() {
        assertNull(Annulus.pick(new Random(1), 0, 0, 600, 800, 0, 0, 250, 16));
    }

    @Test
    void aRingMuchBiggerThanTheBorderStillFindsPoints() {
        Random r = new Random(3);
        for (int i = 0; i < 1000; i++) {
            double[] p = Annulus.pick(r, 0, 0, 0, 2000, 0, 0, 80, 24);
            assertNotNull(p);
            assertTrue(Math.abs(p[0]) <= 56 && Math.abs(p[1]) <= 56);
        }
    }

    @Test
    void tinyBorderGivesNothing() {
        assertNull(Annulus.pick(new Random(1), 0, 0, 0, 10, 0, 0, 10, 16));
    }
}
