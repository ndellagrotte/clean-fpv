package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.common.config.RateTriple;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/**
 * Kinematic body attitude (spec §4.3, PLAN §6.2): the drone's orientation is rotated directly by
 * the rate-curve commands, once per rendered frame. Pure math, stateless.
 *
 * <h2>Convention</h2>
 * Same as {@code ClientDroneContext.attitude()}: the quaternion maps body-local axes to world, body
 * forward = {@code q·(0,0,1)}, body up = {@code q·(0,1,0)}, and the spec's "right" =
 * {@code up × forward} = {@code q·(1,0,0)}. In Minecraft's right-handed world that vector points to
 * the pilot's <em>left</em> on screen; the spec's rotation signs are written against it, so the
 * signs below produce the conventional FPV response:
 * <ul>
 *   <li>yaw stick + (right) → nose turns right: rotation of {@code −yaw} about body up</li>
 *   <li>pitch stick + (forward) → nose down: rotation of {@code +pitch} about body "right"</li>
 *   <li>roll stick + (right) → roll right: rotation of {@code +roll} about body forward</li>
 * </ul>
 *
 * <h2>Frame step</h2>
 * Per frame: angle = rate(stick)·dt (+ mouse radians for pitch/roll, a displacement that is
 * <em>not</em> scaled by dt), applied as three successive body-axis rotations in the spec's order
 * yaw → pitch → roll (each about the axis as moved by the previous one), then renormalised. The
 * throttle mapping (normal / 3D mode, spec §4.4) is {@code PhysicsConfig.mapThrottle}, not here.
 */
public final class Attitude {

    private Attitude() {}

    /**
     * Arming: body attitude such that the <em>camera</em> (body tilted up by {@code tiltDeg}) looks
     * exactly where the player looks. Spec §4.1 {@code fromAngles(pitch + tilt, −yaw, 0)}, i.e.
     * {@code Ry(−yaw)·Rx(pitch + tilt)} in this convention; roll starts at 0.
     *
     * @param yawDeg   player {@code rotationYaw} (Minecraft degrees)
     * @param pitchDeg player {@code rotationPitch} (positive = looking down)
     * @param tiltDeg  effective camera tilt (positive = camera tilted up relative to the body)
     * @param dest     receives the body attitude
     * @return {@code dest}
     */
    public static Quaternionf initFromLook(float yawDeg, float pitchDeg, float tiltDeg, Quaternionf dest) {
        double yaw = finiteOr0(yawDeg);
        double pitch = finiteOr0(pitchDeg) + finiteOr0(tiltDeg);
        return dest.rotationYXZ((float) Math.toRadians(-yaw), (float) Math.toRadians(pitch), 0f);
    }

    /**
     * Applies one frame's body-axis rotations in spec order and renormalises {@code q}.
     *
     * @param q        body attitude, mutated in place
     * @param yawRad   yaw angle this frame, positive = nose right
     * @param pitchRad pitch angle this frame, positive = nose down
     * @param rollRad  roll angle this frame, positive = roll right
     * @return {@code q}
     */
    public static Quaternionf rotateBody(Quaternionf q, double yawRad, double pitchRad, double rollRad) {
        // Post-multiplication = rotation about the current body-local axis.
        if (yawRad != 0.0 && Double.isFinite(yawRad)) {
            q.rotateY((float) -yawRad);
        }
        if (pitchRad != 0.0 && Double.isFinite(pitchRad)) {
            q.rotateX((float) pitchRad);
        }
        if (rollRad != 0.0 && Double.isFinite(rollRad)) {
            q.rotateZ((float) rollRad);
        }
        return renormalize(q);
    }

    /**
     * The whole per-frame attitude step: rate curves × dt plus the mouse displacement.
     *
     * @param q              body attitude, mutated in place
     * @param yawStick       yaw stick [−1, 1], positive = right
     * @param pitchStick     pitch stick [−1, 1], positive = forward (nose down)
     * @param rollStick      roll stick [−1, 1], positive = right
     * @param yawRates       yaw rate triple
     * @param pitchRates     pitch rate triple
     * @param rollRates      roll rate triple
     * @param dt             frame time (s); non-finite or negative → 0
     * @param mousePitchUpRad mouse pitch this frame in radians, <b>positive = nose up</b> (the sign of
     *                       vanilla's {@code MouseTurnEvent} pitch; negated internally)
     * @param mouseRollRad   mouse roll this frame in radians, positive = roll right (event yaw sign)
     * @return {@code q}
     */
    public static Quaternionf frameStep(Quaternionf q, float yawStick, float pitchStick, float rollStick,
                                        RateTriple yawRates, RateTriple pitchRates, RateTriple rollRates,
                                        double dt, double mousePitchUpRad, double mouseRollRad) {
        double t = Double.isFinite(dt) && dt > 0.0 ? dt : 0.0;
        double yaw = Rates.rateRadPerSec(yawStick, yawRates) * t;
        double pitch = Rates.rateRadPerSec(pitchStick, pitchRates) * t - finiteOr0(mousePitchUpRad);
        double roll = Rates.rateRadPerSec(rollStick, rollRates) * t + finiteOr0(mouseRollRad);
        return rotateBody(q, yaw, pitch, roll);
    }

    /**
     * Normalises {@code q}; a degenerate (zero / non-finite) quaternion is reset to identity so a
     * numerical accident can never poison the camera or the physics.
     */
    public static Quaternionf renormalize(Quaternionf q) {
        float len2 = q.lengthSquared();
        if (!Float.isFinite(len2) || len2 < 1e-12f) {
            return q.identity();
        }
        return q.normalize();
    }

    /** Body forward {@code q·(0,0,1)} in world space. */
    public static Vector3f forward(Quaternionfc q, Vector3f dest) {
        return q.transform(0f, 0f, 1f, dest);
    }

    /** Body up {@code q·(0,1,0)} in world space. */
    public static Vector3f up(Quaternionfc q, Vector3f dest) {
        return q.transform(0f, 1f, 0f, dest);
    }

    /** The spec's body "right" {@code up × forward = q·(1,0,0)} in world space (screen-left). */
    public static Vector3f right(Quaternionfc q, Vector3f dest) {
        return q.transform(1f, 0f, 0f, dest);
    }

    private static double finiteOr0(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}
