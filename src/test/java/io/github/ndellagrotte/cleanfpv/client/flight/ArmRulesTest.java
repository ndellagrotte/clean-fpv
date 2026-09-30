package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.client.flight.ArmRules.Refusal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmRulesTest {

    private static final int PROTOCOL = 1;

    @Test
    void armsWhenEverythingIsFine() {
        assertEquals(Refusal.NONE, ArmRules.check(true, PROTOCOL, PROTOCOL, false, true, true));
        assertNull(Refusal.NONE.langKey());
    }

    @Test
    void noHelloRefusesBeforeAnythingElse() {
        assertEquals(Refusal.NO_SERVER, ArmRules.check(false, 0, PROTOCOL, true, true, false));
    }

    @Test
    void protocolMismatchRefuses() {
        assertEquals(Refusal.PROTOCOL, ArmRules.check(true, PROTOCOL + 1, PROTOCOL, false, true, true));
    }

    @Test
    void ridingRefusesBeforeThrottle() {
        assertEquals(Refusal.RIDING, ArmRules.check(true, PROTOCOL, PROTOCOL, true, true, false));
    }

    @Test
    void throttleGuardOnlyForJoystickSticks() {
        assertEquals(Refusal.THROTTLE, ArmRules.check(true, PROTOCOL, PROTOCOL, false, true, false));
        // Keyboard flight idles at 50 % and is exempt.
        assertEquals(Refusal.NONE, ArmRules.check(true, PROTOCOL, PROTOCOL, false, false, false));
    }

    @Test
    void everyRefusalHasAMessageKey() {
        for (Refusal r : Refusal.values()) {
            if (r != Refusal.NONE) {
                assertNotNull(r.langKey(), r.name());
                assertTrue(r.langKey().startsWith("cleanfpv.message.arm_refused."), r.name());
            }
        }
    }

    @Test
    void forcedDisarmTriggers() {
        assertFalse(ArmRules.mustForceDisarm(false, true, false, false, false), "disarmed: nothing to do");
        assertFalse(ArmRules.mustForceDisarm(true, false, true, true, true), "healthy armed pilot");
        assertTrue(ArmRules.mustForceDisarm(true, true, true, true, true), "request from client net");
        assertTrue(ArmRules.mustForceDisarm(true, false, false, false, false), "player gone");
        assertTrue(ArmRules.mustForceDisarm(true, false, true, false, true), "new player entity");
        assertTrue(ArmRules.mustForceDisarm(true, false, true, true, false), "dead");
    }

    @Test
    void flyingAfterDisarmFollowsGameMode() {
        assertTrue(ArmRules.flyingAfterDisarm(false, true, false), "spectators always fly");
        assertTrue(ArmRules.flyingAfterDisarm(true, false, true), "creative keeps its pre-arm flight");
        assertFalse(ArmRules.flyingAfterDisarm(true, false, false));
        assertFalse(ArmRules.flyingAfterDisarm(false, false, true), "survival falls");
    }

    @Test
    void momentumHandOverIsPerTick() {
        assertEquals(1.0, ArmRules.handOverMotion(20.0), 1e-12);
        assertEquals(-0.5, ArmRules.handOverMotion(-10.0), 1e-12);
        assertEquals(0.0, ArmRules.handOverMotion(Double.NaN));
        assertEquals(0.0, ArmRules.handOverMotion(Double.POSITIVE_INFINITY));
    }

    @Test
    void teleportDetectionUsesTheSpeedCapPerTick() {
        // 500 m/s × 0.05 s = 25 blocks per tick.
        assertFalse(ArmRules.isTeleport(24.9, 0, 0, 500));
        assertTrue(ArmRules.isTeleport(20, 20, 0, 500));
        assertTrue(ArmRules.isTeleport(Double.NaN, 0, 0, 500));
        assertFalse(ArmRules.isTeleport(0, -3.92, 0, 500), "terminal-velocity fall is motion");
    }
}
