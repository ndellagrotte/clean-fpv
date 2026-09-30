package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsEngineTest {

    private static final PhysicsConfig V2 = new PhysicsConfig(DroneBuild.fiveInch(), true, false, false, 500);
    private static final CollisionWorld FLOOR = region -> List.of(new AxisAlignedBB(-100, -1, -100, 100, 0, 100));

    private static StepInput input(float thr) {
        return new StepInput(new Quaternionf(), thr, PhysicsEngine.TICK_DT, false);
    }

    @Test
    void freeFallWithMotorsOff() {
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        e.onArm(s, 0, 50, 0);
        PhysicsEngine.Result r = e.stepTick(s, input(-1f), V2, CollisionWorld.EMPTY);
        assertEquals(7, r.substeps());
        assertEquals(-Stepper.GRAVITY * 0.05, s.velocity.y, 1e-3);
        assertEquals(50 - 0.5 * Stepper.GRAVITY * 0.05 * 0.05, s.position.y, 2e-3);
        assertTrue(s.maxAbsOmega() < 1.0, "only slight windmilling");
        assertEquals(MassModel.flownMassKg(V2.build()), s.massKg, 1e-12);
    }

    @Test
    void restsOnTheFloor() {
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        e.onArm(s, 0, 0, 0);
        for (int i = 0; i < 40; i++) {
            e.stepTick(s, input(-1f), V2, FLOOR);
        }
        assertEquals(0.0, s.position.y, 1e-9);
        assertEquals(0.0, s.velocity.length(), 1e-9);
        assertTrue(s.collided);
    }

    @Test
    void climbsAtFullThrottle() {
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        e.onArm(s, 0, 0, 0);
        for (int i = 0; i < 20; i++) {
            e.stepTick(s, input(1f), V2, FLOOR);
        }
        assertTrue(s.position.y > 1.0, "y " + s.position.y);
        assertTrue(s.velocity.y > 0.0);
    }

    @Test
    void speedIsClampedToTheServerCap() {
        PhysicsConfig capped = new PhysicsConfig(DroneBuild.fiveInch(), true, false, false, 10);
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        e.onArm(s, 0, 1000, 0);
        s.velocity.set(0, -300, 0);
        e.stepTick(s, input(-1f), capped, CollisionWorld.EMPTY);
        assertTrue(s.velocity.length() <= 10.0 + 1e-9);
    }

    @Test
    void noClipIgnoresWalls() {
        CollisionWorld wall = region -> List.of(new AxisAlignedBB(1, -10, -10, 2, 10, 10));
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        e.onArm(s, 0, 0, 0);
        s.velocity.set(60, 0, 0);
        PhysicsEngine.Result r = e.stepTick(s, new StepInput(new Quaternionf(), 0f, 0.05, true), V2, wall);
        assertTrue(s.position.x > 2.5);
        assertFalse(r.collided());
        assertFalse(r.snapped());
    }

    @Test
    void realtimeAndTickShareTheSubstep() {
        PhysicsEngine a = new PhysicsEngine();
        PhysicsEngine b = new PhysicsEngine();
        DroneState sa = new DroneState();
        DroneState sb = new DroneState();
        a.onArm(sa, 0, 10, 0);
        b.onArm(sb, 0, 10, 0);
        sa.velocity.set(3, 1, -2);
        sb.velocity.set(3, 1, -2);
        a.stepTick(sa, input(0.3f), V2, CollisionWorld.EMPTY);
        b.stepRealtime(sb, input(0.3f), V2, CollisionWorld.EMPTY);
        assertEquals(0.0, sa.position.distance(sb.position), 1e-12);
        assertEquals(0.0, sa.velocity.distance(sb.velocity), 1e-12);
        assertEquals(PhysicsEngine.Result.NONE,
                b.stepRealtime(sb, new StepInput(new Quaternionf(), 0f, 0.0, false), V2, CollisionWorld.EMPTY));
    }

    @Test
    void disarmedTrackingFeedsArmVelocity() {
        DroneState s = new DroneState();
        s.velocity.set(99, 99, 99);
        PhysicsEngine.trackDisarmed(s, 0, 0, 0, 0.05);
        PhysicsEngine.trackDisarmed(s, 1, 0, -0.5, 0.05);
        assertEquals(new Vector3d(20, 0, -10), s.velocity);
        PhysicsEngine.trackDisarmed(s, 1e6, 0, 0, 0.05); // teleport
        assertTrue(s.velocity.length() <= PhysicsConfig.ABSOLUTE_MAX_SPEED + 1e-9);
        new PhysicsEngine().onArm(s, 5, 6, 7);
        assertTrue(s.velocity.length() > 0, "velocity inherited");
        assertEquals(new Vector3d(5, 6, 7), s.lastPosition);
    }

    @Test
    void syncAdoptsExternalTeleports() {
        DroneState s = new DroneState();
        s.position.set(1, 2, 3);
        assertFalse(PhysicsEngine.syncPosition(s, 1, 2, 3 + 1e-6));
        assertTrue(PhysicsEngine.syncPosition(s, 10, 2, 3));
        assertEquals(new Vector3d(10, 2, 3), s.lastPosition);
    }

    @Test
    void garbageInputsKeepStateFinite() {
        PhysicsEngine e = new PhysicsEngine();
        DroneState s = new DroneState();
        e.onArm(s, 0, 10, 0);
        s.velocity.set(Double.NaN, 1, 1);
        e.stepTick(s, new StepInput(new Quaternionf(Float.NaN, 0, 0, 1), Float.NaN, Double.NaN, false), V2, FLOOR);
        assertTrue(s.position.isFinite() && s.velocity.isFinite());
        e.stepRealtime(s, new StepInput(new Quaternionf(0, 0, 0, 0), 1f, 1e9, false), V2, FLOOR);
        assertTrue(s.position.isFinite() && s.velocity.isFinite());
    }

    @Test
    void switchesModelsWithTheConfig() {
        PhysicsEngine e = new PhysicsEngine();
        assertTrue(e.model(V2) instanceof ThrustV2);
        PhysicsConfig v1 = new PhysicsConfig(DroneBuild.fiveInch(), false, false, false, 500);
        assertTrue(e.model(v1) instanceof ThrustV1);
        assertFalse(e.overheating(new DroneState(), v1));
    }
}
