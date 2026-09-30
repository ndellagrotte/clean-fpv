package io.github.ndellagrotte.cleanfpv.client.render;

/**
 * Per-drone propeller angular positions (spec §6.3 "prop animation", PLAN §6.4). Pure math, no
 * Minecraft types.
 *
 * <p>Angles are integrated from each motor's signed ω against the wall clock, so calling
 * {@link #advance} more than once in a frame (anaglyph passes, inventory preview) only adds the
 * time that actually elapsed: there is no double integration (fixes spec §13.1 "integrated twice
 * per frame"). Every drone has its own instance (fixes the shared-angles bug for remote drones).
 */
public final class PropSpin {

    /** |ω| (rad/s) at or above which a motor shows a blur disc instead of its blades (spec §6.3). */
    public static final float DISC_THRESHOLD = 30f;
    /** Largest time step integrated at once (s): a stall or pause does not make props jump. */
    public static final float MAX_STEP = 0.1f;

    private static final double TWO_PI = Math.PI * 2.0;

    private final float[] angles = new float[4];
    private long lastNanos;

    /**
     * Advances every motor by {@code ω·dt}, where {@code dt} is the time since the previous call
     * (clamped to [0, {@link #MAX_STEP}]; the first call only records the clock).
     *
     * @param omega signed motor speeds, rad/s (length ≥ 4)
     * @param nowNanos {@code System.nanoTime()}
     */
    public void advance(float[] omega, long nowNanos) {
        if (lastNanos != 0L) {
            float dt = stepSeconds(lastNanos, nowNanos);
            for (int i = 0; i < angles.length; i++) {
                angles[i] = wrap(angles[i] + omega[i] * dt);
            }
        }
        lastNanos = nowNanos;
    }

    /** Current angle of motor {@code i}, radians in [0, 2π). */
    public float angle(int i) {
        return angles[i];
    }

    /** Elapsed seconds between two {@code nanoTime} stamps, clamped to [0, {@link #MAX_STEP}]. */
    public static float stepSeconds(long fromNanos, long toNanos) {
        double dt = (toNanos - fromNanos) * 1.0e-9;
        if (!(dt > 0.0)) {
            return 0f;
        }
        return (float) Math.min(dt, MAX_STEP);
    }

    /** Wraps an angle into [0, 2π); non-finite input becomes 0. */
    public static float wrap(double a) {
        if (!Double.isFinite(a)) {
            return 0f;
        }
        double w = a % TWO_PI;
        if (w < 0.0) {
            w += TWO_PI;
        }
        float f = (float) w;
        return f >= (float) TWO_PI ? 0f : f;
    }

    /** Whether a motor spinning at {@code omega} rad/s is drawn as a disc. */
    public static boolean showsDisc(float omega) {
        return Math.abs(omega) >= DISC_THRESHOLD;
    }
}
