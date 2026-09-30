package io.github.ndellagrotte.cleanfpv.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FisheyeMathTest {

    private static final double FOV = Math.toRadians(100.0);
    private static final double ASPECT = 16.0 / 9.0;

    @Test
    void centreIsFixed() {
        double[] out = new double[2];
        assertTrue(FisheyeMath.sourceOf(0.5, 0.5, FOV, ASPECT, out));
        assertEquals(0.5, out[0], 1e-12);
        assertEquals(0.5, out[1], 1e-12);
    }

    @Test
    void pivotRadiusIsFixed() {
        double[] out = new double[2];
        // Straight up from the centre by PIVOT picture heights (aspect does not scale y).
        FisheyeMath.sourceOf(0.5, 0.5 + FisheyeMath.PIVOT, FOV, ASPECT, out);
        assertEquals(0.5 + FisheyeMath.PIVOT, out[1], 1e-9);
        assertEquals(0.5, out[0], 1e-12);
    }

    @Test
    void centreIsMagnifiedAndEdgesCompressed() {
        double[] out = new double[2];
        double d = 0.01;
        FisheyeMath.sourceOf(0.5, 0.5 + d, FOV, ASPECT, out);
        double half = FOV / 2;
        // Near the centre the source offset shrinks by h / tan(h) (< 1): magnification.
        assertEquals(d * half / Math.tan(half), out[1] - 0.5, 1e-5);
        // Between the centre and the pivot every source offset is smaller than the screen offset.
        FisheyeMath.sourceOf(0.5, 0.5 + 0.4, FOV, ASPECT, out);
        assertTrue(out[1] - 0.5 < 0.4);
    }

    @Test
    void farCornersFallOutsideTheFrame() {
        double[] out = new double[2];
        assertFalse(FisheyeMath.sourceOf(1.0, 1.0, Math.toRadians(150.0), ASPECT, out));
    }

    @Test
    void inverseRoundTrips() {
        double[] src = new double[2];
        double[] back = new double[2];
        for (double u = 0.05; u < 1.0; u += 0.15) {
            for (double v = 0.05; v < 1.0; v += 0.15) {
                FisheyeMath.sourceOf(u, v, FOV, ASPECT, src);
                FisheyeMath.screenOf(src[0], src[1], FOV, ASPECT, back);
                assertEquals(u, back[0], 1e-6, "u at " + u + "," + v);
                assertEquals(v, back[1], 1e-6, "v at " + u + "," + v);
            }
        }
    }

    @Test
    void nonFiniteFovIsSafe() {
        double[] out = new double[2];
        FisheyeMath.sourceOf(0.3, 0.7, Double.NaN, ASPECT, out);
        assertTrue(Double.isFinite(out[0]) && Double.isFinite(out[1]));
    }
}
