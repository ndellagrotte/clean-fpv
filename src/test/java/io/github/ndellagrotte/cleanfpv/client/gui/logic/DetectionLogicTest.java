package io.github.ndellagrotte.cleanfpv.client.gui.logic;

import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionLogicTest {

    @Test
    void axisDetectorPicksLargestMoveAboveThreshold() {
        AxisDetector d = new AxisDetector();
        d.snapshot(new float[] {0f, 0f, -1f, 0f});
        assertEquals(-1, d.detect(new float[] {0.2f, -0.25f, -0.8f, 0f}), "below threshold");
        assertEquals(2, d.detect(new float[] {0.35f, 0f, -0.2f, 0f}), "largest delta wins");
        assertEquals(0, d.detect(new float[] {0.35f, 0f, -0.2f, 0f}, 0.3f, Set.of(2)), "exclusion");
        assertTrue(d.delta(1, new float[] {0f, -0.9f, 0f, 0f}) < 0f, "sign tells inversion");
        assertTrue(d.isReturned(2, new float[] {0f, 0f, -0.9f, 0f}, 0.15f));
        assertFalse(d.isReturned(2, new float[] {0f, 0f, -0.5f, 0f}, 0.15f));
        assertEquals(0f, d.delta(9, new float[] {0f}), "unknown axis");
    }

    @Test
    void switchDetectorPrefersButtonsThenVirtualAxes() {
        SwitchDetector d = new SwitchDetector();
        float[] axes = {0f, 0f, -1f};
        d.snapshot(axes, new boolean[] {false, false, false});
        assertEquals(SwitchDetector.NONE, d.detect(axes, new boolean[] {false, false, false}));
        assertEquals(1, d.detect(new float[] {0f, 0f, 1f}, new boolean[] {false, true, false}));
        int idx = d.detect(new float[] {0f, 0f, 1f}, new boolean[] {false, false, false});
        assertEquals(ChannelMap.virtualIndex(2, 3), idx);
        assertTrue(ChannelMap.isVirtual(idx));

        ChannelMap map = new ChannelMap();
        assertFalse(SwitchDetector.invertForOnNow(map, idx, new float[] {0f, 0f, 1f}, new boolean[0]),
                "axis now high reads on: no invert");
        assertTrue(SwitchDetector.invertForOnNow(map, idx, new float[] {0f, 0f, -1f}, new boolean[0]),
                "switch moved to the low end: invert so it reads on");
    }

    @Test
    void rangeCaptureCompletesAfterSpanAndQuiet() {
        RangeCapture r = new RangeCapture();
        int[] required = {0, 1};
        assertFalse(r.isComplete(required, 1f, 1500, 0));
        r.update(new float[] {0f, 0f, 0.5f}, 0);
        r.update(new float[] {-0.9f, 0.8f, 0.5f}, 100);
        r.update(new float[] {0.95f, -0.85f, 0.5f}, 200);
        assertFalse(r.isComplete(required, 1f, 1500, 1000), "not quiet yet");
        r.update(new float[] {0.949f, -0.849f, 0.5f}, 1600); // noise, not a new extreme
        assertTrue(r.isComplete(required, 1f, 1500, 1700));
        assertFalse(r.isComplete(new int[] {2}, 1f, 1500, 1700), "axis 2 never moved");

        ChannelMap map = new ChannelMap();
        map.calMin[2] = -0.2f;
        r.applyTo(map);
        assertEquals(-0.9f, map.calMin[0]);
        assertEquals(0.95f, map.calMax[0]);
        assertEquals(-0.85f, map.calMin[1]);
        assertEquals(0.8f, map.calMax[1]);
        assertEquals(-1f, map.calMin[2], "unmoved axis resets to default range");
        assertEquals(1f, map.calMax[2]);
        assertEquals(ChannelMap.DEFAULT_AXIS_SLOTS, map.calMin.length);
    }
}
