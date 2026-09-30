package io.github.ndellagrotte.cleanfpv.common.race;

import io.github.ndellagrotte.cleanfpv.common.race.LapSequencer.Kind;
import io.github.ndellagrotte.cleanfpv.common.race.LapSequencer.Step;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LapSequencerTest {

    @Test
    void fullLapStartsProgressesAndFinishes() {
        LapSequencer seq = new LapSequencer(3);
        assertFalse(seq.isRunning());
        assertEquals(Kind.NONE, seq.onGate(1, 100).kind());

        Step start = seq.onGate(0, 1_000);
        assertEquals(Kind.STARTED, start.kind());
        assertFalse(start.restarted());
        assertEquals(1, start.nextGate());

        assertEquals(Kind.PROGRESS, seq.onGate(1, 2_000).kind());
        Step second = seq.onGate(2, 3_000);
        assertEquals(Kind.PROGRESS, second.kind());
        assertEquals(0, second.nextGate());

        Step finish = seq.onGate(0, 4_250);
        assertEquals(Kind.FINISHED, finish.kind());
        assertEquals(3_250, finish.lapMs());
        assertEquals(1, finish.nextGate());
        assertTrue(seq.isRunning());
        assertEquals(4_250, seq.lapStartMs());
    }

    @Test
    void outOfOrderGatesAreIgnored() {
        LapSequencer seq = new LapSequencer(3);
        seq.onGate(0, 0);
        assertEquals(Kind.NONE, seq.onGate(2, 10).kind());
        assertEquals(1, seq.nextGate());
        assertEquals(Kind.NONE, seq.onGate(7, 10).kind());
    }

    @Test
    void startGateOutOfOrderRestartsTheLap() {
        LapSequencer seq = new LapSequencer(3);
        seq.onGate(0, 0);
        assertTrue(seq.restartPossible());
        Step restart = seq.onGate(0, 5_000);
        assertEquals(Kind.STARTED, restart.kind());
        assertTrue(restart.restarted());
        assertEquals(5_000, seq.lapStartMs());
        assertEquals(1, seq.nextGate());
        seq.onGate(1, 6_000);
        seq.onGate(2, 7_000);
        assertFalse(seq.restartPossible());
        assertEquals(3_000, seq.onGate(0, 8_000).lapMs());
    }

    @Test
    void oneGateTrackLapsOnEveryCrossing() {
        LapSequencer seq = new LapSequencer(1);
        assertEquals(Kind.STARTED, seq.onGate(0, 0).kind());
        assertEquals(0, seq.nextGate());
        assertFalse(seq.restartPossible());
        Step lap = seq.onGate(0, 900);
        assertEquals(Kind.FINISHED, lap.kind());
        assertEquals(900, lap.lapMs());
    }

    @Test
    void emptyTrackAndResetDoNothingSurprising() {
        LapSequencer seq = new LapSequencer(0);
        assertEquals(Kind.NONE, seq.onGate(0, 0).kind());
        seq.reset(2);
        seq.onGate(0, 0);
        seq.reset(2);
        assertFalse(seq.isRunning());
        assertEquals(0, seq.nextGate());
    }

    @Test
    void negativeDurationsClampToZero() {
        LapSequencer seq = new LapSequencer(1);
        seq.onGate(0, 1_000);
        assertEquals(0, seq.onGate(0, 500).lapMs());
    }
}
