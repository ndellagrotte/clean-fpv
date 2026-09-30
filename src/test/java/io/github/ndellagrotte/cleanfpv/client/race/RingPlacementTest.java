package io.github.ndellagrotte.cleanfpv.client.race;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RingPlacementTest {

    private static final double EPS = 1.0e-6;
    private static final double W = 400;
    private static final double H = 200;
    private static final double M = 10;

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** gluPerspective(90°, aspect 2, 0.05, 100), column-major. */
    private static final float[] PROJECTION;

    static {
        float f = 1f;
        float near = 0.05f;
        float far = 100f;
        PROJECTION = new float[16];
        PROJECTION[0] = f / 2f;
        PROJECTION[5] = f;
        PROJECTION[10] = (far + near) / (near - far);
        PROJECTION[11] = -1f;
        PROJECTION[14] = 2f * far * near / (near - far);
    }

    private static double[] place(double x, double y, double z, RingPlacement.Distortion d) {
        double[] out = new double[3];
        RingPlacement.place(IDENTITY, PROJECTION, x, y, z, W, H, M, d, out);
        return out;
    }

    @Test
    void pointAheadIsAtTheCentre() {
        double[] p = place(0, 0, -5, null);
        assertEquals(200, p[0], EPS);
        assertEquals(100, p[1], EPS);
        assertEquals(1.0, p[2]);
    }

    @Test
    void visiblePointMapsThroughThePerspective() {
        // x = 5 at depth 10 with half-width tan = 2 → ndc 0.25 → u 0.625; y up 5 → ndc 0.5 → v 0.25.
        double[] p = place(5, 5, -10, null);
        assertEquals(0.625 * W, p[0], EPS);
        assertEquals(0.25 * H, p[1], EPS);
        assertEquals(1.0, p[2]);
    }

    @Test
    void offScreenPointIsClampedAlongItsDirection() {
        double[] p = place(100, 0, -1, null);
        assertEquals(W - M, p[0], EPS);
        assertEquals(H / 2, p[1], EPS);
        assertEquals(0.0, p[2]);
    }

    @Test
    void pointBehindGoesToTheEdgeOnItsSide() {
        double[] right = place(3, 0, 5, null);
        assertEquals(W - M, right[0], EPS);
        assertEquals(H / 2, right[1], EPS);
        assertEquals(0.0, right[2]);

        double[] up = place(0, 3, 5, null);
        assertEquals(W / 2, up[0], EPS);
        assertEquals(M, up[1], EPS);

        double[] straightBehind = place(0, 0, 5, null);
        assertEquals(W / 2, straightBehind[0], EPS);
        assertEquals(H - M, straightBehind[1], EPS);
    }

    @Test
    void distortionIsAppliedToVisiblePoints() {
        double[] p = place(5, 5, -10, (u, v, out) -> {
            out[0] = 0.5 + (u - 0.5) * 0.5;
            out[1] = 0.5 + (v - 0.5) * 0.5;
        });
        assertEquals((0.5 + 0.125 * 0.5) * W, p[0], EPS);
        assertEquals((0.5 - 0.25 * 0.5) * H, p[1], EPS);
    }

    @Test
    void clampKeepsInsidePointsAndHandlesZeroBox() {
        double[] out = new double[2];
        assertEquals(false, RingPlacement.clampToBox(210, 105, 200, 100, 50, 50, out));
        assertEquals(210, out[0], EPS);
        assertEquals(true, RingPlacement.clampToBox(210, 105, 200, 100, 0, 0, out));
        assertEquals(200, out[0], EPS);
        assertEquals(100, out[1], EPS);
    }
}
