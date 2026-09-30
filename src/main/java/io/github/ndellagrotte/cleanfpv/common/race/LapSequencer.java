package io.github.ndellagrotte.cleanfpv.common.race;

/**
 * One pilot's lap state on one track: which gate is next and when the running lap started. Pure,
 * driven by the server with gate crossings in time order.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Gate 0 is the start/finish gate. Before the first lap only gate 0 counts: crossing it
 *       starts a lap and makes gate 1 next.</li>
 *   <li>Gates must be passed in order. After the last gate, gate 0 is next again; crossing it
 *       finishes the lap and immediately starts the next one at the same instant.</li>
 *   <li>Crossing gate 0 out of order (a gate was missed) restarts the running lap. This is the
 *       only way back after a missed gate, so it has to be allowed.</li>
 *   <li>A one-gate track is a lap per crossing of that gate (after the first).</li>
 *   <li>Any other crossing is ignored.</li>
 * </ul>
 */
public final class LapSequencer {

    /** What a crossing did. */
    public enum Kind {
        /** Not the expected gate: nothing changed. */
        NONE,
        /** A lap started (first crossing of gate 0, or a restart after a missed gate). */
        STARTED,
        /** An intermediate gate was passed. */
        PROGRESS,
        /** A lap was completed ({@link Step#lapMs}); the next lap started at the same time. */
        FINISHED
    }

    /**
     * Result of {@link #onGate}.
     *
     * @param nextGate  gate expected next after this crossing
     * @param lapMs     completed lap time for {@link Kind#FINISHED}, else −1
     * @param restarted for {@link Kind#STARTED}: a running lap was abandoned
     */
    public record Step(Kind kind, int nextGate, int lapMs, boolean restarted) {
        static final Step IGNORED = new Step(Kind.NONE, -1, -1, false);
    }

    private int gateCount;
    private boolean running;
    private int nextGate;
    private long lapStartMs;

    public LapSequencer(int gateCount) {
        reset(gateCount);
    }

    /** Back to "waiting for the start gate", for a track with {@code gateCount} gates. */
    public void reset(int gateCount) {
        this.gateCount = Math.max(0, gateCount);
        this.running = false;
        this.nextGate = 0;
        this.lapStartMs = 0L;
    }

    public int gateCount() {
        return gateCount;
    }

    /** Whether a lap is being timed. */
    public boolean isRunning() {
        return running;
    }

    /** The gate that advances the sequence (gate 0 while no lap is running). */
    public int nextGate() {
        return nextGate;
    }

    /** Start time of the running lap (caller's clock), meaningful only while {@link #isRunning()}. */
    public long lapStartMs() {
        return lapStartMs;
    }

    /** Whether crossing gate 0 now would restart the lap (a lap runs and gate 0 is not next). */
    public boolean restartPossible() {
        return running && nextGate != 0;
    }

    /** Applies a crossing of {@code gate} at {@code timeMs}. */
    public Step onGate(int gate, long timeMs) {
        if (gateCount == 0 || gate < 0 || gate >= gateCount) {
            return Step.IGNORED;
        }
        if (!running) {
            if (gate != 0) {
                return Step.IGNORED;
            }
            startLap(timeMs);
            return new Step(Kind.STARTED, nextGate, -1, false);
        }
        if (gate == nextGate) {
            if (gate == 0) {
                int lapMs = (int) Math.clamp(timeMs - lapStartMs, 0L, Integer.MAX_VALUE);
                startLap(timeMs);
                return new Step(Kind.FINISHED, nextGate, lapMs, false);
            }
            nextGate = (nextGate + 1) % gateCount;
            return new Step(Kind.PROGRESS, nextGate, -1, false);
        }
        if (gate == 0) {
            startLap(timeMs);
            return new Step(Kind.STARTED, nextGate, -1, true);
        }
        return Step.IGNORED;
    }

    private void startLap(long timeMs) {
        running = true;
        lapStartMs = timeMs;
        nextGate = 1 % gateCount;
    }
}
