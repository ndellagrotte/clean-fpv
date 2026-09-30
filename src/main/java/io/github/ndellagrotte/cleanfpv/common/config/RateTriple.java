package io.github.ndellagrotte.cleanfpv.common.config;

/**
 * Betaflight-style rate parameters for one axis (spec §4.2). Plain Gson-friendly data; the curve
 * itself lives in {@code client.flight.Rates}. Ranges: rate 0..3, superRate 0..1, expo 0..1;
 * {@link #set} rejects out-of-range input (GUI fields are "silently rejected" per spec §11.2).
 */
public class RateTriple {

    public static final float RATE_MIN = 0f;
    public static final float RATE_MAX = 3f;
    public static final float SUPER_MIN = 0f;
    public static final float SUPER_MAX = 1f;
    public static final float EXPO_MIN = 0f;
    public static final float EXPO_MAX = 1f;

    public float rate;
    public float superRate;
    public float expo;

    public RateTriple() {
        this(1.15f, 0.67f, 0f);
    }

    public RateTriple(float rate, float superRate, float expo) {
        this.rate = rate;
        this.superRate = superRate;
        this.expo = expo;
    }

    /** Radio ("Fast Reset") defaults, all axes: 1.15 / 0.67 / 0.0. */
    public static RateTriple radio() {
        return new RateTriple(1.15f, 0.67f, 0f);
    }

    /** Gamepad ("Slow Reset") defaults for roll and pitch: 1.1 / 0.3 / 0.5. */
    public static RateTriple gamepad() {
        return new RateTriple(1.1f, 0.3f, 0.5f);
    }

    /** Gamepad ("Slow Reset") defaults for yaw: 1.0 / 0.3 / 0.5. */
    public static RateTriple gamepadYaw() {
        return new RateTriple(1.0f, 0.3f, 0.5f);
    }

    /** Sets all three if every value is finite and in range; returns whether it was applied. */
    public boolean set(float rate, float superRate, float expo) {
        if (!isValid(rate, superRate, expo)) {
            return false;
        }
        this.rate = rate;
        this.superRate = superRate;
        this.expo = expo;
        return true;
    }

    public static boolean isValid(float rate, float superRate, float expo) {
        return inRange(rate, RATE_MIN, RATE_MAX) && inRange(superRate, SUPER_MIN, SUPER_MAX)
                && inRange(expo, EXPO_MIN, EXPO_MAX);
    }

    public boolean isValid() {
        return isValid(rate, superRate, expo);
    }

    private static boolean inRange(float v, float min, float max) {
        return Float.isFinite(v) && v >= min && v <= max;
    }

    public RateTriple copy() {
        return new RateTriple(rate, superRate, expo);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RateTriple r && Float.compare(rate, r.rate) == 0
                && Float.compare(superRate, r.superRate) == 0 && Float.compare(expo, r.expo) == 0;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Float.hashCode(rate) + Float.hashCode(superRate)) + Float.hashCode(expo);
    }

    @Override
    public String toString() {
        return "RateTriple{rate=" + rate + ", super=" + superRate + ", expo=" + expo + '}';
    }
}
