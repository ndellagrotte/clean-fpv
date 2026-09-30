package io.github.ndellagrotte.cleanfpv.client.net;

import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteInterpolationTest {

    private static final double EPS = 1e-4;

    private static void assertSameRotation(Quaternionf expected, Quaternionf actual) {
        double angle = RemoteInterpolation.angleBetween(expected, actual);
        assertTrue(angle < 1e-3, () -> "expected " + expected + " got " + actual + " (Δ " + angle + " rad)");
    }

    @Test
    void noOlderSampleReturnsNewer() {
        Quaternionf newer = new Quaternionf().rotationY(0.7f);
        Quaternionf out = RemoteInterpolation.extrapolate(null, 0L, newer, 1000L, 0.1, new Quaternionf());
        assertSameRotation(newer, out);
    }

    @Test
    void zeroElapsedReturnsNewer() {
        Quaternionf older = new Quaternionf().rotationY(0.1f);
        Quaternionf newer = new Quaternionf().rotationY(0.2f);
        Quaternionf out = RemoteInterpolation.extrapolate(older, 0L, newer, 50L, 0.0, new Quaternionf());
        assertSameRotation(newer, out);
    }

    @Test
    void constantRateIsContinued() {
        // 2 rad/s about Y: 0.1 rad per 50 ms packet; 25 ms later → +0.05 rad.
        Quaternionf older = new Quaternionf().rotationY(0.1f);
        Quaternionf newer = new Quaternionf().rotationY(0.2f);
        Quaternionf out = RemoteInterpolation.extrapolate(older, 1000L, newer, 1050L, 0.025, new Quaternionf());
        assertSameRotation(new Quaternionf().rotationY(0.25f), out);
    }

    @Test
    void worldFrameRateAboutTiltedBody() {
        // Body pitched 40°, spinning about world Y at 3 rad/s.
        Quaternionf base = new Quaternionf().rotationX(0.7f);
        Quaternionf older = new Quaternionf().rotationY(0.0f).mul(base);
        Quaternionf newer = new Quaternionf().rotationY(0.15f).mul(base);
        Quaternionf out = RemoteInterpolation.extrapolate(older, 0L, newer, 50L, 0.05, new Quaternionf());
        assertSameRotation(new Quaternionf().rotationY(0.30f).mul(base), out);
    }

    @Test
    void takesShortWayRound() {
        // older and newer are the same rotation with opposite quaternion signs, then a small step:
        // the estimate must be the small step, not ~2π.
        Quaternionf older = new Quaternionf().rotationZ(0.1f);
        older.set(-older.x, -older.y, -older.z, -older.w);
        Quaternionf newer = new Quaternionf().rotationZ(0.2f);
        Quaternionf out = RemoteInterpolation.extrapolate(older, 0L, newer, 100L, 0.05, new Quaternionf());
        assertSameRotation(new Quaternionf().rotationZ(0.25f), out);
    }

    @Test
    void extrapolationIsCapped() {
        Quaternionf older = new Quaternionf().rotationY(0.0f);
        Quaternionf newer = new Quaternionf().rotationY(0.1f);
        Quaternionf out = RemoteInterpolation.extrapolate(older, 0L, newer, 50L, 10.0, new Quaternionf());
        double capped = 0.1 + 2.0 * RemoteInterpolation.MAX_EXTRAPOLATION_S;
        assertSameRotation(new Quaternionf().rotationY((float) capped), out);
    }

    @Test
    void badSenderClockFallsBackToNewer() {
        Quaternionf older = new Quaternionf().rotationY(0.0f);
        Quaternionf newer = new Quaternionf().rotationY(0.1f);
        assertSameRotation(newer, RemoteInterpolation.extrapolate(older, 100L, newer, 100L, 0.05, new Quaternionf()));
        assertSameRotation(newer, RemoteInterpolation.extrapolate(older, 200L, newer, 100L, 0.05, new Quaternionf()));
        assertSameRotation(newer, RemoteInterpolation.extrapolate(older, 0L, newer, 5000L, 0.05, new Quaternionf()));
    }

    @Test
    void inputsAreNotMutated() {
        Quaternionf older = new Quaternionf().rotationY(0.1f);
        Quaternionf newer = new Quaternionf().rotationY(0.2f);
        Quaternionf olderCopy = new Quaternionf(older);
        Quaternionf newerCopy = new Quaternionf(newer);
        RemoteInterpolation.extrapolate(older, 0L, newer, 50L, 0.02, new Quaternionf());
        assertEquals(olderCopy, older);
        assertEquals(newerCopy, newer);
    }

    @Test
    void smoothingMovesTwentyPercentOfTheAngle() {
        Quaternionf current = new Quaternionf().rotationX(0.0f);
        Quaternionf target = new Quaternionf().rotationX(1.0f);
        RemoteInterpolation.smooth(current, target, RemoteInterpolation.SMOOTHING);
        assertSameRotation(new Quaternionf().rotationX(0.2f), current);
        assertEquals(0.8, RemoteInterpolation.angleBetween(current, target), EPS);
    }

    @Test
    void smoothingTakesShortWay() {
        Quaternionf current = new Quaternionf().rotationY(0.0f);
        Quaternionf target = new Quaternionf().rotationY(0.5f);
        target.set(-target.x, -target.y, -target.z, -target.w);
        RemoteInterpolation.smooth(current, target, 0.2f);
        assertSameRotation(new Quaternionf().rotationY(0.1f), current);
    }

    @Test
    void repeatedSmoothingConverges() {
        Quaternionf current = new Quaternionf();
        Quaternionf target = new Quaternionf().rotationXYZ(0.4f, -1.2f, 0.9f);
        for (int i = 0; i < 60; i++) {
            RemoteInterpolation.smooth(current, target, RemoteInterpolation.SMOOTHING);
        }
        assertSameRotation(target, current);
    }
}
