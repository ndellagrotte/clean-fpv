package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * The shared translation integrator (spec §5.1–5.2, §5.6; PLAN §6.3). Tick mode and realtime mode
 * both call {@link #run}, which splits the step into equal substeps of at most 1/128 s and runs
 * {@link #substep} on each:
 * <ol>
 *   <li>force = gravity + quadratic body drag ({@link DragModel}) + propulsion
 *       ({@link ThrustModel#step}, which also advances the motors)</li>
 *   <li>{@code v += F/m · h}; speed clamped to {@code maxSpeed}; non-finite → 0</li>
 *   <li>displacement {@code v·h} swept Y → X → Z ({@link Sweep}); on a clip, {@link Bounce}.
 *       No-clip moves straight through.</li>
 * </ol>
 * Also hosts the tick-consistency rule ({@link #enforceTickConsistency}). Holds scratch vectors
 * only; single-threaded.
 */
public final class Stepper {

    public static final double GRAVITY = 9.80665;
    /** Largest substep (s). */
    public static final double MAX_SUBSTEP = 1.0 / 128.0;
    /** Substep count cap (a 2 s step at 1/128 s). */
    public static final int MAX_SUBSTEPS = 256;

    private final Vector3d force = new Vector3d();

    /** Number of equal substeps for a step of {@code dt} seconds (0 for a non-positive dt). */
    public static int substepCount(double dt) {
        if (!(dt > 0.0) || !Double.isFinite(dt)) {
            return 0;
        }
        return (int) Math.min(MAX_SUBSTEPS, Math.max(1, Math.ceil(dt / MAX_SUBSTEP - 1e-9)));
    }

    /**
     * Runs a whole step of {@link #substepCount(double) substepCount(dt)} equal substeps.
     *
     * @return whether any substep collided
     */
    public boolean run(DroneState s, Quaternionfc attitude, double throttle, double dt, ThrustModel model,
                   double massKg, double dragFactor, double maxSpeed, CollisionWorld world, boolean noClip) {
        int n = substepCount(dt);
        if (n == 0) {
            return false;
        }
        double h = Math.min(dt, MAX_SUBSTEP * MAX_SUBSTEPS) / n;
        boolean collided = false;
        for (int i = 0; i < n; i++) {
            collided |= substep(s, attitude, throttle, h, model, massKg, dragFactor, maxSpeed, world, noClip);
        }
        return collided;
    }

    /** One substep; returns whether the sweep clipped. */
    public boolean substep(DroneState s, Quaternionfc attitude, double throttle, double h, ThrustModel model,
                           double massKg, double dragFactor, double maxSpeed, CollisionWorld world, boolean noClip) {
        Vector3d v = s.velocity;
        force.set(0.0, -GRAVITY * massKg, 0.0);
        DragModel.addDrag(v, dragFactor, force);
        model.step(s, attitude, throttle, h, force, null);
        if (massKg > 0.0 && force.isFinite()) {
            v.fma(h / massKg, force);
        }
        clampSpeed(v, maxSpeed);

        double ax = v.x * h;
        double ay = v.y * h;
        double az = v.z * h;
        Vector3d p = s.position;
        if (noClip) {
            p.add(ax, ay, az);
            return false;
        }
        Sweep.Result hit = Sweep.move(world, PhysicsEngine.hitbox(p.x, p.y, p.z), ax, ay, az);
        Sweep.Result end = hit.collided() ? Bounce.respond(world, hit, ax, ay, az, v) : hit;
        p.set(end.posX(), end.posY(), end.posZ());
        return hit.collided();
    }

    /** Clamps the speed to {@code maxSpeed} (and to the absolute cap); non-finite → zero. */
    public static void clampSpeed(Vector3d v, double maxSpeed) {
        if (!v.isFinite()) {
            v.zero();
            return;
        }
        double cap = Double.isFinite(maxSpeed) && maxSpeed > 0.0
                ? Math.min(maxSpeed, PhysicsConfig.ABSOLUTE_MAX_SPEED) : PhysicsConfig.ABSOLUTE_MAX_SPEED;
        double len = v.length();
        if (len > cap) {
            v.mul(cap / len);
        }
    }

    /**
     * Tick-consistency rule (PLAN §6.3): the server replays {@code lastGood → claimed} as one
     * straight {@code player.move} and flags "moved wrongly" when the horizontal error exceeds 0.25
     * blocks. Substep bounces can round corners that the straight replay cannot, so sweep the straight
     * line {@code tickStart → position}; if its horizontal end differs from {@code position} by more
     * than {@code threshold}, snap to the swept end and zero the velocity on every axis the straight
     * sweep clipped.
     *
     * @return whether the position was snapped
     */
    public static boolean enforceTickConsistency(DroneState s, Vector3dc tickStart, CollisionWorld world,
                                                 double threshold) {
        Vector3d p = s.position;
        AxisAlignedBB start = PhysicsEngine.hitbox(tickStart.x(), tickStart.y(), tickStart.z());
        Sweep.Result straight = Sweep.move(world, start, p.x - tickStart.x(), p.y - tickStart.y(),
                p.z - tickStart.z());
        double ex = straight.posX() - p.x;
        double ez = straight.posZ() - p.z;
        if (ex * ex + ez * ez <= threshold * threshold) {
            return false;
        }
        p.set(straight.posX(), straight.posY(), straight.posZ());
        if (straight.collidedX()) {
            s.velocity.x = 0.0;
        }
        if (straight.collidedY()) {
            s.velocity.y = 0.0;
        }
        if (straight.collidedZ()) {
            s.velocity.z = 0.0;
        }
        return true;
    }
}
