package io.github.ndellagrotte.cleanfpv.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropSpinTest {

    private static final long MS = 1_000_000L;

    @Test
    void firstCallOnlyRecordsTheClock() {
        PropSpin spin = new PropSpin();
        spin.advance(new float[] {100f, 100f, 100f, 100f}, 5_000 * MS);
        assertEquals(0f, spin.angle(0));
    }

    @Test
    void integratesSignedSpeedAndWraps() {
        PropSpin spin = new PropSpin();
        float[] omega = {1f, -1f, 0f, 10f};
        spin.advance(omega, 1_000 * MS);
        spin.advance(omega, 1_050 * MS);
        assertEquals(0.05f, spin.angle(0), 1e-5f);
        assertEquals((float) (2 * Math.PI - 0.05), spin.angle(1), 1e-5f);
        assertEquals(0f, spin.angle(2));
        assertEquals(0.5f, spin.angle(3), 1e-5f);
    }

    @Test
    void repeatedCallsInOneFrameDoNotDoubleCount() {
        PropSpin once = new PropSpin();
        PropSpin twice = new PropSpin();
        float[] omega = {50f, 50f, 50f, 50f};
        once.advance(omega, 0L + 1);
        twice.advance(omega, 0L + 1);
        once.advance(omega, 20 * MS + 1);
        twice.advance(omega, 20 * MS + 1);
        twice.advance(omega, 20 * MS + 1);
        assertEquals(once.angle(0), twice.angle(0), 1e-6f);
    }

    @Test
    void stepIsClamped() {
        assertEquals(PropSpin.MAX_STEP, PropSpin.stepSeconds(0L, 5_000 * MS));
        assertEquals(0f, PropSpin.stepSeconds(10 * MS, 5 * MS));
    }

    @Test
    void wrapHandlesEdgeCases() {
        assertEquals(0f, PropSpin.wrap(Double.NaN));
        assertEquals(0f, PropSpin.wrap(2 * Math.PI), 1e-6f);
        float w = PropSpin.wrap(-0.25);
        assertTrue(w >= 0f && w < (float) (2 * Math.PI));
    }

    @Test
    void discThreshold() {
        assertTrue(PropSpin.showsDisc(30f));
        assertTrue(PropSpin.showsDisc(-30f));
        assertFalse(PropSpin.showsDisc(29.9f));
    }
}
