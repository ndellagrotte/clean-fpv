package io.github.ndellagrotte.cleanfpv.client.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AngleReadoutTest {

    @Test
    void showsOnSwitchOnAndTimesOut() {
        AngleReadout r = new AngleReadout();
        r.update(false, 0f, 0L);
        assertFalse(r.visible(0L));
        r.update(true, 0f, 1000L);
        assertTrue(r.visible(1000L));
        r.update(true, 0.02f, 3000L);
        assertTrue(r.visible(3999L));
        assertFalse(r.visible(4000L));
    }

    @Test
    void knobMovementRestartsTimer() {
        AngleReadout r = new AngleReadout();
        r.update(true, 0f, 0L);
        r.update(true, 0.1f, 5000L);
        assertTrue(r.visible(7000L));
        r.update(true, 0.12f, 7000L);
        assertFalse(r.visible(8000L));
    }

    @Test
    void switchOffHidesImmediately() {
        AngleReadout r = new AngleReadout();
        r.update(true, 0f, 0L);
        r.update(false, 0.5f, 10L);
        assertFalse(r.visible(10L));
    }
}
