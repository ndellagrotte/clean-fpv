package io.github.ndellagrotte.cleanfpv.server.net;

import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.server.net.TransformRules.Verdict;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformRulesTest {

    private static final DroneBuild BUILD = DroneBuild.fiveInch();

    private static TransformSnapshot snap(float vx, float vy, float vz, float omega) {
        return new TransformSnapshot(0f, 0f, 0f, 1f, vx, vy, vz, new float[] {omega, -omega, omega, -omega}, 1000L);
    }

    // ---------------------------------------------------------------------------------------------
    // Validation

    @Test
    void acceptsNormalTransform() {
        assertEquals(Verdict.OK, TransformRules.validate(true, BUILD, snap(10f, -3f, 4f, 1500f), 500));
    }

    @Test
    void rejectsWhenNotArmedFirst() {
        Verdict v = TransformRules.validate(false, BUILD, snap(Float.NaN, 0f, 0f, 0f), 500);
        assertEquals(Verdict.NOT_ARMED, v);
        assertFalse(v.suspicious());
    }

    @Test
    void rejectsNonFinite() {
        assertEquals(Verdict.NON_FINITE, TransformRules.validate(true, BUILD, snap(0f, Float.NaN, 0f, 0f), 500));
        assertEquals(Verdict.NON_FINITE, TransformRules.validate(true, BUILD, snap(0f, 0f, 0f, Float.POSITIVE_INFINITY), 500));
        TransformSnapshot zeroQuat = new TransformSnapshot(0f, 0f, 0f, 0f, 0f, 0f, 0f, new float[4], 0L);
        assertEquals(Verdict.NON_FINITE, TransformRules.validate(true, BUILD, zeroQuat, 500));
    }

    @Test
    void speedCapWithSmallTolerance() {
        assertEquals(Verdict.OK, TransformRules.validate(true, BUILD, snap(0f, 0f, 20f, 0f), 20));
        assertEquals(Verdict.OK, TransformRules.validate(true, BUILD, snap(12f, 0f, 16f, 0f), 20)); // |v| = 20
        assertEquals(Verdict.TOO_FAST, TransformRules.validate(true, BUILD, snap(0f, 0f, 20.5f, 0f), 20));
        assertTrue(Verdict.TOO_FAST.suspicious());
    }

    @Test
    void omegaLimitIsFivePercentAboveNoLoad() {
        double noLoad = BUILD.noLoadOmega(); // 2400 Kv · 2π/60 · 4 · 4.2 ≈ 4222 rad/s
        assertEquals(2400 * 2 * Math.PI / 60 * 4 * 4.2, noLoad, 1e-6);
        float ok = (float) (noLoad * 1.049);
        float bad = (float) (noLoad * 1.06);
        assertEquals(Verdict.OK, TransformRules.validate(true, BUILD, snap(0f, 0f, 0f, ok), 500));
        assertEquals(Verdict.OK, TransformRules.validate(true, BUILD, snap(0f, 0f, 0f, -ok), 500));
        assertEquals(Verdict.OMEGA_TOO_HIGH, TransformRules.validate(true, BUILD, snap(0f, 0f, 0f, bad), 500));
        assertEquals(Verdict.OMEGA_TOO_HIGH, TransformRules.validate(true, BUILD, snap(0f, 0f, 0f, -bad), 500));
    }

    @Test
    void serverOmegaLimitCoversClientMotorClampForLowKv() {
        // The client motor model floors Kv at DroneBuild.MIN_KV; the server limit must agree.
        for (float kv : new float[] {0f, 10f, 49f, 50f, 2400f}) {
            DroneBuild b = DroneBuild.fiveInch();
            b.motorKv = kv;
            double clientClamp = io.github.ndellagrotte.cleanfpv.client.physics.MotorParams.of(b).noLoadOmega();
            assertTrue(TransformRules.omegaLimit(b) >= clientClamp, "Kv " + kv);
            assertTrue(TransformRules.omegaLimit(b.copy().sanitize()) >= clientClamp, "sanitized Kv " + kv);
        }
        DroneBuild zero = DroneBuild.fiveInch();
        zero.motorKv = 0f;
        assertEquals(DroneBuild.MIN_KV, zero.sanitize().motorKv, 0f);
    }

    @Test
    void missingBuildIsRejected() {
        assertEquals(Verdict.NO_BUILD, TransformRules.validate(true, null, snap(0f, 0f, 0f, 10f), 500));
    }

    // ---------------------------------------------------------------------------------------------
    // Roll

    /** Camera attitude for Minecraft yaw/pitch/roll (degrees): yaw about −Y, pitch about local X, roll about local Z. */
    private static Quaternionf camera(float yawDeg, float pitchDeg, float rollDeg) {
        return new Quaternionf()
                .rotationY((float) Math.toRadians(-yawDeg))
                .rotateX((float) Math.toRadians(pitchDeg))
                .rotateZ((float) Math.toRadians(rollDeg));
    }

    /** Body attitude whose camera (tilted up by {@code tilt}) has the given Minecraft angles. */
    private static Quaternionf bodyFor(float yawDeg, float pitchDeg, float rollDeg, float tiltDeg) {
        return camera(yawDeg, pitchDeg, rollDeg).rotateX((float) Math.toRadians(tiltDeg));
    }

    @Test
    void cameraHelperMatchesMinecraftLookVector() {
        // Minecraft look for yaw θ, pitch φ: (−sinθ cosφ, −sinφ, cosθ cosφ).
        float yaw = 57f;
        float pitch = 23f;
        Vector3f f = camera(yaw, pitch, 0f).transform(new Vector3f(0f, 0f, 1f));
        double t = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        assertEquals(-Math.sin(t) * Math.cos(p), f.x, 1e-5);
        assertEquals(-Math.sin(p), f.y, 1e-5);
        assertEquals(Math.cos(t) * Math.cos(p), f.z, 1e-5);
    }

    @Test
    void levelDroneHasZeroRoll() {
        assertEquals(0f, TransformRules.cameraRollDeg(0f, 0f, 0f, 1f, 0f), 1e-4);
        Quaternionf q = bodyFor(123f, -10f, 0f, 30f);
        assertEquals(0f, TransformRules.cameraRollDeg(q.x, q.y, q.z, q.w, 30f), 1e-3);
    }

    @Test
    void rollingRightIsPositive() {
        // Facing south (+Z), pilot's right is west (−X): up tilting toward −X is a right roll.
        Quaternionf q = new Quaternionf().rotationZ((float) Math.toRadians(20));
        Vector3f up = q.transform(new Vector3f(0f, 1f, 0f));
        assertTrue(up.x < 0f);
        assertEquals(20f, TransformRules.cameraRollDeg(q.x, q.y, q.z, q.w, 0f), 1e-3);
    }

    @Test
    void recoversRollForManyAttitudesAndTilts() {
        float[] yaws = {0f, 45f, -90f, 170f, -135f};
        float[] pitches = {0f, 30f, -60f, 80f, -85f};
        float[] rolls = {0f, 15f, -40f, 90f, -120f, 179f};
        float[] tilts = {0f, 30f, 80f, -20f};
        for (float yaw : yaws) {
            for (float pitch : pitches) {
                for (float roll : rolls) {
                    for (float tilt : tilts) {
                        Quaternionf q = bodyFor(yaw, pitch, roll, tilt);
                        float got = TransformRules.cameraRollDeg(q.x, q.y, q.z, q.w, tilt);
                        double diff = Math.abs(((got - roll) % 360 + 540) % 360 - 180);
                        assertTrue(diff < 0.05, () -> "yaw " + yaw + " pitch " + pitch + " roll " + roll
                                + " tilt " + tilt + " → " + got);
                    }
                }
            }
        }
    }

    @Test
    void straightDownIsDegenerateZero() {
        Quaternionf q = camera(0f, 90f, 0f);
        assertEquals(0f, TransformRules.cameraRollDeg(q.x, q.y, q.z, q.w, 0f), 1e-6);
    }

    @Test
    void degenerateInputsGiveZero() {
        assertEquals(0f, TransformRules.cameraRollDeg(0f, 0f, 0f, 0f, 0f));
        assertEquals(0f, TransformRules.cameraRollDeg(0f, 0f, 0.3f, 0.9f, Float.NaN));
    }

    @Test
    void unnormalisedQuaternionIsAccepted() {
        Quaternionf q = bodyFor(10f, 20f, 35f, 30f);
        float got = TransformRules.cameraRollDeg(q.x * 3f, q.y * 3f, q.z * 3f, q.w * 3f, 30f);
        assertEquals(35f, got, 1e-3);
    }
}
