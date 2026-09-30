package io.github.ndellagrotte.cleanfpv.client.race;

/**
 * Where the next-gate ring goes on screen (spec §9 "direction indicator"): the projected gate
 * centre, run through the fisheye mapping when the lens is on (the HUD is drawn undistorted on top
 * of the distorted picture), then kept inside a margin from the screen edges. A point behind the
 * camera is pushed out along its on-screen direction, so the ring sits on the edge the pilot has to
 * turn towards. Pure math, no Minecraft types.
 *
 * <p>Matrices are OpenGL column-major 4×4 ({@code m[col * 4 + row]}), as read back with
 * {@code glGetFloat}. Screen coordinates: x right, y down, in whatever units {@code width}/
 * {@code height} use (the HUD's scaled pixels).
 */
public final class RingPlacement {

    /** Maps undistorted normalised screen coordinates to distorted ones (e.g. the fisheye). */
    @FunctionalInterface
    public interface Distortion {
        void apply(double u, double v, double[] out);
    }

    private static final double MIN_W = 1.0e-6;

    private RingPlacement() {}

    /**
     * Places the ring for the target {@code (rx, ry, rz)}, given relative to the modelview origin
     * (world position minus the render origin).
     *
     * @param distortion {@code null} for a plain perspective picture
     * @param out        receives {x, y, inside}: inside is 1 when the target is in front of the
     *                   camera and needed no clamping, else 0
     */
    public static void place(float[] modelView, float[] projection, double rx, double ry, double rz,
                             double width, double height, double margin, Distortion distortion, double[] out) {
        double ex = mul(modelView, 0, rx, ry, rz, 1.0);
        double ey = mul(modelView, 1, rx, ry, rz, 1.0);
        double ez = mul(modelView, 2, rx, ry, rz, 1.0);
        double ew = mul(modelView, 3, rx, ry, rz, 1.0);
        double cx = mul(projection, 0, ex, ey, ez, ew);
        double cy = mul(projection, 1, ex, ey, ez, ew);
        double cw = mul(projection, 3, ex, ey, ez, ew);

        double midX = width * 0.5;
        double midY = height * 0.5;
        double px;
        double py;
        boolean front = cw > MIN_W;
        if (front) {
            double u = (cx / cw + 1.0) * 0.5;
            double v = (1.0 - cy / cw) * 0.5;
            if (distortion != null) {
                double[] d = new double[2];
                distortion.apply(u, v, d);
                u = d[0];
                v = d[1];
            }
            px = u * width;
            py = v * height;
        } else {
            double dx = cx;
            double dy = -cy;
            double len = Math.hypot(dx, dy);
            if (len < 1.0e-9) {
                dx = 0.0;
                dy = 1.0;
                len = 1.0;
            }
            double far = (width + height) * 4.0;
            px = midX + dx / len * far;
            py = midY + dy / len * far;
        }
        boolean clamped = clampToBox(px, py, midX, midY, Math.max(0.0, midX - margin), Math.max(0.0, midY - margin), out);
        out[2] = front && !clamped ? 1.0 : 0.0;
    }

    /**
     * Pulls {@code (px, py)} towards the centre along the line to it until it lies within
     * {@code ±halfW, ±halfH} of the centre. Writes the point to {@code out[0..1]}; returns whether
     * it moved.
     */
    static boolean clampToBox(double px, double py, double midX, double midY, double halfW, double halfH, double[] out) {
        double dx = px - midX;
        double dy = py - midY;
        double scale = 1.0;
        if (Math.abs(dx) > halfW) {
            scale = Math.min(scale, halfW / Math.abs(dx));
        }
        if (Math.abs(dy) > halfH) {
            scale = Math.min(scale, halfH / Math.abs(dy));
        }
        if (!Double.isFinite(scale)) {
            scale = 0.0;
        }
        out[0] = midX + dx * scale;
        out[1] = midY + dy * scale;
        return scale < 1.0;
    }

    /** Row {@code row} of {@code m · (x, y, z, w)}. */
    private static double mul(float[] m, int row, double x, double y, double z, double w) {
        return m[row] * x + m[4 + row] * y + m[8 + row] * z + m[12 + row] * w;
    }
}
