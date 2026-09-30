package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/**
 * High-fidelity propulsion ("physics v2", spec §5.3–5.4): per motor, the {@link BladeElement} force
 * at the current speed, then the {@link MotorOde} RK4 step against the blade-element load torque,
 * {@link Battery} sag, and the {@link Thermal} winding model (temperature-dependent resistance).
 *
 * <p>Motor {@code m} spins in direction {@code dir = (m even) ? −1 : +1}; the applied voltage is
 * {@code dir · Vcell(thr) · cells · thr}. Single-threaded; allocation-free per step.
 */
public final class ThrustV2 implements ThrustModel {

    private final BladeElement blade = new BladeElement();
    private final Vector3d up = new Vector3d();
    private final Vector3d fwd = new Vector3d();
    private final Vector3d right = new Vector3d();
    private final Load load = new Load();
    private MotorParams params;
    private PropGeometry geometry;

    @Override
    public void prepare(DroneBuild build) {
        params = MotorParams.of(build);
        geometry = new PropGeometry(build);
    }

    public MotorParams params() {
        return params;
    }

    public PropGeometry geometry() {
        return geometry;
    }

    @Override
    public void step(DroneState state, Quaternionfc attitude, double throttle, double dt, Vector3d forceOut,
                     double[] torqueOut) {
        double thr = Double.isFinite(throttle) ? Math.clamp(throttle, -1.0, 1.0) : 0.0;
        state.throttle = thr;
        state.cellVoltage = Battery.cellVoltage(thr);
        basis(attitude, up, fwd, right);
        double vx = state.velocity.x;
        double vy = state.velocity.y;
        double vz = state.velocity.z;
        MotorParams p = params;
        for (int m = 0; m < DroneState.MOTORS; m++) {
            int dir = direction(m);
            double volts = Battery.motorVoltage(thr, p.cells(), dir);
            double ohms = Thermal.resistance(p.coldOhms(), state.temperature[m]);
            double w0 = Double.isFinite(state.omega[m]) ? state.omega[m] : 0.0;
            double torque = blade.evaluate(geometry, vx, vy, vz, up, fwd, right, w0, dir, forceOut);
            load.set(vx, vy, vz, dir);
            double w1 = MotorOde.step(w0, volts, dt, p, ohms, load);
            double amps = MotorOde.current(0.5 * (w0 + w1), volts, ohms, p.kvRad());
            double heat = Thermal.stepHeat(state.heat[m], amps * amps * ohms, p.coolingTau(), p.heatCapacity(), dt);
            state.heat[m] = heat;
            state.temperature[m] = Thermal.temperature(heat, p.heatCapacity());
            state.omega[m] = w1;
            if (torqueOut != null) {
                torqueOut[m] = torque;
            }
        }
    }

    @Override
    public double noLoadOmega() {
        return params != null ? params.noLoadOmega() : 0.0;
    }

    @Override
    public boolean overheating(DroneState state) {
        return Thermal.overheating(state.temperature);
    }

    /**
     * Static operating point of one motor at a mapped throttle in [0, 1], drone held still and level,
     * windings cold: the speed where motor torque balances the blade-element load (bisection), with
     * the axial thrust and current there. Requires {@link #prepare}.
     *
     * @param omega  loaded steady speed magnitude (rad/s)
     * @param thrust axial thrust of one rotor (N)
     * @param amps   motor current (A)
     */
    public record OperatingPoint(double omega, double thrust, double amps) {}

    /** See {@link OperatingPoint}. */
    public OperatingPoint operatingPoint(double throttle) {
        MotorParams p = params;
        double thr = Double.isFinite(throttle) ? Math.clamp(throttle, 0.0, 1.0) : 0.0;
        double volts = Battery.motorVoltage(thr, p.cells(), 1);
        up.set(0, 1, 0);
        fwd.set(0, 0, 1);
        right.set(1, 0, 0);
        double lo = 0.0;
        double hi = Math.min(p.noLoadOmega(), volts * p.kvRad());
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            double net = MotorOde.current(mid, volts, p.coldOhms(), p.kvRad()) / p.kvRad()
                    + blade.evaluate(geometry, 0, 0, 0, up, fwd, right, mid, 1, null);
            if (net > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double w = 0.5 * (lo + hi);
        Vector3d f = new Vector3d();
        blade.evaluate(geometry, 0, 0, 0, up, fwd, right, w, 1, f);
        return new OperatingPoint(w, f.y, MotorOde.current(w, volts, p.coldOhms(), p.kvRad()));
    }

    /**
     * One rotor in pure axial flow (level, cold, no crossflow): the thrust along up (N) and the load
     * torque magnitude (N·m) at speed {@code omega} (rad/s, design direction) with the air arriving
     * at {@code inflow} m/s from the thrust side (the drone climbing along its up axis). Requires
     * {@link #prepare}.
     *
     * @param out receives {thrust, loadTorque}
     */
    public void axial(double omega, double inflow, double[] out) {
        up.set(0, 1, 0);
        fwd.set(0, 0, 1);
        right.set(1, 0, 0);
        Vector3d f = new Vector3d();
        double torque = blade.evaluate(geometry, 0, inflow, 0, up, fwd, right, omega, 1, f);
        out[0] = f.y;
        out[1] = -torque;
    }

    /** Spin direction of motor {@code m}: −1 for even, +1 for odd (spec §5.2). */
    public static int direction(int m) {
        return (m & 1) == 0 ? -1 : 1;
    }

    /** Body up / forward / right ({@code q·Y}, {@code q·Z}, {@code q·X}) as doubles. */
    static void basis(Quaternionfc q, Vector3d up, Vector3d fwd, Vector3d right) {
        double x = q.x();
        double y = q.y();
        double z = q.z();
        double w = q.w();
        double n = x * x + y * y + z * z + w * w;
        if (!(n > 1e-12) || !Double.isFinite(n)) {
            up.set(0, 1, 0);
            fwd.set(0, 0, 1);
            right.set(1, 0, 0);
            return;
        }
        double s = 2.0 / n;
        right.set(1.0 - s * (y * y + z * z), s * (x * y + w * z), s * (x * z - w * y));
        up.set(s * (x * y - w * z), 1.0 - s * (x * x + z * z), s * (y * z + w * x));
        fwd.set(s * (x * z + w * y), s * (y * z - w * x), 1.0 - s * (x * x + y * y));
    }

    /** The blade-element torque as the ODE's load, for fixed velocity/attitude within the step. */
    private final class Load implements MotorOde.LoadTorque {
        private double vx;
        private double vy;
        private double vz;
        private int dir;

        void set(double vx, double vy, double vz, int dir) {
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.dir = dir;
        }

        @Override
        public double torque(double omega) {
            return blade.evaluate(geometry, vx, vy, vz, up, fwd, right, omega, dir, null);
        }
    }
}
