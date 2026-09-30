package io.github.ndellagrotte.cleanfpv.common.race;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.util.function.Predicate;

/**
 * Builds a {@link GateDef} from two corner blocks (the "add gate by two corners" command).
 *
 * <p>The selection {@code a..b} is a box. Its thinnest axis is the gate normal (the axis the pilot
 * flies along); ties prefer X, then Z, then Y, and callers may force the axis. The other two axes
 * become {@code dirA} (horizontal where possible) and {@code dirB} (vertical for an upright gate),
 * each pointing from {@code a} towards {@code b}. Rows run along {@code dirA}, stacked along
 * {@code dirB}.
 *
 * <p>Openings: a cell {@code (k, j)} of the plane is open when every block through the gate's
 * thickness is passable. Each row keeps the span from its first to its last open cell (an empty
 * row gets {@code start > end}), so a frame of solid blocks selected together with its opening
 * yields just the opening. A selection that is entirely open (a gate drawn in the air) keeps
 * every cell; one that is entirely solid has no opening and is rejected.
 *
 * <p>Pure: world access goes through the {@code passable} predicate.
 */
public final class GateShapes {

    /** Largest accepted extent of a gate selection along any axis, in blocks. */
    public static final int MAX_EXTENT = 64;

    /** Axis choice for {@link #fromCorners}: pick the thinnest axis automatically. */
    public static final int AUTO = -1;

    private GateShapes() {}

    /** Why a selection was rejected, or {@code null} if it is acceptable. */
    public enum Problem {
        TOO_LARGE,
        NO_OPENING
    }

    /** Result of {@link #fromCorners}: exactly one of {@code gate} / {@code problem} is non-null. */
    public record Result(GateDef gate, Problem problem) {
    }

    /**
     * @param normalAxis 0 X, 1 Y, 2 Z, or {@link #AUTO}
     * @param passable   whether a block can be flown through (no collision box)
     */
    public static Result fromCorners(int dimension, BlockPos a, BlockPos b, int normalAxis,
                                     Predicate<BlockPos> passable) {
        int[] lo = {Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())};
        int[] extent = {Math.abs(a.getX() - b.getX()) + 1, Math.abs(a.getY() - b.getY()) + 1,
                Math.abs(a.getZ() - b.getZ()) + 1};
        for (int e : extent) {
            if (e > MAX_EXTENT) {
                return new Result(null, Problem.TOO_LARGE);
            }
        }
        int normal = normalAxis >= 0 && normalAxis <= 2 ? normalAxis : thinnestAxis(extent);
        int axisA;
        int axisB;
        switch (normal) {
            case 0 -> { axisA = 2; axisB = 1; }  // upright gate facing X: rows along Z, stacked up
            case 2 -> { axisA = 0; axisB = 1; }  // upright gate facing Z: rows along X, stacked up
            default -> { axisA = 0; axisB = 2; } // flat gate facing Y
        }
        int[] ca = {a.getX(), a.getY(), a.getZ()};
        int[] cb = {b.getX(), b.getY(), b.getZ()};
        int[] dirA = unit(axisA, cb[axisA] - ca[axisA]);
        int[] dirB = unit(axisB, cb[axisB] - ca[axisB]);
        int lenA = extent[axisA];
        int lenB = extent[axisB];
        int depth = extent[normal];

        int[] rowStart = new int[lenB];
        int[] rowEnd = new int[lenB];
        boolean anyOpen = false;
        boolean allOpen = true;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int[] cell = new int[3];
        for (int j = 0; j < lenB; j++) {
            int first = -1;
            int last = -1;
            for (int k = 0; k < lenA; k++) {
                boolean open = true;
                for (int n = 0; n < depth && open; n++) {
                    cell[0] = ca[0] + k * dirA[0] + j * dirB[0];
                    cell[1] = ca[1] + k * dirA[1] + j * dirB[1];
                    cell[2] = ca[2] + k * dirA[2] + j * dirB[2];
                    cell[normal] = lo[normal] + n;
                    open = passable.test(cursor.setPos(cell[0], cell[1], cell[2]));
                }
                if (open) {
                    if (first < 0) {
                        first = k;
                    }
                    last = k;
                } else {
                    allOpen = false;
                }
            }
            if (first >= 0) {
                anyOpen = true;
                rowStart[j] = first;
                rowEnd[j] = last;
            } else {
                rowStart[j] = 0;
                rowEnd[j] = -1;
            }
        }
        if (!anyOpen) {
            return new Result(null, Problem.NO_OPENING);
        }
        if (allOpen) {
            for (int j = 0; j < lenB; j++) {
                rowStart[j] = 0;
                rowEnd[j] = lenA - 1;
            }
        }
        GateDef gate = new GateDef(dimension, a, b, rowStart, rowEnd,
                new Vec3i(dirA[0], dirA[1], dirA[2]), new Vec3i(dirB[0], dirB[1], dirB[2]));
        return new Result(gate, null);
    }

    /** Thinnest axis; ties prefer X, then Z, then Y (upright gates are the common case). */
    static int thinnestAxis(int[] extent) {
        int best = 0;
        if (extent[2] < extent[best]) {
            best = 2;
        }
        if (extent[1] < extent[best]) {
            best = 1;
        }
        return best;
    }

    private static int[] unit(int axis, int delta) {
        int[] v = new int[3];
        v[axis] = delta < 0 ? -1 : 1;
        return v;
    }
}
