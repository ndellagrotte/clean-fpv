package io.github.ndellagrotte.cleanfpv.server.race;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceServerTest {

    @Test
    void segmentLimitGrowsWithTimeSinceLastSample() {
        // 100 m/s pilot, 500 ms hitch: ~50 blocks in one sample 550 ms after the previous one → scanned.
        assertTrue(50.0 <= RaceServer.maxSegment(550L, 100.0));
        // At a low configured cap a 50-block jump 50 ms after the previous sample is still a teleport.
        assertTrue(50.0 > RaceServer.maxSegment(50L, 30.0));
        // Never below the fixed floor; bad inputs fall back to it.
        assertEquals(RaceServer.MAX_SEGMENT, RaceServer.maxSegment(0L, 500.0), 0.0);
        assertEquals(RaceServer.MAX_SEGMENT, RaceServer.maxSegment(1000L, Double.NaN), 0.0);
        // Bounded by the sample-gap cap: ~750 blocks at 500 m/s.
        assertEquals(752.0, RaceServer.maxSegment(RaceServer.MAX_SAMPLE_GAP_MS, 500.0), 1e-9);
    }
}
