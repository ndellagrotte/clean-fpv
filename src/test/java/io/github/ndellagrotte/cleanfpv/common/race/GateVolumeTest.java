package io.github.ndellagrotte.cleanfpv.common.race;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateVolumeTest {

    private static final double EPS = 1.0e-9;

    /** Upright gate facing Z: x 0..3, y 64..66, one block thick at z = 10 (volume x0..4, y64..67, z10..11). */
    private static GateDef open4x3() {
        return GateShapes.fromCorners(0, new BlockPos(0, 64, 10), new BlockPos(3, 66, 10), GateShapes.AUTO,
                p -> true).gate();
    }

    @Test
    void straightThroughCountsInBothDirections() {
        GateVolume v = GateVolume.of(open4x3());
        assertEquals(2, v.normalAxis());
        assertEquals(0.4, v.entry(2, 65, 8, 2, 65, 13), EPS);
        assertEquals(0.4, v.entry(2, 65, 13, 2, 65, 8), EPS);
    }

    @Test
    void segmentEndingInsideCountsButStartingInsideDoesNot() {
        GateVolume v = GateVolume.of(open4x3());
        assertEquals(0.8, v.entry(2, 65, 8, 2, 65, 10.5), EPS);
        assertEquals(GateVolume.MISS, v.entry(2, 65, 10.5, 2, 65, 13));
        assertEquals(GateVolume.MISS, v.entry(2, 65, 10.5, 2, 65, 10.5));
    }

    @Test
    void missesBesideAboveAndShortOfTheGate() {
        GateVolume v = GateVolume.of(open4x3());
        assertEquals(GateVolume.MISS, v.entry(5, 65, 8, 5, 65, 13));
        assertEquals(GateVolume.MISS, v.entry(2, 67.5, 8, 2, 67.5, 13));
        assertEquals(GateVolume.MISS, v.entry(2, 65, 5, 2, 65, 9.9));
        assertEquals(GateVolume.MISS, v.entry(2, 65, 8, 2, 65, 8));
    }

    @Test
    void enteringThroughASideFaceDoesNotCount() {
        GateVolume v = GateVolume.of(open4x3());
        // Along the gate plane, entering through the x = 0 face.
        assertEquals(GateVolume.MISS, v.entry(-2, 65, 10.5, 2, 65, 10.5));
        // Diagonal through the front face counts.
        double t = v.entry(-1, 65, 9, 3, 65, 12);
        assertEquals(1.0 / 3.0, t, EPS);
    }

    @Test
    void frameBlocksAreNotPartOfTheOpening() {
        // 5×5 frame at z = 10: border solid, 3×3 opening at x 1..3, y 65..67.
        Set<BlockPos> solid = new HashSet<>();
        for (int x = 0; x <= 4; x++) {
            for (int y = 64; y <= 68; y++) {
                if (x == 0 || x == 4 || y == 64 || y == 68) {
                    solid.add(new BlockPos(x, y, 10));
                }
            }
        }
        GateDef gate = GateShapes.fromCorners(0, new BlockPos(0, 64, 10), new BlockPos(4, 68, 10), GateShapes.AUTO,
                p -> !solid.contains(p.toImmutable())).gate();
        GateVolume v = GateVolume.of(gate);
        assertEquals(3, v.boxCount());
        assertEquals(GateVolume.MISS, v.entry(0.5, 66, 8, 0.5, 66, 12));  // through the frame post
        assertEquals(GateVolume.MISS, v.entry(2.5, 64.5, 8, 2.5, 64.5, 12)); // through the sill
        assertEquals(0.5, v.entry(2.5, 66.5, 8, 2.5, 66.5, 12), EPS);
        assertTrue(v.contains(2.5, 66.5, 10.5));
        assertFalse(v.contains(0.5, 66.5, 10.5));
    }

    @Test
    void thickGateSpansTheWholeSelection() {
        GateDef gate = GateShapes.fromCorners(0, new BlockPos(0, 64, 10), new BlockPos(3, 66, 12), 2, p -> true).gate();
        GateVolume v = GateVolume.of(gate);
        assertTrue(v.contains(1.5, 65.5, 12.5));
        assertEquals(0.5, v.entry(1.5, 65.5, 8, 1.5, 65.5, 12), EPS);
        assertEquals(0.5, v.entry(1.5, 65.5, 15, 1.5, 65.5, 11), EPS);
    }

    @Test
    void entryAfterSkipsTheEarlierPartOfTheSegment() {
        GateVolume v = GateVolume.of(open4x3());
        assertEquals(0.4, v.entryAfter(2, 65, 8, 2, 65, 13, 0.2), EPS);
        assertEquals(GateVolume.MISS, v.entryAfter(2, 65, 8, 2, 65, 13, 0.5));
    }

    @Test
    void malformedDirectionsFallBackToTheBoundingBox() {
        GateDef gate = new GateDef(0, new BlockPos(0, 0, 0), new BlockPos(1, 1, 1), new int[] {0}, new int[] {1},
                new Vec3i(1, 0, 0), new Vec3i(1, 0, 0));
        GateVolume v = GateVolume.of(gate);
        assertEquals(-1, v.normalAxis());
        assertEquals(0.5, v.entry(-1, 1, 1, 1, 1, 1), EPS); // through the x face
        assertEquals(0.5, v.entry(1, 3, 1, 1, 1, 1), EPS);  // through the top
    }

    @Test
    void gateWithoutRowsIsNeverPassed() {
        GateDef gate = new GateDef(0, new BlockPos(0, 0, 0), new BlockPos(2, 2, 0), new int[] {0, 0}, new int[] {-1, -1},
                new Vec3i(1, 0, 0), new Vec3i(0, 1, 0));
        GateVolume v = GateVolume.of(gate);
        assertEquals(0, v.boxCount());
        assertEquals(GateVolume.MISS, v.entry(1, 1, -2, 1, 1, 2));
    }
}
