package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.common.config.RateTriple;

/**
 * Betaflight-style rate curve (spec §4.2): stick deflection → commanded angular velocity. Pure
 * math, stateless; used by the per-frame attitude step and by the settings rate chart.
 *
 * <p>Curve, with the stick clamped to [−1, 1]:
 * <ol>
 *   <li>rates above 2 are stretched: {@code rate += 14.54·(rate − 2)} (goes past Betaflight's range)</li>
 *   <li>{@code |rc|} is captured <em>before</em> expo</li>
 *   <li>expo: {@code rc = rc·|rc|³·expo + rc·(1 − expo)}</li>
 *   <li>base: {@code 200·rate·rc} deg/s</li>
 *   <li>super: divide by {@code clamp(1 − |rc|·super, 0.01, 1)} (soft centre, fast edges)</li>
 * </ol>
 * Radio defaults (1.15 / 0.67 / 0) give 230 °/s per unit stick near centre and ≈ 697 °/s at full
 * deflection.
 */
public final class Rates {

    /** Base gain: deg/s per unit {@code rate} per unit stick. */
    public static final double BASE_DEG_PER_SEC = 200.0;
    /** Extra stretch per unit of {@code rate} above {@link #STRETCH_START}. */
    public static final double STRETCH_GAIN = 14.54;
    public static final double STRETCH_START = 2.0;
    /** Lower bound of the super-rate divisor (caps the edge boost at 100×). */
    public static final double SUPER_DIVISOR_MIN = 0.01;

    private Rates() {}

    /**
     * Commanded angular velocity in degrees per second.
     *
     * @param stick stick deflection, clamped to [−1, 1]; non-finite → 0
     * @param r     the axis' rate parameters; {@code null} → 0
     */
    public static double rateDegPerSec(double stick, RateTriple r) {
        if (r == null || !Double.isFinite(stick)) {
            return 0.0;
        }
        return rateDegPerSec(stick, r.rate, r.superRate, r.expo);
    }

    /** As {@link #rateDegPerSec(double, RateTriple)} with explicit parameters (non-finite → 0). */
    public static double rateDegPerSec(double stick, double rate, double superRate, double expo) {
        if (!Double.isFinite(stick) || !Double.isFinite(rate) || !Double.isFinite(superRate)
                || !Double.isFinite(expo)) {
            return 0.0;
        }
        double rc = Math.clamp(stick, -1.0, 1.0);
        double effRate = rate > STRETCH_START ? rate + STRETCH_GAIN * (rate - STRETCH_START) : rate;
        double rcAbs = Math.abs(rc);
        rc = rc * rcAbs * rcAbs * rcAbs * expo + rc * (1.0 - expo);
        double angVel = BASE_DEG_PER_SEC * effRate * rc;
        double divisor = Math.clamp(1.0 - rcAbs * superRate, SUPER_DIVISOR_MIN, 1.0);
        return angVel / divisor;
    }

    /** Commanded angular velocity in radians per second. */
    public static double rateRadPerSec(double stick, RateTriple r) {
        return Math.toRadians(rateDegPerSec(stick, r));
    }
}
