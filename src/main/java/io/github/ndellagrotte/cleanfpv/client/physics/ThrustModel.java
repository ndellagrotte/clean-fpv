package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/**
 * Propulsion model: the swappable part of the physics step (PLAN §6.3). v1 = first-order-lag
 * {@code T = k·ω²}; v2 = blade element + motor RK4 + battery sag + thermal. Selected by
 * {@link PhysicsConfig#highFidelity()}. The translation integrator (gravity, body drag, speed
 * clamp, sweep, bounce) is shared and not part of this interface.
 *
 * <p>Forces are returned in the <b>world</b> frame: the blade-element force depends on the world
 * airflow and is not purely along body-up, so a body-frame "thrust" scalar would lose information.
 * v1 simply returns {@code bodyUp · ΣT}.
 *
 * <p>Implementations are pure (no Minecraft types), single-threaded, and may cache per-build
 * precomputation between {@link #prepare} calls.
 */
public interface ThrustModel {

    /** Per-tick precompute from the build (motor inertia, heat capacity, winding resistance, k...). */
    void prepare(DroneBuild build);

    /**
     * Advances one substep: updates {@code state.omega}, {@code heat}, {@code temperature},
     * {@code throttle} and {@code cellVoltage}, and <em>adds</em> the total propeller force (world
     * frame, N) to {@code forceOut}.
     *
     * @param state     drone state (velocity is read, motor fields are written)
     * @param attitude  body attitude (body forward = +Z, up = +Y, right = up × forward)
     * @param throttle  mode-mapped throttle: [0,1] normally, [−1,1] in 3D mode (spec §5.2)
     * @param dt        substep length (s), ≤ 1/128
     * @param forceOut  accumulator for the world-frame prop force (N)
     * @param torqueOut optional per-motor aerodynamic reaction torque about body-up (N·m), length 4;
     *                  may be {@code null}. Informational only: attitude is kinematic (spec §2.3)
     */
    void step(DroneState state, Quaternionfc attitude, double throttle, double dt, Vector3d forceOut,
              double[] torqueOut);

    /** No-load ω clamp for the prepared build (rad/s). */
    double noLoadOmega();

    /** Live overheat warning for the HUD (PLAN §10: driven by real temperatures). */
    default boolean overheating(DroneState state) {
        return false;
    }
}
