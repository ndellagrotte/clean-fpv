package io.github.ndellagrotte.cleanfpv.client.gui.logic;

import java.util.Arrays;
import java.util.Set;

/**
 * "Wiggle" detection of which physical axis the user moved (spec §3.1/§11.1): take a
 * {@link #snapshot} of the raw axes at rest, then {@link #detect} returns the axis whose value moved
 * furthest from the snapshot, provided the move exceeds a threshold (default {@value #DEFAULT_THRESHOLD}).
 * The sign of that move tells the wizard whether the channel must be inverted. Pure logic.
 */
public final class AxisDetector {

    /** Minimum raw displacement that counts as "this axis moved" (spec: 0.3). */
    public static final float DEFAULT_THRESHOLD = 0.3f;
    /** Maximum displacement that counts as "back at the snapshot" when waiting for a recenter. */
    public static final float DEFAULT_RETURN_TOLERANCE = 0.15f;

    private float[] snapshot = new float[0];

    /** Records the rest position of every axis. */
    public void snapshot(float[] rawAxes) {
        snapshot = rawAxes == null ? new float[0] : rawAxes.clone();
    }

    public boolean hasSnapshot() {
        return snapshot.length > 0;
    }

    /** Displacement of {@code axis} from the snapshot (0 when unknown or non-finite). */
    public float delta(int axis, float[] rawAxes) {
        if (rawAxes == null || axis < 0 || axis >= rawAxes.length || !Float.isFinite(rawAxes[axis])) {
            return 0f;
        }
        float base = axis < snapshot.length && Float.isFinite(snapshot[axis]) ? snapshot[axis] : 0f;
        return rawAxes[axis] - base;
    }

    /**
     * The axis that moved furthest beyond {@code threshold}, ignoring {@code exclude}; −1 if none.
     */
    public int detect(float[] rawAxes, float threshold, Set<Integer> exclude) {
        if (rawAxes == null) {
            return -1;
        }
        int best = -1;
        float bestAbs = threshold;
        for (int i = 0; i < rawAxes.length; i++) {
            if (exclude != null && exclude.contains(i)) {
                continue;
            }
            float d = Math.abs(delta(i, rawAxes));
            if (d > bestAbs) {
                bestAbs = d;
                best = i;
            }
        }
        return best;
    }

    public int detect(float[] rawAxes) {
        return detect(rawAxes, DEFAULT_THRESHOLD, null);
    }

    /** True when {@code axis} is back within {@code tolerance} of its snapshot value. */
    public boolean isReturned(int axis, float[] rawAxes, float tolerance) {
        return Math.abs(delta(axis, rawAxes)) <= tolerance;
    }

    @Override
    public String toString() {
        return "AxisDetector" + Arrays.toString(snapshot);
    }
}
