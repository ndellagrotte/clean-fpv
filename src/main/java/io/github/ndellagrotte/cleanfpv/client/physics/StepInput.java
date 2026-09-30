package io.github.ndellagrotte.cleanfpv.client.physics;

import org.joml.Quaternionfc;

/**
 * Per-step controls for one physics step (a whole tick, or one realtime frame; the stepper splits
 * it into ≤ 1/128 s substeps). Immutable.
 *
 * @param attitude      body attitude for this step (read-only view; physics never mutates it)
 * @param throttleStick raw throttle stick in [−1,1] after keyboard overrides (spec §3.2);
 *                      mode-mapped via {@link PhysicsConfig#mapThrottle}
 * @param dt            step length in seconds (0.05 in tick mode, frame dt in realtime mode)
 * @param noClip        bypass collision entirely (spectator / noclip player)
 */
public record StepInput(Quaternionfc attitude, float throttleStick, double dt, boolean noClip) {
}
