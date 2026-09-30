package io.github.ndellagrotte.cleanfpv.client.gui.logic;

import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;

import java.util.Arrays;

/**
 * Calibration capture: tracks the raw minimum and maximum of every axis while the user sweeps the
 * sticks (spec §3.1 "range" step). The wizard's step is complete when each required axis spans at
 * least a minimum range <em>and</em> no new extreme has been seen for a quiet period (spec: 1.5 s).
 * {@link #applyTo} writes the captured ranges of <em>all</em> axes that moved into the channel map
 * (the original only kept four; PLAN §6.1 persists every axis). Pure logic; time is passed in.
 */
public final class RangeCapture {

    /** Quiet period after the last new extreme (spec: 1.5 s). */
    public static final long DEFAULT_QUIET_MS = 1500L;
    /** Span every required axis must reach before the step can complete. */
    public static final float DEFAULT_REQUIRED_SPAN = 1.0f;
    /** Axes that moved less than this keep the default [−1, 1] calibration in {@link #applyTo}. */
    public static final float MIN_APPLY_SPAN = 0.3f;
    /** Changes smaller than this do not count as a "new extreme" (noise). */
    private static final float EXTREME_EPSILON = 0.01f;

    private float[] min = new float[0];
    private float[] max = new float[0];
    private long lastExtremeMs;
    private boolean started;

    /** Clears the capture; the next {@link #update} starts it. */
    public void reset() {
        min = new float[0];
        max = new float[0];
        started = false;
        lastExtremeMs = 0L;
    }

    public boolean isStarted() {
        return started;
    }

    /** Folds one sample in; returns true if it produced a new extreme. */
    public boolean update(float[] rawAxes, long nowMs) {
        if (rawAxes == null) {
            return false;
        }
        if (!started || min.length != rawAxes.length) {
            min = new float[rawAxes.length];
            max = new float[rawAxes.length];
            for (int i = 0; i < rawAxes.length; i++) {
                float v = Float.isFinite(rawAxes[i]) ? rawAxes[i] : 0f;
                min[i] = v;
                max[i] = v;
            }
            started = true;
            lastExtremeMs = nowMs;
            return true;
        }
        boolean extreme = false;
        for (int i = 0; i < rawAxes.length; i++) {
            float v = rawAxes[i];
            if (!Float.isFinite(v)) {
                continue;
            }
            if (v < min[i] - EXTREME_EPSILON) {
                min[i] = v;
                extreme = true;
            } else if (v < min[i]) {
                min[i] = v;
            }
            if (v > max[i] + EXTREME_EPSILON) {
                max[i] = v;
                extreme = true;
            } else if (v > max[i]) {
                max[i] = v;
            }
        }
        if (extreme) {
            lastExtremeMs = nowMs;
        }
        return extreme;
    }

    public int axisCount() {
        return min.length;
    }

    public float min(int axis) {
        return axis >= 0 && axis < min.length ? min[axis] : 0f;
    }

    public float max(int axis) {
        return axis >= 0 && axis < max.length ? max[axis] : 0f;
    }

    public float span(int axis) {
        return max(axis) - min(axis);
    }

    /** Milliseconds since the last new extreme. */
    public long quietFor(long nowMs) {
        return started ? nowMs - lastExtremeMs : 0L;
    }

    /** True when every axis in {@code required} spans {@code minSpan} and the capture has been quiet. */
    public boolean isComplete(int[] required, float minSpan, long quietMs, long nowMs) {
        if (!started) {
            return false;
        }
        for (int axis : required) {
            if (axis < 0 || axis >= min.length || span(axis) < minSpan) {
                return false;
            }
        }
        return quietFor(nowMs) >= quietMs;
    }

    /**
     * Writes captured ranges into {@code map}: axes spanning at least {@link #MIN_APPLY_SPAN} get
     * their [min, max], every other slot is reset to [−1, 1].
     */
    public void applyTo(ChannelMap map) {
        map.ensureAxisCount(Math.max(min.length, ChannelMap.DEFAULT_AXIS_SLOTS));
        Arrays.fill(map.calMin, -1f);
        Arrays.fill(map.calMax, 1f);
        for (int i = 0; i < min.length; i++) {
            if (span(i) >= MIN_APPLY_SPAN) {
                map.calMin[i] = min[i];
                map.calMax[i] = max[i];
            }
        }
    }
}
