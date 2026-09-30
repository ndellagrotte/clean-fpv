package io.github.ndellagrotte.cleanfpv.common.race;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GateShapesTest {

    @Test
    void uprightGateFacingZHasHorizontalRowsStackedUp() {
        GateDef g = GateShapes.fromCorners(-1, new BlockPos(0, 64, 10), new BlockPos(3, 66, 10), GateShapes.AUTO,
                p -> true).gate();
        assertEquals(-1, g.dimension);
        assertEquals(new Vec3i(1, 0, 0), g.dirA);
        assertEquals(new Vec3i(0, 1, 0), g.dirB);
        assertEquals(3, g.rowCount());
        for (int j = 0; j < 3; j++) {
            assertEquals(0, g.rowStart(j));
            assertEquals(3, g.rowEnd(j));
        }
    }

    @Test
    void directionsPointFromCornerAToCornerB() {
        GateDef g = GateShapes.fromCorners(0, new BlockPos(5, 70, 3), new BlockPos(5, 68, 0), GateShapes.AUTO,
                p -> true).gate();
        // Thinnest axis is X: rows along Z (towards smaller z), stacked downwards.
        assertEquals(new Vec3i(0, 0, -1), g.dirA);
        assertEquals(new Vec3i(0, -1, 0), g.dirB);
        assertEquals(3, g.rowCount());
        assertEquals(3, g.rowEnd(0));
    }

    @Test
    void flatGateFacesUpAndAxisCanBeForced() {
        GateDef flat = GateShapes.fromCorners(0, new BlockPos(0, 64, 0), new BlockPos(2, 64, 2), GateShapes.AUTO,
                p -> true).gate();
        assertEquals(new Vec3i(1, 0, 0), flat.dirA);
        assertEquals(new Vec3i(0, 0, 1), flat.dirB);

        GateDef forced = GateShapes.fromCorners(0, new BlockPos(0, 64, 0), new BlockPos(2, 64, 2), 2,
                p -> true).gate();
        assertEquals(new Vec3i(1, 0, 0), forced.dirA);
        assertEquals(new Vec3i(0, 1, 0), forced.dirB);
        assertEquals(1, forced.rowCount());
    }

    @Test
    void tiesPreferAnUprightGate() {
        assertEquals(0, GateShapes.thinnestAxis(new int[] {3, 3, 3}));
        assertEquals(2, GateShapes.thinnestAxis(new int[] {3, 3, 1}));
        assertEquals(2, GateShapes.thinnestAxis(new int[] {3, 1, 1}));
        assertEquals(1, GateShapes.thinnestAxis(new int[] {3, 1, 3}));
    }

    @Test
    void rowsKeepTheSpanOfOpenCells() {
        // Row y=64 open at x=1..2 only, row y=65 fully blocked, row y=66 open at x=0.
        GateShapes.Result r = GateShapes.fromCorners(0, new BlockPos(0, 64, 0), new BlockPos(3, 66, 0), GateShapes.AUTO,
                p -> (p.getY() == 64 && (p.getX() == 1 || p.getX() == 2)) || (p.getY() == 66 && p.getX() == 0));
        GateDef g = r.gate();
        assertEquals(1, g.rowStart(0));
        assertEquals(2, g.rowEnd(0));
        assertEquals(0, g.rowStart(1));
        assertEquals(-1, g.rowEnd(1));
        assertEquals(0, g.rowStart(2));
        assertEquals(0, g.rowEnd(2));
    }

    @Test
    void everyBlockThroughTheThicknessMustBeOpen() {
        GateDef g = GateShapes.fromCorners(0, new BlockPos(0, 0, 0), new BlockPos(1, 0, 1), 2,
                p -> !(p.getX() == 1 && p.getZ() == 1)).gate();
        assertEquals(0, g.rowStart(0));
        assertEquals(0, g.rowEnd(0));
    }

    @Test
    void rejectsSolidAndOversizedSelections() {
        GateShapes.Result solid = GateShapes.fromCorners(0, new BlockPos(0, 0, 0), new BlockPos(2, 2, 0),
                GateShapes.AUTO, p -> false);
        assertNull(solid.gate());
        assertEquals(GateShapes.Problem.NO_OPENING, solid.problem());

        GateShapes.Result big = GateShapes.fromCorners(0, new BlockPos(0, 0, 0),
                new BlockPos(GateShapes.MAX_EXTENT, 2, 0), GateShapes.AUTO, p -> true);
        assertEquals(GateShapes.Problem.TOO_LARGE, big.problem());
    }
}
