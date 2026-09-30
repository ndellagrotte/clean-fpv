package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TickConsistencyTest {

    private static final double HALF = 0.2f / 2.0f;
    /** A block column whose corner is at (x=1, z=1): occupies x ∈ [1,2], z ∈ [−5,1]. */
    private static final CollisionWorld CORNER = region -> List.of(new AxisAlignedBB(1, -5, -5, 2, 5, 1));

    @Test
    void snapsWhenSubstepsRoundedACornerTheStraightReplayCannot() {
        DroneState s = new DroneState();
        s.lastPosition.set(0.5, 0, 0.5);
        // The substeps went first +Z past the corner, then +X: legal step by step.
        s.position.set(1.5, 0, 1.5);
        s.velocity.set(20, 0, 20);
        boolean snapped = Stepper.enforceTickConsistency(s, s.lastPosition, CORNER, PhysicsEngine.SNAP_DISTANCE);
        assertTrue(snapped);
        // The server's straight Y→X→Z replay stops X at the wall face, then Z is free.
        assertEquals(1.0 - HALF, s.position.x, 1e-9);
        assertEquals(1.5, s.position.z, 1e-9);
        assertEquals(0.0, s.velocity.x, 0.0, "clipped component zeroed");
        assertEquals(20.0, s.velocity.z, 0.0);
    }

    @Test
    void keepsSmallDiscrepanciesBelowTheThreshold() {
        DroneState s = new DroneState();
        s.lastPosition.set(0.5, 0, 0.5);
        s.position.set(1.05, 0, 1.3); // straight replay ends at x = 0.9: error 0.15 < 0.2
        s.velocity.set(5, 0, 5);
        assertFalse(Stepper.enforceTickConsistency(s, s.lastPosition, CORNER, PhysicsEngine.SNAP_DISTANCE));
        assertEquals(1.05, s.position.x, 0.0);
        assertEquals(5.0, s.velocity.x, 0.0);
    }

    @Test
    void verticalErrorsAreIgnoredLikeTheServer() {
        CollisionWorld ceiling = region -> List.of(new AxisAlignedBB(-5, 1, -5, 5, 2, 5));
        DroneState s = new DroneState();
        s.lastPosition.set(0, 0, 0);
        s.position.set(0, 3, 0); // passed the slab vertically (impossible, but only Y differs)
        assertFalse(Stepper.enforceTickConsistency(s, s.lastPosition, ceiling, PhysicsEngine.SNAP_DISTANCE));
    }

    @Test
    void engineAppliesTheRuleInTickModeAndOnRealtimeTickBoundaries() {
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        s.lastPosition.set(0.5, 0, 0.5);
        s.position.set(1.5, 0, 1.5);
        assertTrue(e.markTickBoundary(s, CORNER, false));
        assertEquals(s.position, s.lastPosition, "new tick starts at the snapped point");
        s.position.set(3, 0, 3);
        s.lastPosition.set(0.5, 0, 0.5);
        assertFalse(e.markTickBoundary(s, CORNER, true), "no-clip skips the rule");
    }
}
