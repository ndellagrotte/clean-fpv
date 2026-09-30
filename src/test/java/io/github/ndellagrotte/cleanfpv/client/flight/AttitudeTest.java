package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.common.config.RateTriple;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttitudeTest {

    private static final RateTriple LINEAR = new RateTriple(0.5f, 0f, 0f); // 100 °/s per unit stick

    @Test
    void stickSignsGiveConventionalResponse() {
        // Level, facing south (+Z). Screen right is west (−X).
        Vector3f f = new Vector3f();
        Vector3f u = new Vector3f();

        Quaternionf q = Attitude.frameStep(new Quaternionf(), 1f, 0f, 0f, LINEAR, LINEAR, LINEAR, 0.1, 0, 0);
        Attitude.forward(q, f);
        assertTrue(f.x < -0.1f, "yaw right turns the nose west: " + f);
        assertEquals(Math.toRadians(10.0), Math.asin(-f.x), 1e-5);

        q = Attitude.frameStep(new Quaternionf(), 0f, 1f, 0f, LINEAR, LINEAR, LINEAR, 0.1, 0, 0);
        assertTrue(Attitude.forward(q, f).y < -0.1f, "pitch forward = nose down");

        q = Attitude.frameStep(new Quaternionf(), 0f, 0f, 1f, LINEAR, LINEAR, LINEAR, 0.1, 0, 0);
        assertTrue(Attitude.up(q, u).x < -0.1f, "roll right tilts the top toward screen right");

        q = Attitude.frameStep(new Quaternionf(), 0f, 0f, 0f, LINEAR, LINEAR, LINEAR, 0.1, 0.2, 0);
        assertEquals(0.2, Math.asin(Attitude.forward(q, f).y), 1e-5, "mouse up (event sign) = nose up, not × dt");

        q = Attitude.frameStep(new Quaternionf(), 0f, 0f, 0f, LINEAR, LINEAR, LINEAR, 0.1, 0, 0.3);
        assertEquals(-Math.sin(0.3), Attitude.up(q, u).x, 1e-5, "mouse right = roll right");
    }

    @Test
    void appliesBodyAxisRotationsInSpecOrder() {
        Quaternionf start = new Quaternionf().rotationYXZ(0.4f, -0.3f, 0.2f);
        double yaw = 0.05;
        double pitch = -0.07;
        double roll = 0.11;
        Quaternionf q = Attitude.rotateBody(new Quaternionf(start), yaw, pitch, roll);
        Quaternionf expected = new Quaternionf(start).rotateY((float) -yaw).rotateX((float) pitch)
                .rotateZ((float) roll);
        assertTrue(q.equals(expected, 1e-6f) || q.equals(new Quaternionf(expected).mul(-1f), 1e-6f),
                q + " vs " + expected);
        // Each rotation is about the axis as moved by the previous one: roll axis = forward after yaw+pitch.
        Vector3f fwdAfterYawPitch = new Quaternionf(start).rotateY((float) -yaw).rotateX((float) pitch)
                .transform(new Vector3f(0, 0, 1));
        Vector3f fwd = Attitude.forward(q, new Vector3f());
        assertEquals(0f, fwd.distance(fwdAfterYawPitch), 1e-5f, "roll leaves the forward axis in place");
    }

    @Test
    void integratesSmallRotationsAccurately() {
        Quaternionf q = new Quaternionf();
        // 90 °/s yaw for 1 s in 240 frames
        RateTriple r = new RateTriple(0.45f, 0f, 0f);
        for (int i = 0; i < 240; i++) {
            Attitude.frameStep(q, 1f, 0f, 0f, r, r, r, 1.0 / 240.0, 0, 0);
        }
        Vector3f f = Attitude.forward(q, new Vector3f());
        assertEquals(-1f, f.x, 1e-4f);
        assertEquals(0f, f.z, 1e-3f);
    }

    @Test
    void staysNormalisedOverLongRandomFlights() {
        Random rnd = new Random(7);
        Quaternionf q = new Quaternionf();
        RateTriple r = RateTriple.radio();
        for (int i = 0; i < 200_000; i++) {
            Attitude.frameStep(q, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2 - 1,
                    r, r, r, 1.0 / 144.0, rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01);
        }
        assertEquals(1f, q.lengthSquared(), 1e-5f);
        Vector3f f = Attitude.forward(q, new Vector3f());
        Vector3f u = Attitude.up(q, new Vector3f());
        Vector3f right = Attitude.right(q, new Vector3f());
        assertEquals(0f, f.dot(u), 1e-5f);
        assertEquals(0f, right.distance(new Vector3f(u).cross(f)), 1e-5f, "right = up × forward");
    }

    @Test
    void degenerateInputsAreHarmless() {
        Quaternionf q = new Quaternionf(0, 0, 0, 0);
        Attitude.renormalize(q);
        assertEquals(new Quaternionf(), q);
        q = Attitude.frameStep(new Quaternionf(), 1f, 1f, 1f, null, RateTriple.radio(), RateTriple.radio(),
                Double.NaN, Double.NaN, Double.POSITIVE_INFINITY);
        assertTrue(q.isFinite());
        assertEquals(new Quaternionf(), q);
    }

    @Test
    void cameraLooksAlongTheLookDirectionRightAfterInit() {
        Random rnd = new Random(3);
        for (int i = 0; i < 2000; i++) {
            float yaw = rnd.nextFloat() * 720f - 360f;
            float pitch = rnd.nextFloat() * 180f - 90f;
            float tilt = rnd.nextFloat() * 90f - 10f;
            Quaternionf body = Attitude.initFromLook(yaw, pitch, tilt, new Quaternionf());
            Vector3f cam = CameraRig.cameraForward(body, tilt, new Vector3f());
            double y = Math.toRadians(yaw);
            double p = Math.toRadians(pitch);
            Vector3f look = new Vector3f((float) (-Math.sin(y) * Math.cos(p)), (float) -Math.sin(p),
                    (float) (Math.cos(y) * Math.cos(p)));
            assertEquals(0f, cam.distance(look), 1e-4f, "yaw " + yaw + " pitch " + pitch + " tilt " + tilt);

            CameraPose pose = CameraRig.poseFor(body, tilt, 135f, 16f / 9f);
            assertEquals(0f, pose.rollDeg(), Math.abs(pitch) > 89.9f ? 1f : 2e-2f);
            assertEquals(pitch, pose.pitchDeg(), 2e-2f);
            if (Math.abs(pitch) < 89f) {
                assertEquals(0f, CameraRig.unwrapNear(pose.yawDeg(), yaw) - yaw, 2e-2f);
            }
            // body starts level in roll, tilted down by the camera angle
            Vector3f bodyFwd = Attitude.forward(body, new Vector3f());
            assertEquals(Math.sin(Math.toRadians(-(pitch + tilt))), bodyFwd.y, 1e-4);
        }
    }
}
