package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/**
 * Simple propulsion ("physics v1", PLAN §6.3, spec §13 step 4): each motor's speed follows a
 * first-order lag (time constant {@link #SPOOL_TAU}) toward its steady loaded speed, and each prop
 * pushes {@code T = k·ω²·f_T(J)} along body up (downward when it spins against its design
 * direction, as in 3D mode). A linear rotor drag {@code −c·m·v} ({@link #LINEAR_DRAG_PER_SECOND})
 * adds to the shared quadratic body drag. Motor temperatures stay at ambient; it never overheats.
 *
 * <h2>Advance ratio</h2>
 * {@code J = v_axial / (ω·R)}, with {@code v_axial} the airspeed along the thrust axis (positive
 * when climbing into the air, which lowers the blade angle of attack). {@code f_T(J)} and the load
 * fraction {@code f_Q(J)} are 1 when still and fall toward 0 near the pitch speed. Descending
 * ({@code v_axial < 0}) counts as still. Crossflow (edgewise) effects are not modelled.
 *
 * <h2>Steady speed</h2>
 * The motor sees {@code V = Vcell(thr)·cells·|thr|} (with {@link Battery} sag) and the load
 * {@code Q = c·ω²·f_Q(J)}. The motor torque {@code (V − ω/Kv)/(R·Kv)} balances it at the target
 * speed ({@link #steadyOmega}); when still that is the closed-form root of
 * {@code c·ω² + ω/(R·Kv²) − V/(R·Kv) = 0}. As the prop unloads with inflow the motor speeds up
 * toward {@code V·Kv}, as in v2.
 *
 * <h2>Calibration from v2 (per build, in {@link #prepare})</h2>
 * {@code k = T_op/ω_op²} and {@code c = (I_op/Kv)/ω_op²} come from the v2 static operating point
 * ({@link ThrustV2#operatingPoint} at {@link #CALIBRATION_THROTTLE}). In pure axial flow every blade
 * section's angle of attack depends only on {@code J}, so v2's thrust and load are exactly
 * {@code ω²} times a function of {@code J}. {@code f_T} and {@code f_Q} are tabulated from the v2
 * blade model ({@link ThrustV2#axial}). v1 therefore reproduces v2's static thrust over the whole
 * throttle range (same hover throttle, same cold thrust-to-weight) and its climb, for any build.
 */
public final class ThrustV1 implements ThrustModel {

    /** Spool-up time constant (s). */
    public static final double SPOOL_TAU = 0.04;
    /** Throttle at which v1 is matched to the v2 rotor model (any value gives the same k and c). */
    public static final double CALIBRATION_THROTTLE = 1.0;
    /** Linear drag as a fraction of mass per second (1/s). */
    public static final double LINEAR_DRAG_PER_SECOND = 0.25;
    /** Samples of the advance-ratio tables. */
    public static final int TABLE_SIZE = 48;
    /** Table range, in multiples of the zero-lift advance ratio {@code pitch / (2π·R)}. */
    public static final double TABLE_SPAN = 2.0;

    private final Vector3d up = new Vector3d();
    private final Vector3d fwd = new Vector3d();
    private final Vector3d right = new Vector3d();
    private final double[] thrustTable = new double[TABLE_SIZE];
    private final double[] loadTable = new double[TABLE_SIZE];
    private double tableStep = 1.0;
    private double tipRadius = 1.0;
    private double kvRad;
    private int cells;
    private double noLoad;
    private double ohms;
    private double k;
    private double loadCoeff;
    private double linearDrag;

    @Override
    public void prepare(DroneBuild build) {
        MotorParams p = MotorParams.of(build);
        kvRad = p.kvRad();
        cells = p.cells();
        noLoad = p.noLoadOmega();
        ohms = Math.max(MotorParams.MIN_OHMS, p.coldOhms());
        linearDrag = LINEAR_DRAG_PER_SECOND * MassModel.flownMassKg(build);
        ThrustV2 reference = new ThrustV2();
        reference.prepare(build);
        tipRadius = reference.geometry().radius;
        double zeroLift = Math.max(0.0, build.propPitchMetres()) / (2.0 * Math.PI * tipRadius);
        tableStep = Math.max(0.05, TABLE_SPAN * zeroLift) / (TABLE_SIZE - 1);

        ThrustV2.OperatingPoint op = reference.operatingPoint(CALIBRATION_THROTTLE);
        double w = op.omega();
        double[] out = new double[2];
        reference.axial(w, 0.0, out);
        double t0 = out[0];
        double q0 = out[1];
        boolean ok = w > 1e-3 && t0 > 0.0 && q0 > 0.0 && Double.isFinite(t0) && Double.isFinite(q0)
                && Double.isFinite(op.amps());
        if (!ok) {
            k = 0.0;
            loadCoeff = 0.0;
            java.util.Arrays.fill(thrustTable, 0.0);
            java.util.Arrays.fill(loadTable, 0.0);
            return;
        }
        k = t0 / (w * w);
        loadCoeff = Math.max(0.0, op.amps() / kvRad) / (w * w);
        for (int i = 0; i < TABLE_SIZE; i++) {
            reference.axial(w, i * tableStep * w * tipRadius, out);
            thrustTable[i] = Double.isFinite(out[0]) ? Math.max(0.0, out[0] / t0) : 0.0;
            loadTable[i] = Double.isFinite(out[1]) ? Math.max(0.0, out[1] / q0) : 0.0;
        }
    }

    /** Thrust constant {@code k} in N per (rad/s)² (static, cold). */
    public double thrustConstant() {
        return k;
    }

    /** Static load torque constant {@code c} in N·m per (rad/s)². */
    public double loadConstant() {
        return loadCoeff;
    }

    /** Fraction of the static thrust left at an axial inflow (m/s) and motor speed (rad/s). */
    public double thrustFraction(double omega, double inflow) {
        return lookup(thrustTable, omega, inflow);
    }

    /** Fraction of the static load torque left at an axial inflow (m/s) and motor speed (rad/s). */
    public double loadFraction(double omega, double inflow) {
        return lookup(loadTable, omega, inflow);
    }

    private double lookup(double[] table, double omega, double inflow) {
        if (!(inflow > 0.0)) {
            return table[0];
        }
        double tip = Math.abs(omega) * tipRadius;
        if (!(tip > 1e-9)) {
            return table[TABLE_SIZE - 1];
        }
        double x = inflow / tip / tableStep;
        if (!(x < TABLE_SIZE - 1)) {
            return table[TABLE_SIZE - 1];
        }
        int i = (int) x;
        double f = x - i;
        return table[i] + (table[i + 1] - table[i]) * f;
    }

    /** Steady-state loaded motor speed magnitude for a mapped throttle, drone held still (rad/s). */
    public double steadyOmega(double throttle) {
        return steadyOmega(throttle, 0.0);
    }

    /**
     * Steady-state loaded motor speed magnitude (rad/s) for a mapped throttle and an axial inflow
     * (m/s, positive = climbing along the thrust axis).
     */
    public double steadyOmega(double throttle, double inflow) {
        double t = Double.isFinite(throttle) ? Math.clamp(throttle, -1.0, 1.0) : 0.0;
        double volts = Battery.cellVoltage(t) * cells * Math.abs(t);
        if (!(volts > 0.0)) {
            return 0.0;
        }
        double a = 1.0 / (ohms * kvRad * kvRad);
        double b = volts / (ohms * kvRad);
        double free = Math.min(noLoad, volts * kvRad);
        if (!(loadCoeff > 1e-15)) {
            return free;
        }
        if (!(inflow > 0.0) || !Double.isFinite(inflow)) {
            // c·ω² + a·ω − b = 0, positive root written to avoid cancellation.
            return Math.min(free, 2.0 * b / (a + Math.sqrt(a * a + 4.0 * loadCoeff * b)));
        }
        // Motor torque b − a·ω (falling) against c·ω²·f_Q(J): bisection on (0, free].
        double lo = 0.0;
        double hi = free;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            double net = b - a * mid - loadCoeff * mid * mid * loadFraction(mid, inflow);
            if (net > 0.0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    @Override
    public void step(DroneState state, Quaternionfc attitude, double throttle, double dt, Vector3d forceOut,
                     double[] torqueOut) {
        double thr = Double.isFinite(throttle) ? Math.clamp(throttle, -1.0, 1.0) : 0.0;
        state.throttle = thr;
        state.cellVoltage = Battery.cellVoltage(thr);
        ThrustV2.basis(attitude, up, fwd, right);
        double blend = 1.0 - Math.exp(-Math.max(0.0, dt) / SPOOL_TAU);
        double axial = state.velocity.x * up.x + state.velocity.y * up.y + state.velocity.z * up.z;
        if (!Double.isFinite(axial)) {
            axial = 0.0;
        }
        // Inflow seen by props pushing along +up (normal spin) or −up (reversed, 3D mode).
        double target = Math.signum(thr) * steadyOmega(thr, thr >= 0.0 ? axial : -axial);
        double thrust = 0.0;
        for (int m = 0; m < DroneState.MOTORS; m++) {
            int dir = ThrustV2.direction(m);
            double w = Double.isFinite(state.omega[m]) ? state.omega[m] : 0.0;
            // Thrust from the speed at the start of the substep (same order as v2).
            double sign = Math.signum(w * dir);
            thrust += sign * k * w * w * thrustFraction(w, sign * axial);
            w += (dir * target - w) * blend;
            state.omega[m] = Math.clamp(w, -noLoad, noLoad);
            state.heat[m] = 0.0;
            state.temperature[m] = DroneState.AMBIENT_K;
            if (torqueOut != null) {
                torqueOut[m] = 0.0;
            }
        }
        forceOut.add(up.x * thrust, up.y * thrust, up.z * thrust);
        forceOut.sub(state.velocity.x * linearDrag, state.velocity.y * linearDrag, state.velocity.z * linearDrag);
    }

    @Override
    public double noLoadOmega() {
        return noLoad;
    }
}
