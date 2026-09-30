package io.github.ndellagrotte.cleanfpv.common.race;

import java.util.List;
import java.util.function.Consumer;

/**
 * Runs one movement segment of a pilot through the gate tests and feeds the crossings to that
 * pilot's {@link LapSequencer} in the order they happen along the segment. Pure.
 *
 * <p>Only gates that can change the sequence are tested: the expected gate, and gate 0 while a
 * restart is possible. After each crossing the search continues from that point of the segment,
 * so a fast pilot can pass several gates in one tick. Crossing times are interpolated between the
 * two sample times (sub-tick resolution for the lap clock).
 */
public final class CrossingScanner {

    /** Upper bound on crossings handled per segment (guards against degenerate tracks). */
    public static final int MAX_CROSSINGS_PER_SEGMENT = 8;

    private static final double STEP_EPS = 1.0e-7;

    private CrossingScanner() {}

    /** A crossing that changed the sequence. */
    public record Crossing(int gate, long timeMs, LapSequencer.Step step) {
    }

    /**
     * @param gates  gate volumes of the track, index-aligned with the track's gates; an entry may be
     *               {@code null} for a gate in another dimension (never crossed)
     * @param from   segment start {x, y, z}, sampled at {@code fromMs}
     * @param to     segment end {x, y, z}, sampled at {@code toMs}
     * @param sink   receives every crossing that changed the sequence, in order
     * @return number of crossings delivered
     */
    public static int scan(LapSequencer seq, List<GateVolume> gates, double[] from, long fromMs,
                           double[] to, long toMs, Consumer<Crossing> sink) {
        if (seq.gateCount() == 0 || gates.size() != seq.gateCount()) {
            return 0;
        }
        double tMin = 0.0;
        int delivered = 0;
        for (int round = 0; round < MAX_CROSSINGS_PER_SEGMENT; round++) {
            int expected = seq.nextGate();
            double tExpected = entry(gates.get(expected), from, to, tMin);
            double tRestart = GateVolume.MISS;
            if (seq.restartPossible()) {
                tRestart = entry(gates.get(0), from, to, tMin);
            }
            int gate;
            double t;
            if (tExpected != GateVolume.MISS && (tRestart == GateVolume.MISS || tExpected <= tRestart)) {
                gate = expected;
                t = tExpected;
            } else if (tRestart != GateVolume.MISS) {
                gate = 0;
                t = tRestart;
            } else {
                break;
            }
            long timeMs = fromMs + Math.round(t * (toMs - fromMs));
            LapSequencer.Step step = seq.onGate(gate, timeMs);
            if (step.kind() != LapSequencer.Kind.NONE) {
                sink.accept(new Crossing(gate, timeMs, step));
                delivered++;
            }
            tMin = t + STEP_EPS;
            if (tMin >= 1.0) {
                break;
            }
        }
        return delivered;
    }

    private static double entry(GateVolume v, double[] a, double[] b, double tMin) {
        if (v == null) {
            return GateVolume.MISS;
        }
        return v.entryAfter(a[0], a[1], a[2], b[0], b[1], b[2], tMin);
    }
}
