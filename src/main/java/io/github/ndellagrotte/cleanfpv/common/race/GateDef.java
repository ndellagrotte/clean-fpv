package io.github.ndellagrotte.cleanfpv.common.race;

import io.github.ndellagrotte.cleanfpv.common.net.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;

import java.util.Arrays;

/**
 * One race gate (spec §8.1 "Gate"): a set of block rows. Row {@code j < rowStart.length} covers
 * blocks {@code cornerA + k·dirA + j·dirB} for {@code k ∈ [rowStart[j], rowEnd[j]]}. Immutable.
 *
 * <p>1.12 identifies dimensions by int id, so {@link #dimension} replaces the spec's dimension
 * name. Wire form: int dimension, cornerA, cornerB (BlockPos longs), rowStart[], rowEnd[]
 * (u16 length + ints, ≤ {@link #MAX_ROWS}), dirA, dirB (3 ints each).
 */
public final class GateDef {

    public static final int MAX_ROWS = 4096;

    public final int dimension;
    public final BlockPos cornerA;
    public final BlockPos cornerB;
    private final int[] rowStart;
    private final int[] rowEnd;
    public final Vec3i dirA;
    public final Vec3i dirB;

    public GateDef(int dimension, BlockPos cornerA, BlockPos cornerB, int[] rowStart, int[] rowEnd,
                   Vec3i dirA, Vec3i dirB) {
        this.dimension = dimension;
        this.cornerA = cornerA.toImmutable();
        this.cornerB = cornerB.toImmutable();
        this.rowStart = rowStart.clone();
        this.rowEnd = rowEnd.clone();
        this.dirA = new Vec3i(dirA.getX(), dirA.getY(), dirA.getZ());
        this.dirB = new Vec3i(dirB.getX(), dirB.getY(), dirB.getZ());
    }

    public int rowCount() {
        return Math.min(rowStart.length, rowEnd.length);
    }

    public int rowStart(int row) {
        return rowStart[row];
    }

    public int rowEnd(int row) {
        return rowEnd[row];
    }

    /** Midpoint of the block centres of {@link #cornerA} and {@link #cornerB} (spec §8.1). */
    public Vec3d center() {
        return new Vec3d((cornerA.getX() + cornerB.getX()) * 0.5 + 0.5,
                (cornerA.getY() + cornerB.getY()) * 0.5 + 0.5,
                (cornerA.getZ() + cornerB.getZ()) * 0.5 + 0.5);
    }

    public void write(ByteBuf buf) {
        buf.writeInt(dimension);
        Wire.writeBlockPos(buf, cornerA);
        Wire.writeBlockPos(buf, cornerB);
        Wire.writeIntArray(buf, rowStart);
        Wire.writeIntArray(buf, rowEnd);
        Wire.writeVec3i(buf, dirA);
        Wire.writeVec3i(buf, dirB);
    }

    public static GateDef read(ByteBuf buf) {
        int dim = buf.readInt();
        BlockPos a = Wire.readBlockPos(buf);
        BlockPos b = Wire.readBlockPos(buf);
        int[] start = Wire.readIntArray(buf, MAX_ROWS);
        int[] end = Wire.readIntArray(buf, MAX_ROWS);
        Vec3i dirA = Wire.readVec3i(buf);
        Vec3i dirB = Wire.readVec3i(buf);
        return new GateDef(dim, a, b, start, end, dirA, dirB);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GateDef g && dimension == g.dimension && cornerA.equals(g.cornerA)
                && cornerB.equals(g.cornerB) && Arrays.equals(rowStart, g.rowStart)
                && Arrays.equals(rowEnd, g.rowEnd) && dirA.equals(g.dirA) && dirB.equals(g.dirB);
    }

    @Override
    public int hashCode() {
        int h = dimension;
        h = 31 * h + cornerA.hashCode();
        h = 31 * h + cornerB.hashCode();
        h = 31 * h + Arrays.hashCode(rowStart);
        h = 31 * h + Arrays.hashCode(rowEnd);
        h = 31 * h + dirA.hashCode();
        h = 31 * h + dirB.hashCode();
        return h;
    }
}
