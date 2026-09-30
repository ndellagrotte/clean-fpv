package io.github.ndellagrotte.cleanfpv.server.net;

import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;

/**
 * Pure rules applied to owner transforms (PLAN §6.6): the server's validation predicate and the
 * roll derived from a drone attitude. No Minecraft imports, so both are unit-tested, and the client
 * uses {@link #cameraRollDeg} for remote players' entity roll with the same convention.
 *
 * <h2>Roll convention</h2>
 * Attitude quaternions map body-local axes to world axes: body forward = {@code q·(0,0,1)}, body up
 * = {@code q·(0,1,0)} (see {@code ClientDroneContext}). The camera is the body tilted <em>up</em>
 * by the camera angle {@code t} (spec §4.3, rotation by −t about the spec's {@code right = up ×
 * forward}): {@code camF = cos t·f + sin t·u}, {@code camU = cos t·u − sin t·f}.
 *
 * <p>From {@code camF}: Minecraft yaw {@code θ = atan2(−F.x, F.z)} and pitch {@code φ = asin(−F.y)}
 * (look vector {@code (−sinθ cosφ, −sinφ, cosθ cosφ)}). The level ("unrolled") camera up for that
 * look is {@code U0 = −∂look/∂φ = (−sinθ sinφ, cosφ, cosθ sinφ)} and the pilot's right is
 * {@code R0 = camF × U0}. The actual up is {@code camU = cos ρ·U0 + sin ρ·R0}, so
 * {@code ρ = atan2(camU·R0, camU·U0)}: <b>positive = rolled right</b> (right side down).
 *
 * <p>That is exactly the value {@code EntityViewRenderEvent.CameraSetup.setRoll} expects:
 * {@code orientCamera} applies {@code Rz(roll)} last, in eye space (x right, y up, looking −z), so a
 * positive roll turns the world counter-clockwise on screen, which is what a camera rolled to the
 * right sees. It is the same triple as {@code CameraPose} ({@code Rz(roll)·Rx(pitch)·Ry(yaw+180)}),
 * so the server-synced {@code Entity.ROLL} of an armed player matches the roll of that pilot's own
 * view. When the camera looks straight up or down, yaw and roll are degenerate and roll is 0.
 */
public final class TransformRules {

    /** ω may exceed the build's no-load speed by this factor (float noise, voltage headroom). */
    public static final double OMEGA_TOLERANCE = 1.05;
    /** Absolute ω slack (rad/s), so a 0 Kv build does not reject numerically-zero motor speeds. */
    public static final double OMEGA_SLACK = 1.0;
    /** Relative slack on the speed cap: the client clamps to exactly {@code maxSpeed} in doubles. */
    public static final double SPEED_TOLERANCE = 1.01;

    private TransformRules() {}

    /** Why a transform was rejected; {@link #OK} when accepted. */
    public enum Verdict {
        OK,
        /** The sender is not armed on the server (e.g. packets in flight after a forced disarm). */
        NOT_ARMED,
        /** No build received since the player connected: the client must send Build at arm. */
        NO_BUILD,
        /** NaN / infinity anywhere, or a zero-length quaternion. */
        NON_FINITE,
        /** |v| above the server's speed cap. */
        TOO_FAST,
        /** A motor spins faster than the build physically can. */
        OMEGA_TOO_HIGH;

        public boolean accepted() {
            return this == OK;
        }

        /** Worth a (rate-limited) log line: everything except expected races around disarm. */
        public boolean suspicious() {
            return this != OK && this != NOT_ARMED;
        }
    }

    /**
     * The server's transform check (PLAN §6.6): registry armed, all values finite, {@code |v| ≤
     * maxSpeed} and {@code max|ω| ≤ 1.05 × noLoadOmega} of the stored build.
     *
     * @param armed    whether the sender is armed in {@code ArmRegistry.SERVER}
     * @param build    the sender's stored build, or {@code null}
     * @param t        the received transform (never {@code null})
     * @param maxSpeed the server speed cap (m/s)
     */
    public static Verdict validate(boolean armed, DroneBuild build, TransformSnapshot t, double maxSpeed) {
        if (!armed) {
            return Verdict.NOT_ARMED;
        }
        if (!t.isFinite()) {
            return Verdict.NON_FINITE;
        }
        if (t.speed() > maxSpeed * SPEED_TOLERANCE) {
            return Verdict.TOO_FAST;
        }
        if (build == null) {
            return Verdict.NO_BUILD;
        }
        if (t.maxAbsOmega() > omegaLimit(build)) {
            return Verdict.OMEGA_TOO_HIGH;
        }
        return Verdict.OK;
    }

    /** Highest accepted |ω| (rad/s) for {@code build}. */
    public static double omegaLimit(DroneBuild build) {
        return OMEGA_TOLERANCE * build.noLoadOmega() + OMEGA_SLACK;
    }

    /**
     * Minecraft roll (degrees, {@code (−180, 180]}, positive = rolled right) of the camera of a drone
     * with body attitude {@code (qx,qy,qz,qw)} and camera tilt {@code tiltDeg}; see the class doc.
     * The quaternion need not be normalised; a degenerate one gives 0.
     */
    public static float cameraRollDeg(float qx, float qy, float qz, float qw, float tiltDeg) {
        double n = Math.sqrt((double) qx * qx + (double) qy * qy + (double) qz * qz + (double) qw * qw);
        if (!(n > 1e-9) || !Float.isFinite(tiltDeg)) {
            return 0f;
        }
        double x = qx / n;
        double y = qy / n;
        double z = qz / n;
        double w = qw / n;
        // Rotation-matrix columns: body up = q·(0,1,0), body forward = q·(0,0,1).
        double ux = 2 * (x * y - w * z);
        double uy = 1 - 2 * (x * x + z * z);
        double uz = 2 * (y * z + w * x);
        double fx = 2 * (x * z + w * y);
        double fy = 2 * (y * z - w * x);
        double fz = 1 - 2 * (x * x + y * y);

        double t = Math.toRadians(tiltDeg);
        double ct = Math.cos(t);
        double st = Math.sin(t);
        double cfx = ct * fx + st * ux;
        double cfy = ct * fy + st * uy;
        double cfz = ct * fz + st * uz;
        double cux = ct * ux - st * fx;
        double cuy = ct * uy - st * fy;
        double cuz = ct * uz - st * fz;
        return (float) rollDeg(cfx, cfy, cfz, cux, cuy, cuz);
    }

    /** Roll (degrees) of an orthonormal camera frame given its forward and up vectors (world). */
    static double rollDeg(double fx, double fy, double fz, double ux, double uy, double uz) {
        double horizontal = Math.sqrt(fx * fx + fz * fz);
        if (horizontal < 1e-6) {
            return 0.0;
        }
        double yaw = Math.atan2(-fx, fz);
        double pitch = Math.asin(Math.clamp(-fy, -1.0, 1.0));
        double sy = Math.sin(yaw);
        double cy = Math.cos(yaw);
        double sp = Math.sin(pitch);
        double cp = Math.cos(pitch);
        // Level up vector for this look direction.
        double u0x = -sy * sp;
        double u0y = cp;
        double u0z = cy * sp;
        // Pilot's right = forward × level up.
        double r0x = fy * u0z - fz * u0y;
        double r0y = fz * u0x - fx * u0z;
        double r0z = fx * u0y - fy * u0x;
        double sin = ux * r0x + uy * r0y + uz * r0z;
        double cos = ux * u0x + uy * u0y + uz * u0z;
        return Math.toDegrees(Math.atan2(sin, cos));
    }
}
