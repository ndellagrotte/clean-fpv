package io.github.ndellagrotte.cleanfpv.client.physics;

import org.joml.Vector3d;

/**
 * Wall-collision response (spec §5.6), applied after a {@link Sweep} that clipped the attempted
 * displacement.
 *
 * <ol>
 *   <li>{@code blocked = attempted − actual}; the impact angle {@code θ = angle(attempted, blocked)}
 *       is 0 for a head-on hit and 90° for a glancing one. Degenerate (NaN) → stop.</li>
 *   <li>Restitution applies to the <em>whole</em> speed:
 *       {@code e = sin θ·0.65 + (1 − sin θ)·0.2} (head-on keeps 0.2, glancing 0.65).</li>
 *   <li>If {@code e·|v| < 1 m/s} the drone stops at the collided position.</li>
 *   <li>Otherwise the velocity is mirrored on every clipped axis (a box-world wall or corner
 *       normal) and rescaled to {@code e·|v|}, and the blocked remainder of the displacement is
 *       replayed mirrored (the spec's {@code reflected = 2·actual − attempted}), scaled by {@code e}
 *       and swept again so the rebound itself can never enter a block.</li>
 * </ol>
 * Pure, stateless.
 */
public final class Bounce {

    public static final double HEAD_ON_RESTITUTION = 0.2;
    public static final double GLANCING_RESTITUTION = 0.65;
    /** Post-bounce speeds below this stop the drone completely (m/s). */
    public static final double STOP_SPEED = 1.0;

    private Bounce() {}

    /** Restitution for an impact angle (radians; 0 = head-on, π/2 = glancing). */
    public static double restitution(double thetaRad) {
        double s = Math.abs(Math.sin(thetaRad));
        return s * GLANCING_RESTITUTION + (1.0 - s) * HEAD_ON_RESTITUTION;
    }

    /** Angle between two vectors in radians, or NaN if either is (near) zero or non-finite. */
    public static double angleBetween(double ax, double ay, double az, double bx, double by, double bz) {
        double la = Math.sqrt(ax * ax + ay * ay + az * az);
        double lb = Math.sqrt(bx * bx + by * by + bz * bz);
        if (!(la > 1e-12) || !(lb > 1e-12) || !Double.isFinite(la) || !Double.isFinite(lb)) {
            return Double.NaN;
        }
        double c = (ax * bx + ay * by + az * bz) / (la * lb);
        return Math.acos(Math.clamp(c, -1.0, 1.0));
    }

    /**
     * Resolves a collision.
     *
     * @param world     collision source for the rebound sweep
     * @param hit       the clipped sweep of the attempted displacement
     * @param ax        attempted X displacement
     * @param ay        attempted Y displacement
     * @param az        attempted Z displacement
     * @param velocity  drone velocity, updated in place
     * @return the final sweep result (position = its box); collision flags are those of {@code hit}
     */
    public static Sweep.Result respond(CollisionWorld world, Sweep.Result hit, double ax, double ay, double az,
                                       Vector3d velocity) {
        double bx = ax - hit.dx();
        double by = ay - hit.dy();
        double bz = az - hit.dz();
        double theta = angleBetween(ax, ay, az, bx, by, bz);
        double speed = velocity.length();
        if (Double.isNaN(theta) || !Double.isFinite(speed)) {
            velocity.zero();
            return hit;
        }
        double e = restitution(theta);
        double newSpeed = speed * e;
        if (newSpeed < STOP_SPEED) {
            velocity.zero();
            return hit;
        }
        double vx = hit.collidedX() ? -velocity.x : velocity.x;
        double vy = hit.collidedY() ? -velocity.y : velocity.y;
        double vz = hit.collidedZ() ? -velocity.z : velocity.z;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (!(len > 1e-12)) {
            velocity.zero();
            return hit;
        }
        velocity.set(vx, vy, vz).mul(newSpeed / len);

        Sweep.Result rebound = Sweep.move(world, hit.box(), -bx * e, -by * e, -bz * e);
        if (rebound.collidedX()) {
            velocity.x = 0.0;
        }
        if (rebound.collidedY()) {
            velocity.y = 0.0;
        }
        if (rebound.collidedZ()) {
            velocity.z = 0.0;
        }
        return new Sweep.Result(rebound.box(), hit.dx() + rebound.dx(), hit.dy() + rebound.dy(),
                hit.dz() + rebound.dz(), hit.collidedX(), hit.collidedY(), hit.collidedZ());
    }
}
