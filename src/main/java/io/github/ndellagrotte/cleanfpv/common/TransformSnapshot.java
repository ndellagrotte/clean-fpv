package io.github.ndellagrotte.cleanfpv.common;

import io.netty.buffer.ByteBuf;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;

/**
 * Immutable drone transform as sent every armed tick (spec §8.1 #10 + velocity, PLAN §6.6):
 * body attitude quaternion (x, y, z, w), world velocity in m/s (= blocks/s), four signed motor
 * angular speeds in rad/s, and the sender's wall-clock epoch milliseconds.
 *
 * <p>Wire form (52 bytes): 4 floats quat xyzw, 3 floats velocity, 4 floats ω, 1 long epochMs.
 * The same body is used by the C2S message and (after the player UUID) the S2C relay.
 */
public final class TransformSnapshot {

    public static final int MOTORS = 4;

    public final float qx;
    public final float qy;
    public final float qz;
    public final float qw;
    public final float vx;
    public final float vy;
    public final float vz;
    private final float[] omega;
    public final long epochMs;

    public TransformSnapshot(float qx, float qy, float qz, float qw, float vx, float vy, float vz,
                             float[] omega, long epochMs) {
        this.qx = qx;
        this.qy = qy;
        this.qz = qz;
        this.qw = qw;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.omega = new float[MOTORS];
        if (omega != null) {
            System.arraycopy(omega, 0, this.omega, 0, Math.min(MOTORS, omega.length));
        }
        this.epochMs = epochMs;
    }

    /** Builds a snapshot from simulation types (doubles are narrowed to floats). */
    public static TransformSnapshot of(Quaternionfc attitude, Vector3d velocity, double[] omega, long epochMs) {
        float[] w = new float[MOTORS];
        for (int i = 0; i < MOTORS && omega != null && i < omega.length; i++) {
            w[i] = (float) omega[i];
        }
        return new TransformSnapshot(attitude.x(), attitude.y(), attitude.z(), attitude.w(),
                (float) velocity.x, (float) velocity.y, (float) velocity.z, w, epochMs);
    }

    /** Writes the attitude into {@code dest} and returns it. */
    public Quaternionf attitude(Quaternionf dest) {
        return dest.set(qx, qy, qz, qw);
    }

    /** Writes the velocity (m/s) into {@code dest} and returns it. */
    public Vector3d velocity(Vector3d dest) {
        return dest.set(vx, vy, vz);
    }

    public float omega(int motor) {
        return omega[motor];
    }

    /** Defensive copy of the four motor speeds (rad/s). */
    public float[] omegaCopy() {
        return omega.clone();
    }

    /** Largest |ω| of the four motors. */
    public float maxAbsOmega() {
        float m = 0f;
        for (float w : omega) {
            m = Math.max(m, Math.abs(w));
        }
        return m;
    }

    public double speed() {
        return Math.sqrt((double) vx * vx + (double) vy * vy + (double) vz * vz);
    }

    /** True when every float is finite and the quaternion is not degenerate. */
    public boolean isFinite() {
        if (!(Float.isFinite(qx) && Float.isFinite(qy) && Float.isFinite(qz) && Float.isFinite(qw)
                && Float.isFinite(vx) && Float.isFinite(vy) && Float.isFinite(vz))) {
            return false;
        }
        for (float w : omega) {
            if (!Float.isFinite(w)) {
                return false;
            }
        }
        float n = qx * qx + qy * qy + qz * qz + qw * qw;
        return n > 1e-6f;
    }

    public void write(ByteBuf buf) {
        buf.writeFloat(qx);
        buf.writeFloat(qy);
        buf.writeFloat(qz);
        buf.writeFloat(qw);
        buf.writeFloat(vx);
        buf.writeFloat(vy);
        buf.writeFloat(vz);
        for (float w : omega) {
            buf.writeFloat(w);
        }
        buf.writeLong(epochMs);
    }

    public static TransformSnapshot read(ByteBuf buf) {
        float qx = buf.readFloat();
        float qy = buf.readFloat();
        float qz = buf.readFloat();
        float qw = buf.readFloat();
        float vx = buf.readFloat();
        float vy = buf.readFloat();
        float vz = buf.readFloat();
        float[] w = new float[MOTORS];
        for (int i = 0; i < MOTORS; i++) {
            w[i] = buf.readFloat();
        }
        long epochMs = buf.readLong();
        return new TransformSnapshot(qx, qy, qz, qw, vx, vy, vz, w, epochMs);
    }
}
