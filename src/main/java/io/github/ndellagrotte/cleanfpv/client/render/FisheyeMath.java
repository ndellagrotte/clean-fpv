package io.github.ndellagrotte.cleanfpv.client.render;

/**
 * CPU mirror of the fisheye post shader ({@code assets/cleanfpv/shaders/program/fisheye.fsh}),
 * spec §6.2. Pure math, no Minecraft types: used by unit tests and by anything that has to place
 * HUD marks on the distorted picture (e.g. race gate arrows, which need the inverse mapping).
 *
 * <h2>Coordinates</h2>
 * {@code u, v} are normalised screen coordinates in [0, 1] (either vertical orientation; the
 * mapping is point-symmetric about the centre). Radial distances are measured in picture-height
 * units with x scaled by the aspect ratio, so a circle on screen is a circle here. The mapping
 * leaves the radius {@link #PIVOT} (half the diagonal of a unit square) fixed, magnifies the
 * centre by {@code tan(h)/h} and compresses towards the edges ({@code h} = half the vertical FOV).
 */
public final class FisheyeMath {

    /** Radius (picture-height units) that the mapping maps onto itself. */
    public static final double PIVOT = Math.sqrt(0.5);
    /** Half-FOV clamp, radians; identical to the shader's. */
    public static final double MIN_HALF_FOV = 0.01;
    public static final double MAX_HALF_FOV = 1.55;

    private FisheyeMath() {}

    /**
     * Where the shader samples for the output pixel at {@code (u, v)}: writes the source
     * {@code (u', v')} into {@code out[0..1]} and returns whether it lies inside the frame (the
     * shader paints outside samples black).
     *
     * @param fovYRad vertical field of view (radians), the value set as the {@code FovY} uniform
     * @param aspect  framebuffer width / height
     */
    public static boolean sourceOf(double u, double v, double fovYRad, double aspect, double[] out) {
        double halfFov = clampHalf(fovYRad);
        double tanHalf = Math.tan(halfFov);
        double ox = u - 0.5;
        double oy = v - 0.5;
        double dist = Math.hypot(ox * aspect, oy);
        double ray = Math.atan(dist * tanHalf / PIVOT);
        double spread = ray > 1.0e-4 ? Math.tan(ray) / ray : 1.0;
        double gain = spread * halfFov / tanHalf;
        out[0] = 0.5 + ox * gain;
        out[1] = 0.5 + oy * gain;
        return out[0] >= 0.0 && out[0] <= 1.0 && out[1] >= 0.0 && out[1] <= 1.0;
    }

    /**
     * Inverse of {@link #sourceOf}: where a point of the undistorted frame at {@code (u, v)}
     * appears on screen after the fisheye pass. Written into {@code out[0..1]}; the result may lie
     * outside [0, 1] (the point is then off-screen after distortion too).
     */
    public static void screenOf(double u, double v, double fovYRad, double aspect, double[] out) {
        double halfFov = clampHalf(fovYRad);
        double tanHalf = Math.tan(halfFov);
        double sx = u - 0.5;
        double sy = v - 0.5;
        double target = Math.hypot(sx * aspect, sy);
        if (target < 1.0e-9) {
            out[0] = u;
            out[1] = v;
            return;
        }
        // Source radius as a function of the ray angle t (monotonic on (0, π/2)):
        // r_src(t) = PIVOT/tanHalf · tan(t) · tan(t)/t · halfFov/tanHalf. Solve r_src(t) = target.
        double k = PIVOT * halfFov / (tanHalf * tanHalf);
        double lo = 0.0;
        double hi = Math.PI / 2 - 1.0e-9;
        for (int i = 0; i < 80; i++) {
            double mid = 0.5 * (lo + hi);
            double t = Math.tan(mid);
            double r = mid > 1.0e-9 ? k * t * t / mid : 0.0;
            if (r < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double ray = 0.5 * (lo + hi);
        double dist = PIVOT * Math.tan(ray) / tanHalf;
        double scale = dist / target;
        out[0] = 0.5 + sx * scale;
        out[1] = 0.5 + sy * scale;
    }

    private static double clampHalf(double fovYRad) {
        double h = Double.isFinite(fovYRad) ? fovYRad * 0.5 : 1.0;
        return Math.clamp(h, MIN_HALF_FOV, MAX_HALF_FOV);
    }
}
