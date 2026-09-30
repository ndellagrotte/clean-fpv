package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;

/**
 * Settings the physics needs that change rarely (on arm, settings save, Hello). Immutable.
 *
 * @param build        the build to simulate (callers pass a copy they will not mutate)
 * @param highFidelity physics v2 ({@link ThrustModel} swap)
 * @param realtime     step per rendered frame instead of per tick
 * @param mode3d       signed throttle (spec §4.4)
 * @param maxSpeed     speed clamp (m/s): {@code min(500, server Hello maxSpeed)}
 */
public record PhysicsConfig(DroneBuild build, boolean highFidelity, boolean realtime, boolean mode3d,
                            double maxSpeed) {

    /** Hard speed clamp from spec §5.2. */
    public static final double ABSOLUTE_MAX_SPEED = 500.0;

    /** Builds a config from a model (the build is copied) and the server's speed cap. */
    public static PhysicsConfig from(DroneModelConfig model, double serverMaxSpeed) {
        double cap = Double.isFinite(serverMaxSpeed) && serverMaxSpeed > 0
                ? Math.min(ABSOLUTE_MAX_SPEED, serverMaxSpeed) : ABSOLUTE_MAX_SPEED;
        return new PhysicsConfig(model.build.copy(), model.highFidelity, model.useRealtimePhysics,
                model.flightMode3d, cap);
    }

    /** Maps a stick throttle in [−1,1] to the physics throttle (spec §5.2). */
    public double mapThrottle(double stick) {
        return mode3d ? Math.clamp(stick, -1.0, 1.0) : Math.clamp((stick + 1.0) * 0.5, 0.0, 1.0);
    }
}
