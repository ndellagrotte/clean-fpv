package io.github.ndellagrotte.cleanfpv.client.flight;

import org.joml.Matrix3d;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraRigTest {

    /**
     * Independent model of vanilla's {@code orientCamera}: GL {@code rotate(roll,Z)},
     * {@code rotate(pitch,X)}, {@code rotate(yaw + 180,Y)} post-multiplied onto the modelview.
     */
    private static Matrix3d vanillaViewRotation(CameraPose p) {
        return new Matrix3d()
                .rotateZ(Math.toRadians(p.rollDeg()))
                .rotateX(Math.toRadians(p.pitchDeg()))
                .rotateY(Math.toRadians(CameraRig.cameraSetupYaw(p)));
    }

    /** Asserts that the pose, applied the vanilla way, shows exactly what the camera attitude sees. */
    private static void assertRendersAs(Quaternionf cam, CameraPose pose, double tol) {
        Matrix3d view = vanillaViewRotation(pose);
        Matrix3d toWorld = new Matrix3d(view).transpose();
        Vector3d eyeForward = toWorld.transform(new Vector3d(0, 0, -1));
        Vector3d eyeUp = toWorld.transform(new Vector3d(0, 1, 0));
        Vector3f f = cam.transform(new Vector3f(0, 0, 1));
        Vector3f u = cam.transform(new Vector3f(0, 1, 0));
        assertEquals(0.0, eyeForward.distance(f.x, f.y, f.z), tol, "forward for " + pose);
        assertEquals(0.0, eyeUp.distance(u.x, u.y, u.z), tol, "up for " + pose);
    }

    @Test
    void eulerRoundTripThroughVanillaCameraConvention() {
        Random rnd = new Random(11);
        for (int i = 0; i < 20_000; i++) {
            Quaternionf cam = new Quaternionf((float) rnd.nextGaussian(), (float) rnd.nextGaussian(),
                    (float) rnd.nextGaussian(), (float) rnd.nextGaussian()).normalize();
            CameraPose pose = CameraRig.poseFromCamera(cam, 90f);
            assertRendersAs(cam, pose, 1e-4);
            assertTrue(pose.pitchDeg() >= -90f && pose.pitchDeg() <= 90f);
        }
    }

    @Test
    void eulerRoundTripNearStraightUpAndDown() {
        float[] pitches = {89f, 89.9f, 89.99f, 89.999f, 90f, -89f, -89.9f, -89.99f, -89.999f, -90f};
        float[] others = {-170f, -45f, 0f, 30f, 135f};
        for (float p : pitches) {
            for (float y : others) {
                for (float r : others) {
                    Quaternionf cam = CameraRig.cameraFromPose(y, p, r, new Quaternionf());
                    CameraPose pose = CameraRig.poseFromCamera(cam, 70f);
                    assertRendersAs(cam, pose, 2e-4);
                    assertEquals(p, pose.pitchDeg(), 0.05f);
                }
            }
        }
    }

    @Test
    void cameraFromPoseInvertsPoseFromCamera() {
        Random rnd = new Random(5);
        for (int i = 0; i < 2000; i++) {
            float yaw = rnd.nextFloat() * 360f - 180f;
            float pitch = rnd.nextFloat() * 178f - 89f;
            float roll = rnd.nextFloat() * 360f - 180f;
            Quaternionf cam = CameraRig.cameraFromPose(yaw, pitch, roll, new Quaternionf());
            CameraPose pose = CameraRig.poseFromCamera(cam, 70f);
            assertEquals(yaw, pose.yawDeg(), 0.01f);
            assertEquals(pitch, pose.pitchDeg(), 0.01f);
            assertEquals(roll, pose.rollDeg(), 0.01f);
            assertRendersAs(cam, pose, 1e-4);
        }
    }

    @Test
    void zeroRollPoseMatchesVanillaLookVector() {
        CameraPose pose = new CameraPose(37f, 21f, 0f, 70f);
        Matrix3d toWorld = vanillaViewRotation(pose).transpose();
        Vector3d fwd = toWorld.transform(new Vector3d(0, 0, -1));
        double y = Math.toRadians(37);
        double p = Math.toRadians(21);
        assertEquals(0.0, fwd.distance(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)), 1e-9);
    }

    @Test
    void tiltPointsTheCameraUp() {
        CameraPose pose = CameraRig.poseFor(new Quaternionf(), 30f, 135f, 16f / 9f);
        assertEquals(-30f, pose.pitchDeg(), 1e-3f);
        assertEquals(0f, pose.yawDeg(), 1e-3f);
        assertEquals(0f, pose.rollDeg(), 1e-3f);
        assertEquals(-30f, CameraRig.cameraPitchDeg(new Quaternionf(), 30f), 1e-3f);
        // rolled body: camera pitch output equals the pose pitch
        Quaternionf body = new Quaternionf().rotationYXZ(0.3f, 0.2f, 0.9f);
        assertEquals(CameraRig.poseFor(body, 25f, 120f, 1.5f).pitchDeg(), CameraRig.cameraPitchDeg(body, 25f), 1e-3f);
    }

    @Test
    void rollRightIsPositiveMinecraftRoll() {
        Quaternionf body = new Quaternionf().rotateZ((float) Math.toRadians(20)); // roll right
        CameraPose pose = CameraRig.poseFor(body, 0f, 135f, 1f);
        assertEquals(20f, pose.rollDeg(), 1e-3f);
    }

    @Test
    void verticalFovFromDiagonal() {
        assertEquals(99.6f, CameraRig.verticalFovDeg(135f, 16f / 9f), 0.1f);
        double expected = Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(45)) / Math.sqrt(2)));
        assertEquals(expected, CameraRig.verticalFovDeg(90f, 1f), 1e-4);
        assertEquals(CameraRig.verticalFovDeg(90f, 1f), CameraRig.verticalFovDeg(90f, Float.NaN), 1e-6f);
        assertEquals(CameraRig.verticalFovDeg(135f, 2f), CameraRig.poseFor(new Quaternionf(), 0f, 135f, 2f).vFovDeg(), 1e-6f);
    }

    @Test
    void tiltFromKnobAndSwitch() {
        assertEquals(30f, CameraRig.tiltDeg(false, 1f, 30f));
        assertEquals(12.5f, CameraRig.tiltDeg(false, -1f, 12.5f));
        assertEquals(10f, CameraRig.tiltDeg(true, -1f, 30f));
        assertEquals(80f, CameraRig.tiltDeg(true, 1f, 30f));
        assertEquals(45f, CameraRig.tiltDeg(true, 0f, 30f));
        float t = CameraRig.tiltDeg(true, 0.33f, 30f);
        assertEquals(0f, t % 5f);
    }

    @Test
    void bodyYawFollowsHeadingIncludingVerticalNose() {
        for (float yaw = -175f; yaw < 180f; yaw += 25f) {
            for (float pitch : new float[] {-90f, -60f, 0f, 45f, 90f}) {
                Quaternionf body = new Quaternionf().rotationYXZ((float) Math.toRadians(-yaw),
                        (float) Math.toRadians(pitch), 0f);
                assertEquals(0f, CameraRig.unwrapNear(CameraRig.bodyYawDeg(body), yaw) - yaw, 1e-2f,
                        "yaw " + yaw + " pitch " + pitch);
            }
        }
    }

    @Test
    void unwrapNearPicksClosestRepresentation() {
        assertEquals(370f, CameraRig.unwrapNear(10f, 360f), 1e-4f);
        assertEquals(-190f, CameraRig.unwrapNear(170f, -170f), 1e-4f);
        assertEquals(5f, CameraRig.unwrapNear(5f, 0f), 1e-4f);
        assertEquals(720f + 179f, CameraRig.unwrapNear(179f, 720f), 1e-3f);
    }

    @Test
    void bankedTiltedCameraHeadingDiffersFromBodyHeading() {
        // 45° bank, 30° camera tilt: the boom (camera yaw) and vanilla's clip ray (body yaw) diverge
        // by atan(tan(tilt)·sin(roll)) ≈ 22°.
        Quaternionf body = new Quaternionf().rotationZ((float) Math.toRadians(45));
        CameraPose pose = CameraRig.poseFor(body, 30f, 90f, 16f / 9f);
        double diff = Math.abs(CameraRig.unwrapNear(pose.yawDeg(), CameraRig.bodyYawDeg(body)) - CameraRig.bodyYawDeg(body));
        double expected = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(30)) * Math.sin(Math.toRadians(45))));
        assertEquals(expected, diff, 0.05);
        assertEquals(22.2, diff, 0.1);
    }

    @Test
    void boomCorrectionYieldsCameraBoomInBothViews() {
        double dVanilla = 4.0;
        double dCam = 1.5;
        // Back view: vanilla T(0,0,-dV), then the handler's translate.
        org.joml.Matrix4d back = new org.joml.Matrix4d().translate(0, 0, -dVanilla)
                .translate(0, 0, CameraRig.boomCorrectionZ(false, dVanilla, dCam));
        assertEquals(-dCam, back.m32(), 1e-12);
        // Front view: Ry(180)·Rx(-180)·T(0,0,-dV)·Rx(180), then the handler's translate.
        org.joml.Matrix4d front = new org.joml.Matrix4d().rotateY(Math.PI).rotateX(-Math.PI)
                .translate(0, 0, -dVanilla).rotateX(Math.PI)
                .translate(0, 0, CameraRig.boomCorrectionZ(true, dVanilla, dCam));
        org.joml.Matrix4d expected = new org.joml.Matrix4d().rotateY(Math.PI).translate(0, 0, dCam);
        assertTrue(front.equals(expected, 1e-9), front + " vs " + expected);
        // No obstruction difference → no translate.
        assertEquals(0.0, CameraRig.boomCorrectionZ(true, 4.0, 4.0), 0.0);
    }
}
