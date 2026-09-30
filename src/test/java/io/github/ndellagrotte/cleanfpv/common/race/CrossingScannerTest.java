package io.github.ndellagrotte.cleanfpv.common.race;

import io.github.ndellagrotte.cleanfpv.common.race.CrossingScanner.Crossing;
import io.github.ndellagrotte.cleanfpv.common.race.LapSequencer.Kind;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrossingScannerTest {

    /** Upright 5×5 gate facing Z at the given z, centred on x = 0, y = 64..68. */
    private static GateVolume gateAt(int z) {
        GateDef g = GateShapes.fromCorners(0, new BlockPos(-2, 64, z), new BlockPos(2, 68, z), GateShapes.AUTO,
                p -> true).gate();
        return GateVolume.of(g);
    }

    private static List<Crossing> fly(LapSequencer seq, List<GateVolume> gates, double z0, long t0, double z1, long t1) {
        List<Crossing> out = new ArrayList<>();
        CrossingScanner.scan(seq, gates, new double[] {0.5, 66.5, z0}, t0, new double[] {0.5, 66.5, z1}, t1, out::add);
        return out;
    }

    @Test
    void severalGatesInOneSegmentAreTakenInOrderWithInterpolatedTimes() {
        List<GateVolume> gates = List.of(gateAt(10), gateAt(20));
        LapSequencer seq = new LapSequencer(2);

        List<Crossing> out = fly(seq, gates, 5, 0, 25, 1_000);
        assertEquals(2, out.size());
        assertEquals(Kind.STARTED, out.get(0).step().kind());
        assertEquals(250, out.get(0).timeMs());
        assertEquals(Kind.PROGRESS, out.get(1).step().kind());
        assertEquals(750, out.get(1).timeMs());

        // Back through gate 1 (not expected: ignored) and then gate 0 (finish).
        out = fly(seq, gates, 25, 1_000, 5, 2_000);
        assertEquals(1, out.size());
        assertEquals(Kind.FINISHED, out.get(0).step().kind());
        assertEquals(1_700, out.get(0).timeMs());
        assertEquals(1_450, out.get(0).step().lapMs());
    }

    @Test
    void hoveringInsideAGateDoesNotRetrigger() {
        List<GateVolume> gates = List.of(gateAt(10));
        LapSequencer seq = new LapSequencer(1);
        assertEquals(1, fly(seq, gates, 8, 0, 10.5, 50).size());
        assertEquals(0, fly(seq, gates, 10.5, 50, 10.6, 100).size());
        assertEquals(0, fly(seq, gates, 10.6, 100, 13, 150).size());
        List<Crossing> lap = fly(seq, gates, 13, 150, 10.5, 200);
        assertEquals(1, lap.size());
        assertEquals(Kind.FINISHED, lap.get(0).step().kind());
    }

    @Test
    void missedGateThenStartGateRestarts() {
        List<GateVolume> gates = List.of(gateAt(10), gateAt(20), gateAt(30));
        LapSequencer seq = new LapSequencer(3);
        fly(seq, gates, 5, 0, 15, 100);          // start
        List<Crossing> out = fly(seq, gates, 15, 100, 5, 200); // turn back through the start gate
        assertEquals(1, out.size());
        assertEquals(Kind.STARTED, out.get(0).step().kind());
        assertEquals(true, out.get(0).step().restarted());
    }

    @Test
    void gatesInOtherDimensionsAreSkipped() {
        List<GateVolume> gates = Arrays.asList(gateAt(10), null);
        LapSequencer seq = new LapSequencer(2);
        fly(seq, gates, 5, 0, 15, 100);
        assertEquals(0, fly(seq, gates, 15, 100, 25, 200).size());
        assertEquals(1, seq.nextGate());
    }

    @Test
    void mismatchedGateListIsIgnored() {
        LapSequencer seq = new LapSequencer(3);
        assertEquals(0, fly(seq, List.of(gateAt(10)), 5, 0, 15, 100).size());
    }
}
