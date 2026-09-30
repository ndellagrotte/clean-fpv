package io.github.ndellagrotte.cleanfpv.client.physics;

import org.joml.Vector3d;

import java.util.Arrays;

/**
 * Mutable simulation state of the local drone (spec §5 intro). Pure (no Minecraft types); owned by
 * {@code ClientDroneContext} and mutated only by the physics step on the client thread.
 *
 * <p>Units: metres = blocks, seconds, rad/s, kelvin, joules. Attitude is <em>not</em> here: it is
 * {@code ClientDroneContext.attitude()} (mutated only by the per-frame attitude step; physics
 * reads it through {@link StepInput#attitude()}). Prop angular positions are not here either: the
 * renderer integrates them per frame from {@link #omega} (spec §5, §6.3).
 */
public final class DroneState {

    public static final int MOTORS = 4;
    /** Ambient / initial motor temperature (K). */
    public static final double AMBIENT_K = 293.15;
    /** Nominal per-cell voltage at zero throttle (spec §5.2 sag {@code 4 − 0.4·thr}). */
    public static final double REST_CELL_VOLTAGE = 4.0;

    /** Entity (feet) position in world coordinates. */
    public final Vector3d position = new Vector3d();
    /** Position at the previous step; used for disarmed velocity tracking and tick-consistency. */
    public final Vector3d lastPosition = new Vector3d();
    /** World velocity (m/s). While disarmed, tracked as {@code (pos − lastPos)/dt}. */
    public final Vector3d velocity = new Vector3d();

    /** Signed motor angular speeds (rad/s); even motors spin −, odd + (spec §5.2). */
    public final double[] omega = new double[MOTORS];
    /** Motor heat above ambient (J). */
    public final double[] heat = new double[MOTORS];
    /** Motor winding temperature (K) = ambient + heat / C. */
    public final double[] temperature = new double[MOTORS];

    /** Last applied throttle after mode mapping: [0,1] normal, [−1,1] in 3D mode. */
    public double throttle;
    /** Last effective per-cell voltage (sag model), V. */
    public double cellVoltage = REST_CELL_VOLTAGE;
    /** All-up mass used by the last step (kg); resolved from the build by the mass model. */
    public double massKg;
    /** Whether the last step collided (for bounce/HUD/debug). */
    public boolean collided;
    /** {@code System.nanoTime()} of the last realtime step, 0 = none (realtime mode dt source). */
    public long lastStepNanos;

    public DroneState() {
        resetMotors();
    }

    /** Zeroes ω and heat, temperatures to ambient, throttle 0, cell voltage to rest. */
    public void resetMotors() {
        Arrays.fill(omega, 0.0);
        Arrays.fill(heat, 0.0);
        Arrays.fill(temperature, AMBIENT_K);
        throttle = 0.0;
        cellVoltage = REST_CELL_VOLTAGE;
    }

    /** Full reset at {@code x, y, z}: motors reset, zero velocity, timestamp cleared. */
    public void reset(double x, double y, double z) {
        position.set(x, y, z);
        lastPosition.set(x, y, z);
        velocity.zero();
        resetMotors();
        collided = false;
        lastStepNanos = 0L;
    }

    public double maxAbsOmega() {
        double m = 0.0;
        for (double w : omega) {
            m = Math.max(m, Math.abs(w));
        }
        return m;
    }

    public void copyFrom(DroneState o) {
        position.set(o.position);
        lastPosition.set(o.lastPosition);
        velocity.set(o.velocity);
        System.arraycopy(o.omega, 0, omega, 0, MOTORS);
        System.arraycopy(o.heat, 0, heat, 0, MOTORS);
        System.arraycopy(o.temperature, 0, temperature, 0, MOTORS);
        throttle = o.throttle;
        cellVoltage = o.cellVoltage;
        massKg = o.massKg;
        collided = o.collided;
        lastStepNanos = o.lastStepNanos;
    }
}
