package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Vector3d;

/**
 * The physics facade orchestration calls (PLAN §6.3, §7). One instance per client; single-threaded
 * (client thread). Owns both {@link ThrustModel} implementations and picks one per step from
 * {@link PhysicsConfig#highFidelity()}; re-prepares them when the config changes.
 *
 * <h2>How to drive it</h2>
 * <ul>
 *   <li><b>Arm:</b> {@link #onArm} with the player's feet position (keeps the tracked velocity so the
 *       drone inherits momentum, spec §5.1).</li>
 *   <li><b>Disarmed:</b> {@link #trackDisarmed} once per client tick with the player's position and
 *       {@link #TICK_DT}.</li>
 *   <li><b>Tick mode</b> ({@code PlayerTickEvent} START, local player): {@link #stepTick} with
 *       {@code dt = TICK_DT}; it records the tick start in {@link DroneState#lastPosition} and applies
 *       the tick-consistency rule itself.</li>
 *   <li><b>Realtime mode:</b> {@link #stepRealtime} per frame ({@code RenderTickEvent} START, frame dt)
 *       plus {@link #markTickBoundary} once per client tick ({@code PlayerTickEvent} START, before the
 *       position is sent).</li>
 *   <li>After a step, write {@link DroneState#position} to the player with {@code setPosition} and
 *       zero its motion; the hitbox here ({@link #hitbox}) is exactly what {@code setPosition} builds
 *       for a 0.2 × 0.1 player.</li>
 * </ul>
 * Pausing while a GUI is open and the skip-first-step flag are the caller's (they are frame/tick
 * sequencing, not physics).
 */
public final class PhysicsEngine {

    /** Drone hitbox width (blocks); equals {@code PlayerSizing.DRONE_WIDTH}. */
    public static final float HITBOX_WIDTH = 0.2f;
    /** Drone hitbox height (blocks); equals {@code PlayerSizing.DRONE_HEIGHT}. */
    public static final float HITBOX_HEIGHT = 0.1f;
    /** Tick-mode step (s). */
    public static final double TICK_DT = 0.05;
    /**
     * Horizontal tick-consistency threshold (blocks): below the server's 0.25 ("moved wrongly" at
     * error² > 0.0625) with margin.
     */
    public static final double SNAP_DISTANCE = 0.2;
    /** A position mismatch larger than this (blocks) counts as an external teleport in {@link #syncPosition}. */
    public static final double TELEPORT_EPSILON = 1e-4;

    /**
     * What one step did.
     *
     * @param substeps number of substeps run (0 = nothing happened)
     * @param collided some substep clipped against the world
     * @param snapped  the tick-consistency rule moved the drone
     */
    public record Result(int substeps, boolean collided, boolean snapped) {
        public static final Result NONE = new Result(0, false, false);
    }

    private final ThrustV1 v1 = new ThrustV1();
    private final ThrustV2 v2 = new ThrustV2();
    private final Stepper stepper = new Stepper();
    private final Vector3d tickStart = new Vector3d();
    private PhysicsConfig prepared;
    private double massKg;
    private double dragFactor;

    /** The feet-centred drone box at a position, built exactly like {@code Entity.setPosition}. */
    public static AxisAlignedBB hitbox(double x, double y, double z) {
        float half = HITBOX_WIDTH / 2.0F;
        float height = HITBOX_HEIGHT;
        return new AxisAlignedBB(x - half, y, z - half, x + half, y + height, z + half);
    }

    // =============================================================================================
    // Stepping

    /**
     * Tick-mode step: {@code in.dt()} (normally {@link #TICK_DT}; non-positive → {@code TICK_DT}) in
     * ≤ 1/128 s substeps, then the tick-consistency rule from the tick start.
     */
    public Result stepTick(DroneState state, StepInput in, PhysicsConfig config, CollisionWorld world) {
        double dt = in.dt() > 0.0 && Double.isFinite(in.dt()) ? in.dt() : TICK_DT;
        tickStart.set(state.position);
        state.lastPosition.set(state.position);
        CollisionWorld cache = new CollisionCache(world);
        Result r = run(state, in, config, cache, dt);
        boolean snapped = !in.noClip()
                && Stepper.enforceTickConsistency(state, tickStart, cache, SNAP_DISTANCE);
        return new Result(r.substeps(), r.collided(), snapped);
    }

    /** Realtime-mode step of {@code in.dt()} seconds (the frame time); no consistency check. */
    public Result stepRealtime(DroneState state, StepInput in, PhysicsConfig config, CollisionWorld world) {
        double dt = in.dt();
        if (!(dt > 0.0) || !Double.isFinite(dt)) {
            return Result.NONE;
        }
        return run(state, in, config, new CollisionCache(world), dt);
    }

    /**
     * Realtime mode, once per client tick before the position is sent: applies the tick-consistency
     * rule from the previous boundary ({@link DroneState#lastPosition}) to the current position,
     * then starts a new tick there.
     *
     * @return whether the position was snapped
     */
    public boolean markTickBoundary(DroneState state, CollisionWorld world, boolean noClip) {
        boolean snapped = !noClip
                && Stepper.enforceTickConsistency(state, state.lastPosition, world, SNAP_DISTANCE);
        state.lastPosition.set(state.position);
        return snapped;
    }

    private Result run(DroneState state, StepInput in, PhysicsConfig config, CollisionWorld world, double dt) {
        ThrustModel model = model(config);
        state.massKg = massKg;
        double throttle = config.mapThrottle(in.throttleStick());
        int n = Stepper.substepCount(dt);
        boolean collided = stepper.run(state, in.attitude(), throttle, dt, model, massKg, dragFactor,
                config.maxSpeed(), world, in.noClip());
        state.collided = collided;
        return new Result(n, collided, false);
    }

    // =============================================================================================
    // Arm / disarm helpers

    /**
     * Arming: the drone starts at the player's feet position with the velocity tracked while
     * disarmed; motors reset (spinning up from rest), collision flag cleared.
     */
    public void onArm(DroneState state, double x, double y, double z) {
        state.position.set(x, y, z);
        state.lastPosition.set(x, y, z);
        state.resetMotors();
        state.collided = false;
        state.lastStepNanos = 0L;
        if (!state.velocity.isFinite()) {
            state.velocity.zero();
        }
    }

    /**
     * While disarmed (spec §5.1): {@code velocity = (pos − lastPos) / dt}, clamped to the absolute
     * speed cap, and the position is recorded, so arming inherits the player's current motion.
     */
    public static void trackDisarmed(DroneState state, double x, double y, double z, double dt) {
        if (dt > 0.0 && Double.isFinite(dt)) {
            state.velocity.set(x - state.lastPosition.x, y - state.lastPosition.y, z - state.lastPosition.z)
                    .div(dt);
            Stepper.clampSpeed(state.velocity, PhysicsConfig.ABSOLUTE_MAX_SPEED);
        }
        state.position.set(x, y, z);
        state.lastPosition.set(x, y, z);
    }

    /**
     * Adopts an externally changed player position (server teleport/correction, respawn) before a
     * step: when the player is more than {@link #TELEPORT_EPSILON} away from the simulated position,
     * both {@code position} and {@code lastPosition} jump there.
     *
     * @return whether the position was adopted
     */
    public static boolean syncPosition(DroneState state, double x, double y, double z) {
        double dx = x - state.position.x;
        double dy = y - state.position.y;
        double dz = z - state.position.z;
        if (dx * dx + dy * dy + dz * dz <= TELEPORT_EPSILON * TELEPORT_EPSILON) {
            return false;
        }
        state.position.set(x, y, z);
        state.lastPosition.set(x, y, z);
        return true;
    }

    // =============================================================================================
    // Models

    /** The (prepared) propulsion model the config selects. */
    public ThrustModel model(PhysicsConfig config) {
        if (!config.equals(prepared)) {
            v1.prepare(config.build());
            v2.prepare(config.build());
            massKg = MassModel.flownMassKg(config.build());
            dragFactor = DragModel.dragFactor(massKg);
            prepared = config;
        }
        return config.highFidelity() ? v2 : v1;
    }

    /** Live overheat warning for the HUD (always false for v1). */
    public boolean overheating(DroneState state, PhysicsConfig config) {
        return model(config).overheating(state);
    }

    /** Flown mass (kg) of the prepared config. */
    public double massKg(PhysicsConfig config) {
        model(config);
        return massKg;
    }
}
