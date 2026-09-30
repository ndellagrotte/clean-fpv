package io.github.ndellagrotte.cleanfpv.client.physics;

/**
 * Brushless motor speed ODE (spec §5.4), integrated with classic RK4:
 * {@code dω/dt = [ (V − ω/Kv_rad) / R / Kv_rad + τ_load(ω) ] / J}
 * (current {@code I = (V − ω/Kv)/R}, motor torque {@code I/Kv}, {@code τ_load} the signed
 * aerodynamic torque, which opposes the spin).
 *
 * <p>Robustness: the step is split into as many RK4 sub-steps as needed to keep each below half
 * the electromechanical time constant {@code J·R·Kv²} (tiny, high-Kv motors are stiff), capped at
 * {@link #MAX_SUBSTEPS}; after every sub-step a non-finite ω is reset to 0 (spec NaN guard) and ω is
 * clamped to ±no-load speed. Pure, stateless.
 */
public final class MotorOde {

    public static final int MAX_SUBSTEPS = 64;

    /** Aerodynamic load torque as a function of motor speed. */
    @FunctionalInterface
    public interface LoadTorque {
        double torque(double omega);

        LoadTorque NONE = omega -> 0.0;
    }

    private MotorOde() {}

    /** Right-hand side of the ODE. */
    public static double derivative(double omega, double volts, double ohms, double kvRad, double inertia,
                                    double loadTorque) {
        double current = (volts - omega / kvRad) / ohms;
        return (current / kvRad + loadTorque) / inertia;
    }

    /** Motor current (A) at {@code omega}. */
    public static double current(double omega, double volts, double ohms, double kvRad) {
        return (volts - omega / kvRad) / ohms;
    }

    /**
     * Advances ω by {@code dt}.
     *
     * @param omega current signed speed (rad/s)
     * @param volts applied average voltage (signed)
     * @param dt    step (s)
     * @param p     motor constants (Kv, inertia, no-load clamp)
     * @param ohms  winding resistance at the current temperature
     * @param load  aerodynamic load torque; {@code null} = none
     * @return the new ω, finite and within ±{@code p.noLoadOmega()}
     */
    public static double step(double omega, double volts, double dt, MotorParams p, double ohms, LoadTorque load) {
        double w = Double.isFinite(omega) ? omega : 0.0;
        if (!(dt > 0.0) || !Double.isFinite(dt)) {
            return clamp(w, p.noLoadOmega());
        }
        LoadTorque l = load != null ? load : LoadTorque.NONE;
        double v = Double.isFinite(volts) ? volts : 0.0;
        double r = Math.max(MotorParams.MIN_OHMS, ohms);
        double kv = p.kvRad();
        double j = p.inertia();
        double tau = p.spoolTimeConstant(r);
        int n = tau > 0.0 && Double.isFinite(tau)
                ? (int) Math.min(MAX_SUBSTEPS, Math.max(1, Math.ceil(dt / (0.5 * tau)))) : MAX_SUBSTEPS;
        double h = dt / n;
        for (int i = 0; i < n; i++) {
            double k1 = derivative(w, v, r, kv, j, l.torque(w));
            double w2 = w + 0.5 * h * k1;
            double k2 = derivative(w2, v, r, kv, j, l.torque(w2));
            double w3 = w + 0.5 * h * k2;
            double k3 = derivative(w3, v, r, kv, j, l.torque(w3));
            double w4 = w + h * k3;
            double k4 = derivative(w4, v, r, kv, j, l.torque(w4));
            w += h / 6.0 * (k1 + 2.0 * k2 + 2.0 * k3 + k4);
            if (!Double.isFinite(w)) {
                w = 0.0;
            }
            w = clamp(w, p.noLoadOmega());
        }
        return w;
    }

    private static double clamp(double w, double limit) {
        return Math.clamp(w, -limit, limit);
    }
}
