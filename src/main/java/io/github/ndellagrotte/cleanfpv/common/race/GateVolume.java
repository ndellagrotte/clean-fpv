package io.github.ndellagrotte.cleanfpv.common.race;

import net.minecraft.util.math.Vec3i;

/**
 * The space a pilot has to fly through to pass a {@link GateDef}, and the segment test the server
 * runs on it every tick (spec §13 "gate crossing via volume intersection on tick").
 *
 * <h2>Shape</h2>
 * Each non-empty row of the gate is one axis-aligned box: the row's blocks along {@code dirA}, one
 * block along {@code dirB}, extruded across the whole gate thickness (the extent of
 * {@code cornerA..cornerB} along the gate normal {@code dirA × dirB}). For a one-block-thick gate
 * this is exactly the spec's block set. A malformed gate (directions not two distinct unit axes)
 * degrades to the full {@code cornerA..cornerB} box with any face accepted.
 *
 * <h2>Crossing rule</h2>
 * A movement segment {@code a → b} passes the gate when it <em>enters</em> the volume through one
 * of the two faces perpendicular to the normal (front or back; either direction counts). A segment
 * that starts inside the volume never counts, so hovering in a gate or leaving it does not
 * re-trigger, and skimming in through a side face (flying along the gate plane) does not count.
 *
 * <p>Immutable and thread-safe; no Minecraft state beyond the {@code BlockPos}/{@code Vec3i} values.
 */
public final class GateVolume {

    /** Returned by the entry queries when the segment does not pass the gate. */
    public static final double MISS = -1.0;

    private static final double EPS = 1.0e-9;

    /** Six doubles per box: minX, minY, minZ, maxX, maxY, maxZ. */
    private final double[] boxes;
    private final int boxCount;
    /** 0 = X, 1 = Y, 2 = Z, or −1 when any face may be the entry face. */
    private final int normalAxis;
    private final double[] bounds = new double[6];

    private GateVolume(double[] boxes, int boxCount, int normalAxis) {
        this.boxes = boxes;
        this.boxCount = boxCount;
        this.normalAxis = normalAxis;
        bounds[0] = bounds[1] = bounds[2] = Double.POSITIVE_INFINITY;
        bounds[3] = bounds[4] = bounds[5] = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < boxCount; i++) {
            for (int k = 0; k < 3; k++) {
                bounds[k] = Math.min(bounds[k], boxes[i * 6 + k]);
                bounds[k + 3] = Math.max(bounds[k + 3], boxes[i * 6 + k + 3]);
            }
        }
    }

    /** Builds the volume of {@code gate} (see the class doc). */
    public static GateVolume of(GateDef gate) {
        int axisA = axisOf(gate.dirA);
        int axisB = axisOf(gate.dirB);
        int[] a = coords(gate.cornerA);
        int[] b = coords(gate.cornerB);
        if (axisA < 0 || axisB < 0 || axisA == axisB) {
            double[] box = new double[6];
            for (int k = 0; k < 3; k++) {
                box[k] = Math.min(a[k], b[k]);
                box[k + 3] = Math.max(a[k], b[k]) + 1.0;
            }
            return new GateVolume(box, 1, -1);
        }
        int normal = 3 - axisA - axisB;
        int[] dirA = coords(gate.dirA);
        int[] dirB = coords(gate.dirB);
        double normalMin = Math.min(a[normal], b[normal]);
        double normalMax = Math.max(a[normal], b[normal]) + 1.0;

        int rows = gate.rowCount();
        double[] out = new double[rows * 6];
        int count = 0;
        for (int j = 0; j < rows; j++) {
            int start = gate.rowStart(j);
            int end = gate.rowEnd(j);
            if (start > end) {
                continue; // empty row: no opening on this line
            }
            int base = count * 6;
            for (int k = 0; k < 3; k++) {
                long p1 = (long) a[k] + (long) start * dirA[k] + (long) j * dirB[k];
                long p2 = (long) a[k] + (long) end * dirA[k] + (long) j * dirB[k];
                out[base + k] = Math.min(p1, p2);
                out[base + k + 3] = Math.max(p1, p2) + 1.0;
            }
            out[base + normal] = normalMin;
            out[base + normal + 3] = normalMax;
            count++;
        }
        return new GateVolume(out, count, normal);
    }

    /** Number of row boxes (0 for a gate without any opening). */
    public int boxCount() {
        return boxCount;
    }

    /** The entry-face axis (0 X, 1 Y, 2 Z), or −1 when any face counts. */
    public int normalAxis() {
        return normalAxis;
    }

    /** Whether {@code (x, y, z)} lies strictly inside one of the row boxes. */
    public boolean contains(double x, double y, double z) {
        if (boxCount == 0 || !inside(bounds, 0, x, y, z)) {
            return false;
        }
        for (int i = 0; i < boxCount; i++) {
            if (inside(boxes, i * 6, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where the segment {@code a → b} passes the gate, as the fraction {@code t ∈ [0, 1]} of the
     * segment at the entry point, or {@link #MISS}.
     */
    public double entry(double ax, double ay, double az, double bx, double by, double bz) {
        if (boxCount == 0 || contains(ax, ay, az)) {
            return MISS;
        }
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        if (slab(bounds, 0, ax, ay, az, dx, dy, dz, -1) == MISS) {
            return MISS;
        }
        double best = MISS;
        for (int i = 0; i < boxCount; i++) {
            double t = slab(boxes, i * 6, ax, ay, az, dx, dy, dz, normalAxis);
            if (t != MISS && (best == MISS || t < best)) {
                best = t;
            }
        }
        return best;
    }

    /**
     * Like {@link #entry} but only considers the part of the segment from fraction {@code tMin}
     * on (the pilot is taken to be at {@code a + tMin·(b − a)}); the result is still a fraction of
     * the whole segment. Used to find several gates passed in order within one tick.
     */
    public double entryAfter(double ax, double ay, double az, double bx, double by, double bz, double tMin) {
        if (tMin <= 0.0) {
            return entry(ax, ay, az, bx, by, bz);
        }
        if (tMin >= 1.0) {
            return MISS;
        }
        double sx = ax + (bx - ax) * tMin;
        double sy = ay + (by - ay) * tMin;
        double sz = az + (bz - az) * tMin;
        double s = entry(sx, sy, sz, bx, by, bz);
        return s == MISS ? MISS : tMin + s * (1.0 - tMin);
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Slab test of {@code a + t·d, t ∈ [0, 1]} against one box. Returns the entry fraction, or
     * {@link #MISS} when it misses or (with {@code requiredAxis >= 0}) enters through a face that is
     * not perpendicular to {@code requiredAxis}.
     */
    private static double slab(double[] box, int off, double ax, double ay, double az,
                               double dx, double dy, double dz, int requiredAxis) {
        double tEnter = Double.NEGATIVE_INFINITY;
        double tExit = Double.POSITIVE_INFINITY;
        double requiredNear = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < 3; k++) {
            double p = k == 0 ? ax : k == 1 ? ay : az;
            double d = k == 0 ? dx : k == 1 ? dy : dz;
            double min = box[off + k];
            double max = box[off + k + 3];
            double near;
            double far;
            if (Math.abs(d) < EPS) {
                if (p < min || p > max) {
                    return MISS;
                }
                near = Double.NEGATIVE_INFINITY;
                far = Double.POSITIVE_INFINITY;
            } else {
                double t1 = (min - p) / d;
                double t2 = (max - p) / d;
                near = Math.min(t1, t2);
                far = Math.max(t1, t2);
            }
            if (k == requiredAxis) {
                requiredNear = near;
            }
            tEnter = Math.max(tEnter, near);
            tExit = Math.min(tExit, far);
        }
        if (tEnter > tExit || tEnter > 1.0 || tExit < 0.0) {
            return MISS;
        }
        if (tEnter < 0.0) {
            // Starts on this box's surface and moves out, or along it: not an entry. (A start
            // exactly on a face moving inwards has tEnter == 0 and is handled below.)
            return MISS;
        }
        if (requiredAxis >= 0 && requiredNear < tEnter - EPS) {
            return MISS;
        }
        return tEnter;
    }

    private static boolean inside(double[] box, int off, double x, double y, double z) {
        return x > box[off] && x < box[off + 3]
                && y > box[off + 1] && y < box[off + 4]
                && z > box[off + 2] && z < box[off + 5];
    }

    /** 0/1/2 for a unit vector along X/Y/Z (either sign), else −1. */
    static int axisOf(Vec3i v) {
        int x = Math.abs(v.getX());
        int y = Math.abs(v.getY());
        int z = Math.abs(v.getZ());
        if (x + y + z != 1) {
            return -1;
        }
        return x == 1 ? 0 : y == 1 ? 1 : 2;
    }

    private static int[] coords(Vec3i v) {
        return new int[] {v.getX(), v.getY(), v.getZ()};
    }
}
