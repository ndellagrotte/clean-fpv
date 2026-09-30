package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/** Test helper: steady-state hover analysis of a thrust model at zero velocity, level attitude. */
final class Hover {

    private Hover() {}

    /** Net upward prop force (N) after the motors settle at {@code throttle} with the drone held still. */
    static double settledThrust(ThrustModel model, DroneBuild build, double throttle) {
        model.prepare(build);
        DroneState s = new DroneState();
        Quaternionf level = new Quaternionf();
        Vector3d f = new Vector3d();
        double h = Stepper.MAX_SUBSTEP;
        for (int i = 0; i < 128 * 3; i++) {
            f.zero();
            model.step(s, level, throttle, h, f, null);
        }
        f.zero();
        model.step(s, level, throttle, h, f, null);
        return f.y;
    }

    /** Throttle in [0,1] at which the settled thrust equals the weight, or NaN if unreachable. */
    static double hoverThrottle(ThrustModel model, DroneBuild build) {
        double weight = MassModel.flownMassKg(build) * Stepper.GRAVITY;
        if (settledThrust(model, build, 1.0) < weight) {
            return Double.NaN;
        }
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 30; i++) {
            double mid = 0.5 * (lo + hi);
            if (settledThrust(model, build, mid) < weight) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }
}
