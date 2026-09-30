package io.github.ndellagrotte.cleanfpv.client.physics;

/**
 * Battery voltage under load (spec §5.2/§5.4): the per-cell voltage sags linearly with throttle,
 * {@code 4.0 − 0.4·|throttle|} V, and the motor sees the PWM-averaged pack voltage
 * {@code dir · Vcell · cells · throttle}. Capacity drain is not modelled (as in the spec).
 *
 * <p>Deliberate fix: the original evaluated the sag on the signed throttle, so in 3D mode a
 * negative throttle <em>raised</em> the cell voltage ({@code 4 + 0.4·|thr|}); here the sag
 * depends on the throttle magnitude. Pure, stateless.
 */
public final class Battery {

    public static final double REST_CELL_VOLTAGE = DroneState.REST_CELL_VOLTAGE;
    /** Cell voltage at full throttle. */
    public static final double FULL_LOAD_CELL_VOLTAGE = 3.6;

    private Battery() {}

    /** Effective per-cell voltage for a mapped throttle in [−1, 1]. */
    public static double cellVoltage(double throttle) {
        double t = Double.isFinite(throttle) ? Math.min(1.0, Math.abs(throttle)) : 0.0;
        return REST_CELL_VOLTAGE + (FULL_LOAD_CELL_VOLTAGE - REST_CELL_VOLTAGE) * t;
    }

    /**
     * Average voltage applied to one motor.
     *
     * @param throttle mapped throttle ([0,1], or [−1,1] in 3D mode)
     * @param cells    series cell count
     * @param dir      the motor's spin direction (±1)
     */
    public static double motorVoltage(double throttle, int cells, int dir) {
        double t = Double.isFinite(throttle) ? Math.clamp(throttle, -1.0, 1.0) : 0.0;
        return dir * cellVoltage(t) * cells * t;
    }
}
