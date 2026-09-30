package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.client.input.SwitchDebouncer.Edge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwitchDebouncerTest {

    @Test
    void firstReadingIsABaselineWithoutEdge() {
        SwitchDebouncer d = new SwitchDebouncer();
        assertEquals(Edge.NONE, d.update(true, true, 1000));
        assertTrue(d.state());
        assertEquals(Edge.NONE, d.update(true, true, 5000));
    }

    @Test
    void changeAfterLockoutIsAcceptedImmediately() {
        SwitchDebouncer d = new SwitchDebouncer();
        d.update(true, false, 0);
        assertEquals(Edge.RISING, d.update(true, true, 500));
        assertEquals(Edge.FALLING, d.update(true, false, 700));
    }

    @Test
    void changeInsideLockoutIsDeferredNotDropped() {
        SwitchDebouncer d = new SwitchDebouncer();
        d.update(true, false, 0);
        assertEquals(Edge.RISING, d.update(true, true, 1000));
        // Flipped back off 50 ms later: swallowed for now...
        assertEquals(Edge.NONE, d.update(true, false, 1050));
        assertTrue(d.state());
        assertEquals(Edge.NONE, d.update(true, false, 1199));
        // ...but re-evaluated once the 200 ms lockout expires.
        assertEquals(Edge.FALLING, d.update(true, false, 1200));
        assertFalse(d.state());
    }

    @Test
    void bounceThatSettlesBackProducesNoEdge() {
        SwitchDebouncer d = new SwitchDebouncer();
        d.update(true, true, 0);
        assertEquals(Edge.NONE, d.update(true, false, 50));
        assertEquals(Edge.NONE, d.update(true, true, 120));
        assertEquals(Edge.NONE, d.update(true, true, 400));
        assertTrue(d.state());
    }

    @Test
    void losingTheSourceWhileOnFailsSafeOnce() {
        SwitchDebouncer d = new SwitchDebouncer();
        d.update(true, false, 0);
        assertEquals(Edge.RISING, d.update(true, true, 300));
        assertEquals(Edge.FALLING, d.update(false, false, 310));
        assertEquals(Edge.NONE, d.update(false, false, 320));
        // Reconnect with the switch still ON: baseline, no re-arm.
        assertEquals(Edge.NONE, d.update(true, true, 330));
        assertEquals(Edge.NONE, d.update(true, true, 900));
    }

    @Test
    void resetRebaselines() {
        SwitchDebouncer d = new SwitchDebouncer();
        d.update(true, false, 0);
        d.reset();
        assertEquals(Edge.NONE, d.update(true, true, 10));
        assertTrue(d.state());
    }

    @Test
    void armIntentRules() {
        assertEquals(ArmIntent.ARM, ArmIntent.resolve(true, Edge.NONE, false));
        assertEquals(ArmIntent.DISARM, ArmIntent.resolve(true, Edge.NONE, true));
        assertEquals(ArmIntent.DISARM, ArmIntent.resolve(true, Edge.RISING, true), "key wins");
        assertEquals(ArmIntent.ARM, ArmIntent.resolve(false, Edge.RISING, false));
        assertEquals(ArmIntent.NONE, ArmIntent.resolve(false, Edge.RISING, true));
        assertEquals(ArmIntent.DISARM, ArmIntent.resolve(false, Edge.FALLING, true));
        assertEquals(ArmIntent.NONE, ArmIntent.resolve(false, Edge.FALLING, false));
        assertEquals(ArmIntent.NONE, ArmIntent.resolve(false, Edge.NONE, true));
    }
}
