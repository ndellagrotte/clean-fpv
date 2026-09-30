package io.github.ndellagrotte.cleanfpv.client.physics;

/**
 * Motor winding thermal model (spec §5.4): Joule heating {@code I²·R(T)}, Newtonian cooling toward
 * ambient with time constant {@code τ = C / (h·A)}, copper resistance
 * {@code R(T) = R₂₀·(1 + 0.00393·(T − 293.15))}. Heat is stored as joules above ambient.
 *
 * <p>The cooling term is integrated exactly for a constant heating power over the step
 * ({@code Q' = Q·e^(−dt/τ) + P·τ·(1 − e^(−dt/τ))}), which is unconditionally stable. Non-finite
 * heat is zeroed (spec NaN guard) and the rise is capped at {@link #MAX_RISE_K}.
 *
 * <p>Overheat (PLAN §10, driven by live temperatures): the mean hot resistance exceeds twice the
 * cold resistance, i.e. {@code R_cold / mean R(T) < 0.5} (≈ 254 K above ambient). Pure, stateless.
 */
public final class Thermal {

    public static final double COPPER_ALPHA = 0.00393;
    /** Cap on the temperature rise (K) so an absurd build cannot run away numerically. */
    public static final double MAX_RISE_K = 2000.0;
    /** Overheat when {@code R_cold / mean(R_hot)} falls below this. */
    public static final double OVERHEAT_RATIO = 0.5;

    private Thermal() {}

    /** Winding resistance at {@code temperatureK}. */
    public static double resistance(double coldOhms, double temperatureK) {
        double rise = Double.isFinite(temperatureK) ? temperatureK - DroneState.AMBIENT_K : 0.0;
        return coldOhms * Math.max(0.05, 1.0 + COPPER_ALPHA * rise);
    }

    /**
     * Advances the stored heat.
     *
     * @param heatJ        heat above ambient (J)
     * @param powerW       Joule heating power over the step (W)
     * @param tauSeconds   cooling time constant (s)
     * @param heatCapacity heat capacity (J/K), caps the rise at {@link #MAX_RISE_K}
     * @param dt           step (s)
     * @return new heat above ambient (J)
     */
    public static double stepHeat(double heatJ, double powerW, double tauSeconds, double heatCapacity, double dt) {
        double q = Double.isFinite(heatJ) ? heatJ : 0.0;
        double p = Double.isFinite(powerW) ? Math.max(0.0, powerW) : 0.0;
        double next;
        if (tauSeconds > 0.0 && Double.isFinite(tauSeconds)) {
            double decay = Math.exp(-dt / tauSeconds);
            next = q * decay + p * tauSeconds * (1.0 - decay);
        } else {
            next = 0.0;
        }
        if (!Double.isFinite(next) || next < 0.0) {
            return 0.0;
        }
        return Math.min(next, MAX_RISE_K * heatCapacity);
    }

    /** Winding temperature (K) for a stored heat. */
    public static double temperature(double heatJ, double heatCapacity) {
        if (!(heatCapacity > 0.0) || !Double.isFinite(heatJ)) {
            return DroneState.AMBIENT_K;
        }
        return DroneState.AMBIENT_K + heatJ / heatCapacity;
    }

    /** Live overheat check over all motors' temperatures. */
    public static boolean overheating(double[] temperaturesK) {
        double sum = 0.0;
        int n = 0;
        for (double t : temperaturesK) {
            sum += 1.0 + COPPER_ALPHA * ((Double.isFinite(t) ? t : DroneState.AMBIENT_K) - DroneState.AMBIENT_K);
            n++;
        }
        if (n == 0) {
            return false;
        }
        double meanRatio = sum / n;
        return meanRatio > 1.0 / OVERHEAT_RATIO;
    }
}
