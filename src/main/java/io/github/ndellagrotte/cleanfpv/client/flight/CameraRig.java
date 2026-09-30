package io.github.ndellagrotte.cleanfpv.client.flight;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * FPV camera geometry (spec §4.3, §6.1; PLAN §5 row 1, §6.2): camera tilt, the camera attitude,
 * its decomposition into Minecraft's camera Euler triple, the diagonal → vertical FOV conversion,
 * and the player yaw/pitch outputs. Pure math, stateless; used for the local drone and, with the
 * interpolated remote quaternion and a fixed 30° tilt, for spectating a remote pilot.
 *
 * <h2>Minecraft's camera convention (verified in {@code EntityRenderer.orientCamera})</h2>
 * After the {@code CameraSetup} event the modelview gets {@code rotate(roll, Z)},
 * {@code rotate(pitch, X)}, {@code rotate(yaw, Y)} where the event's default yaw is the entity yaw
 * <b>+ 180</b>. So the world → eye rotation is {@code R = Rz(roll)·Rx(pitch)·Ry(yaw + 180)} and the
 * camera looks along eye −Z with eye +Y up. A {@link CameraPose} stores the entity-convention yaw;
 * the handler must write {@link #cameraSetupYaw(CameraPose)} ({@code = yawDeg + 180}) to
 * {@code setYaw}, and {@code pitchDeg}/{@code rollDeg} unchanged to {@code setPitch}/{@code setRoll}.
 * With roll 0 the camera forward is vanilla's look vector
 * {@code (−sin yaw·cos pitch, −sin pitch, cos yaw·cos pitch)}.
 *
 * <h2>Decomposition</h2>
 * The camera attitude {@code Q} (forward = {@code Q·+Z}, up = {@code Q·+Y}) relates to {@code R} by
 * {@code Rᵀ = Q·Ry(180°)} (eye −Z ↦ local +Z, eye +X ↦ local −X). Writing {@code M} for the matrix of
 * {@code Q}: {@code pitch = asin(−M₁₂)}, {@code yaw = atan2(−M₀₂, M₂₂)}, {@code roll = atan2(M₁₀, M₁₁)}.
 * When the camera looks straight up or down ({@code cos pitch < 1e−4}) yaw and roll are coupled;
 * the decomposition then returns roll 0 and puts the whole heading into yaw
 * ({@code atan2(M₂₀, M₀₀)}). Every branch reproduces the same rotation, so rendering is exact; only
 * the split between yaw and roll jumps there, which nothing downstream interpolates.
 */
public final class CameraRig {

    /** Fixed tilt used when spectating a remote pilot (spec §6.1, §8.3). */
    public static final float REMOTE_TILT_DEG = 30f;
    /** Knob range of the activate-angle mode: {@code round(lerp(t, 2, 16)) × 5} = 10°..80°. */
    public static final double KNOB_STEPS_MIN = 2.0;
    public static final double KNOB_STEPS_MAX = 16.0;
    public static final double KNOB_STEP_DEG = 5.0;
    /** Below this {@code cos(pitch)} the Euler split is treated as gimbal-locked. */
    public static final double GIMBAL_EPS = 1e-4;

    private CameraRig() {}

    // =============================================================================================
    // Tilt

    /**
     * Effective camera tilt (spec §4.3): with the activate-angle switch on,
     * {@code round(lerp((knob+1)/2, 2, 16)) × 5} (10°..80° in 5° steps); otherwise the fixed
     * {@code switchlessAngle} ("Default Angle").
     */
    public static float tiltDeg(boolean activateAngle, float knob, float switchlessAngle) {
        if (!activateAngle) {
            return Float.isFinite(switchlessAngle) ? switchlessAngle : REMOTE_TILT_DEG;
        }
        double k = Float.isFinite(knob) ? Math.clamp(knob, -1f, 1f) : 0.0;
        double t = (k + 1.0) * 0.5;
        double steps = KNOB_STEPS_MIN + (KNOB_STEPS_MAX - KNOB_STEPS_MIN) * t;
        return (float) (Math.round(steps) * KNOB_STEP_DEG);
    }

    // =============================================================================================
    // Camera attitude and pose

    /**
     * Camera attitude = body rotated by −tilt about the body "right" axis ({@code q·+X}), i.e.
     * {@code body · Rx(−tilt)}: a positive tilt points the camera up relative to the body.
     */
    public static Quaternionf cameraAttitude(Quaternionfc body, float tiltDeg, Quaternionf dest) {
        float tilt = Float.isFinite(tiltDeg) ? tiltDeg : 0f;
        return dest.set(body).rotateX((float) Math.toRadians(-tilt));
    }

    /**
     * The full camera for one frame: tilt the body, decompose, and convert the diagonal FOV to the
     * vertical FOV for the given aspect ratio. Usable for the local drone and for remote pilots.
     *
     * @param body       body attitude
     * @param tiltDeg    camera tilt (degrees, positive = up)
     * @param diagFovDeg diagonal FOV setting (degrees)
     * @param aspect     viewport width / height
     */
    public static CameraPose poseFor(Quaternionfc body, float tiltDeg, float diagFovDeg, float aspect) {
        Quaternionf cam = cameraAttitude(body, tiltDeg, new Quaternionf());
        return poseFromCamera(cam, verticalFovDeg(diagFovDeg, aspect));
    }

    /** Decomposes a camera attitude into Minecraft's camera Euler triple (see class doc). */
    public static CameraPose poseFromCamera(Quaternionfc cam, float vFovDeg) {
        double x = cam.x();
        double y = cam.y();
        double z = cam.z();
        double w = cam.w();
        double n2 = x * x + y * y + z * z + w * w;
        if (!Double.isFinite(n2) || n2 < 1e-12) {
            return new CameraPose(0f, 0f, 0f, vFovDeg);
        }
        double inv = 1.0 / Math.sqrt(n2);
        x *= inv;
        y *= inv;
        z *= inv;
        w *= inv;
        double m00 = 1.0 - 2.0 * (y * y + z * z);
        double m02 = 2.0 * (x * z + w * y);
        double m10 = 2.0 * (x * y + w * z);
        double m11 = 1.0 - 2.0 * (x * x + z * z);
        double m12 = 2.0 * (y * z - w * x);
        double m20 = 2.0 * (x * z - w * y);
        double m22 = 1.0 - 2.0 * (x * x + y * y);

        double sinPitch = Math.clamp(-m12, -1.0, 1.0);
        double pitch = Math.asin(sinPitch);
        double yaw;
        double roll;
        if (Math.cos(pitch) > GIMBAL_EPS) {
            yaw = Math.atan2(-m02, m22);
            roll = Math.atan2(m10, m11);
        } else {
            yaw = Math.atan2(m20, m00);
            roll = 0.0;
        }
        return new CameraPose((float) Math.toDegrees(yaw), (float) Math.toDegrees(pitch),
                (float) Math.toDegrees(roll), vFovDeg);
    }

    /** The yaw to pass to {@code CameraSetup.setYaw}: the pose yaw plus vanilla's 180°. */
    public static float cameraSetupYaw(CameraPose pose) {
        return pose.yawDeg() + 180f;
    }

    /**
     * Rebuilds the camera attitude ({@code Q}, forward = {@code Q·+Z}) from a Minecraft Euler triple:
     * {@code Q = Ry(−yaw)·Rx(pitch)·Rz(roll)}. Inverse of {@link #poseFromCamera}.
     */
    public static Quaternionf cameraFromPose(float yawDeg, float pitchDeg, float rollDeg, Quaternionf dest) {
        return dest.rotationYXZ((float) Math.toRadians(-yawDeg), (float) Math.toRadians(pitchDeg),
                (float) Math.toRadians(rollDeg));
    }

    // =============================================================================================
    // Third-person boom

    /** Vanilla's third-person boom length (blocks); {@code thirdPersonDistancePrev} is reset to it every tick. */
    public static final double THIRD_PERSON_BOOM = 4.0;

    /**
     * Eye-space z translate that turns vanilla's clipped boom {@code dVanilla} (probed along the
     * entity's body yaw) into {@code dCam} (probed along the camera yaw), issued from the
     * {@code CameraSetup} handler, i.e. after vanilla's boom translate and before the event
     * rotations. Back view: vanilla is {@code T(0,0,−dV)}, so {@code +(dV − dCam)}. Front view:
     * vanilla is {@code Ry(180)·T(0,0,+dV)}, so {@code dCam − dV}.
     */
    public static double boomCorrectionZ(boolean frontView, double dVanilla, double dCam) {
        return frontView ? dCam - dVanilla : dVanilla - dCam;
    }

    /** Camera look direction ({@code body · Rx(−tilt) · +Z}) in world space. */
    public static Vector3f cameraForward(Quaternionfc body, float tiltDeg, Vector3f dest) {
        return cameraAttitude(body, tiltDeg, new Quaternionf()).transform(0f, 0f, 1f, dest);
    }

    // =============================================================================================
    // FOV

    /**
     * Vertical FOV from the diagonal FOV setting (spec §6.1):
     * {@code 2·atan(tan(diag/2) · h/√(w²+h²))} with {@code aspect = w/h}. 135° at 16:9 → ≈ 99.6°.
     * The diagonal is clamped to [1°, 179°]; a non-finite or non-positive aspect counts as 1.
     */
    public static float verticalFovDeg(float diagFovDeg, float aspect) {
        double diag = Float.isFinite(diagFovDeg) ? Math.clamp(diagFovDeg, 1.0, 179.0) : 135.0;
        double a = Float.isFinite(aspect) && aspect > 0f ? aspect : 1.0;
        double half = Math.toRadians(diag) * 0.5;
        double v = 2.0 * Math.atan(Math.tan(half) / Math.sqrt(1.0 + a * a));
        return (float) Math.toDegrees(v);
    }

    // =============================================================================================
    // Player rotation outputs (PLAN §5 row 1: yaw = body yaw, pitch = camera pitch)

    /**
     * Heading of the body in Minecraft yaw degrees (−180, 180]. Uses the horizontal part of
     * {@code forward − forward_y·up}, which equals the forward heading in level flight and stays
     * well defined when the nose points straight up or down (the belly/back then gives the
     * heading). Falls back to forward, then up, in the remaining degenerate attitudes. No heading
     * function can be continuous over all attitudes; this one only jumps in inverted, steeply
     * pitched attitudes.
     */
    public static float bodyYawDeg(Quaternionfc body) {
        Vector3f f = body.transform(0f, 0f, 1f, new Vector3f());
        Vector3f u = body.transform(0f, 1f, 0f, new Vector3f());
        double hx = f.x - f.y * u.x;
        double hz = f.z - f.y * u.z;
        if (hx * hx + hz * hz < 1e-8) {
            hx = f.x;
            hz = f.z;
            if (hx * hx + hz * hz < 1e-8) {
                hx = u.x;
                hz = u.z;
            }
        }
        return (float) Math.toDegrees(Math.atan2(-hx, hz));
    }

    /**
     * Camera pitch for the player's {@code rotationPitch} (positive = down, within [−90, 90]):
     * {@code asin(−cameraForward.y)}; equals {@link CameraPose#pitchDeg()} of {@link #poseFor}.
     */
    public static float cameraPitchDeg(Quaternionfc body, float tiltDeg) {
        Vector3f f = cameraForward(body, tiltDeg, new Vector3f());
        return (float) Math.toDegrees(Math.asin(Math.clamp(-f.y, -1.0, 1.0)));
    }

    /**
     * Unwraps {@code angleDeg} to the representation nearest {@code referenceDeg} (differs by a
     * multiple of 360°), so writing it to {@code rotationYaw}/{@code prevRotationYaw} never makes
     * vanilla interpolate the long way round (spec §4.3 "wrapped to the nearest ±180° window").
     */
    public static float unwrapNear(float angleDeg, float referenceDeg) {
        if (!Float.isFinite(angleDeg)) {
            return referenceDeg;
        }
        if (!Float.isFinite(referenceDeg)) {
            return angleDeg;
        }
        double d = angleDeg - (double) referenceDeg;
        d -= 360.0 * Math.floor((d + 180.0) / 360.0);
        return (float) (referenceDeg + d);
    }
}
