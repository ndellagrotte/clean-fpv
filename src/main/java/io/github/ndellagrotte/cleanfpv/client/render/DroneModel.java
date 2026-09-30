package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Arrays;

/**
 * Procedural drone geometry built from a {@link DroneBuild} (spec §6.3). Pure data, no Minecraft
 * types: the renderer streams these vertex arrays into a {@code BufferBuilder}.
 *
 * <h2>Frame and units</h2>
 * Metres (1 m = 1 block; equivalent to the spec's 32 px/m boxes scaled by 1/32), in the drone's
 * <em>body</em> frame: +Y up, +Z forward, +X = up × forward (the attitude quaternion maps this
 * frame to world, see {@code ClientDroneContext}). The origin is the centre of the frame stack;
 * the renderer places it at the middle of the armed hitbox.
 *
 * <h2>Parts</h2>
 * Two frame plates (dark grey) with 4 or 8 coloured standoffs ({@link DroneBuild#hasEightStandoffs()}),
 * a flight-controller stack, the battery (under the bottom plate with 4 standoffs, on top with 8),
 * a split camera between the plates and an optional pro camera on top (hero or compact size;
 * only with 8 standoffs), both tilted up by {@link DroneBuild#cameraAngle}, a rear antenna leaning
 * back by the same angle (upright when the drone pitches forward to fly level), and four arms on
 * the diagonals, each with a motor bell and a coloured hub. Propellers are separate meshes in a
 * rotor frame (origin on the spin axis just above the hub, spin axis +Y) so they can be turned
 * per frame: twisted blades per spin direction, and a translucent blur disc.
 *
 * <h2>Vertex layout</h2>
 * Every array holds GL quads, {@link #FLOATS_PER_VERTEX} floats per vertex: x, y, z, r, g, b, a.
 * Colours carry a fixed per-face shade (top brightest, bottom darkest), so the renderer draws with
 * GL lighting off.
 */
public final class DroneModel {

    public static final int FLOATS_PER_VERTEX = 7;
    public static final int MOTORS = 4;

    private static final float MM = 0.001f;
    private static final float PLATE_THICKNESS = 5f * MM;
    private static final float FRAME_GREY = 36f / 255f;
    private static final float MOTOR_GREY = 0.75f;
    private static final float BATTERY_GREY = 0.75f;
    private static final int ROUND_SIDES = 12;
    private static final int BLADE_SEGMENTS = 6;
    private static final int DISC_SEGMENTS = 24;

    private final float[] body;
    /** Blade meshes: index 0 for motors with ω &lt; 0, index 1 for ω ≥ 0 (mirrored twist). */
    private final float[][] blades;
    private final float[] disc;
    private final float[] rotorOrigins;
    private final float propRadius;
    private final float lowestY;

    private DroneModel(float[] body, float[][] blades, float[] disc, float[] rotorOrigins, float propRadius,
                       float lowestY) {
        this.body = body;
        this.blades = blades;
        this.disc = disc;
        this.rotorOrigins = rotorOrigins;
        this.propRadius = propRadius;
        this.lowestY = lowestY;
    }

    /** Static body quads (frame, electronics, cameras, antenna, arms, motors, hubs). */
    public float[] body() {
        return body;
    }

    /** Blade quads in the rotor frame for a motor spinning with the sign of {@code omega}. */
    public float[] blades(float omega) {
        return blades[omega < 0f ? 0 : 1];
    }

    /** Blur-disc quads in the rotor frame (translucent: draw with blending, depth writes off). */
    public float[] disc() {
        return disc;
    }

    /** Rotor origin of motor {@code i} in the body frame, written into {@code dest}. */
    public Vector3f rotorOrigin(int i, Vector3f dest) {
        return dest.set(rotorOrigins[i * 3], rotorOrigins[i * 3 + 1], rotorOrigins[i * 3 + 2]);
    }

    /** Propeller radius (m). */
    public float propRadius() {
        return propRadius;
    }

    /** Lowest body y (m, body frame); diagnostic/test aid. */
    public float lowestY() {
        return lowestY;
    }

    // =============================================================================================
    // Construction

    /** Builds the model for {@code build} (not modified; values are read as sanitized ranges). */
    public static DroneModel build(DroneBuild build) {
        Mesh mesh = new Mesh();
        Matrix4f m = new Matrix4f();

        float w = build.frameWidth * MM;
        float h = build.frameHeight * MM;
        float len = build.frameLength * MM;
        float tilt = (float) Math.toRadians(build.cameraAngle);
        boolean eight = build.hasEightStandoffs();
        float cr = build.red;
        float cg = build.green;
        float cb = build.blue;

        float topPlateY = h * 0.5f + PLATE_THICKNESS * 0.5f;
        float bottomPlateY = -topPlateY;
        float topSurface = h * 0.5f + PLATE_THICKNESS;
        float bottomSurface = -topSurface;

        // Frame plates.
        mesh.box(m.identity(), 0f, topPlateY, 0f, w, PLATE_THICKNESS, len, FRAME_GREY, FRAME_GREY, FRAME_GREY);
        mesh.box(m.identity(), 0f, bottomPlateY, 0f, w, PLATE_THICKNESS, len, FRAME_GREY, FRAME_GREY, FRAME_GREY);

        // Standoffs (build colour).
        float post = Math.max(3f * MM, Math.min(w, len) * 0.12f);
        float px = Math.max(0f, w * 0.5f - post);
        float pz = Math.max(0f, len * 0.5f - post);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sz = -1; sz <= 1; sz += 2) {
                mesh.box(m.identity(), sx * px, 0f, sz * pz, post, h, post, cr, cg, cb);
                if (eight) {
                    mesh.box(m.identity(), sx * px, 0f, sz * pz / 3f, post, h, post, cr, cg, cb);
                }
            }
        }

        // Flight-controller / ESC stack.
        float stackW = Math.min(w * 0.8f, 36f * MM);
        float stackL = Math.min(len * 0.4f, 36f * MM);
        mesh.box(m.identity(), 0f, 0f, 0f, stackW, h * 0.7f, stackL, 0.12f, 0.14f, 0.12f);

        // Battery: volume scales with capacity × cells (reference: 4S 1300 mAh ≈ 35×34×72 mm).
        float packScale = (float) Math.cbrt(Math.max(1.0, build.batteryMah * (double) build.batteryCells) / 5200.0);
        float battW = 35f * MM * packScale;
        float battH = 34f * MM * packScale;
        float battL = 72f * MM * packScale;
        float battY = eight ? topSurface + battH * 0.5f : bottomSurface - battH * 0.5f;
        float battZ = eight ? -len * 0.1f : 0f;
        mesh.box(m.identity(), 0f, battY, battZ, battW, battH, battL, BATTERY_GREY, BATTERY_GREY, BATTERY_GREY);
        float strap = Math.max(4f * MM, battL * 0.12f);
        mesh.box(m.identity(), 0f, battY, battZ, battW * 1.04f, battH * 1.04f, strap, cr * 0.5f, cg * 0.5f, cb * 0.5f);
        float lowest = eight ? bottomSurface : battY - battH * 0.5f;

        // Split camera between the plates at the front, tilted up by the camera angle.
        float cam = Math.min(19f * MM, Math.min(h, w) * 0.85f);
        float camZ = len * 0.5f - cam * 0.5f;
        m.identity().translate(0f, 0f, camZ).rotateX(-tilt);
        mesh.box(m, 0f, 0f, 0f, cam, cam, cam, 0.08f, 0.08f, 0.09f);
        mesh.cylinderZ(m, 0f, 0f, cam * 0.5f, cam * 0.32f, cam * 0.3f, 0.10f, 0.12f, 0.20f);

        // Optional pro camera on the top plate (only on frames with eight standoffs).
        if (build.showProCam && eight) {
            float pw = (build.heroCam ? 62f : 38f) * MM;
            float ph = (build.heroCam ? 44.5f : 38f) * MM;
            float pd = (build.heroCam ? 24f : 36f) * MM;
            float grey = build.heroCam ? 0.18f : 0.26f;
            m.identity().translate(0f, topSurface + ph * 0.5f, len * 0.5f - pd * 0.5f).rotateX(-tilt);
            mesh.box(m, 0f, 0f, 0f, pw, ph, pd, grey, grey, grey);
            float lens = Math.min(pw, ph) * 0.28f;
            mesh.cylinderZ(m, 0f, 0f, pd * 0.5f, lens, pd * 0.3f, 0.10f, 0.12f, 0.20f);
        }

        // Rear antenna: leans back by the camera angle so it is upright in forward flight.
        float antLen = build.antennaLength * MM;
        float stem = Math.max(1.5f * MM, antLen * 0.05f);
        float antZ = -Math.max(0f, len * 0.5f - post);
        // Same rotation as the cameras: rotateX(−tilt) turns +Z up and +Y back (towards −Z).
        m.identity().translate(0f, topSurface, antZ).rotateX(-tilt);
        mesh.box(m, 0f, antLen * 0.35f, 0f, stem, antLen * 0.7f, stem, 0.06f, 0.06f, 0.06f);
        mesh.box(m, 0f, antLen * 0.85f, 0f, stem * 2.4f, antLen * 0.3f, stem * 2.4f, cr, cg, cb);

        // Arms, motors, hubs.
        float propR = (float) build.bladeLengthMetres();
        float motorR = build.motorWidth * MM * 0.5f;
        float halfDiag = (float) Math.hypot(w * 0.5f, len * 0.5f);
        float reach = Math.max(Math.max(propR * 1.08f * (float) Math.sqrt(2.0), halfDiag * 0.6f), motorR * 3f);
        float armW = build.armWidth * MM;
        float armT = build.armThickness * MM;
        float armTop = bottomPlateY + armT * 0.5f;
        float bellH = build.motorHeight * MM + 4f * MM;
        float hubR = Math.max(2.5f * MM, motorR * 0.4f);
        float hubH = 4f * MM;
        float[] rotors = new float[MOTORS * 3];
        for (int i = 0; i < MOTORS; i++) {
            float phi = (float) Math.toRadians(45.0 + 90.0 * i);
            m.identity().rotateY(phi);
            mesh.box(m, 0f, bottomPlateY, reach * 0.5f, armW, armT, reach + motorR, FRAME_GREY, FRAME_GREY, FRAME_GREY);
            float mx = reach * (float) Math.sin(phi);
            float mz = reach * (float) Math.cos(phi);
            m.identity().translate(mx, armTop, mz);
            mesh.cylinderY(m, 0f, 0f, 0f, motorR, bellH, MOTOR_GREY, MOTOR_GREY, MOTOR_GREY);
            mesh.cylinderY(m, 0f, bellH, 0f, hubR, hubH, cr, cg, cb);
            rotors[i * 3] = mx;
            rotors[i * 3 + 1] = armTop + bellH + hubH * 0.5f;
            rotors[i * 3 + 2] = mz;
            lowest = Math.min(lowest, bottomPlateY - armT * 0.5f);
        }

        float[][] bladeMeshes = {
                blades(build, hubR, propR, -1f),
                blades(build, hubR, propR, 1f)
        };
        return new DroneModel(mesh.toArray(), bladeMeshes, disc(build, hubR, propR), rotors, propR, lowest);
    }

    /** Twisted blades for one spin direction ({@code spin} = ±1, sign of ω about +Y). */
    private static float[] blades(DroneBuild build, float rootR, float tipR, float spin) {
        Mesh mesh = new Mesh();
        int n = Math.max(2, build.blades);
        float chord = build.bladeWidth * MM;
        float pitch = (float) build.propPitchMetres();
        float r0 = Math.min(rootR, tipR * 0.5f);
        float cr = build.red * 0.8f + 0.05f;
        float cg = build.green * 0.8f + 0.05f;
        float cb = build.blue * 0.8f + 0.05f;
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        Vector3f d = new Vector3f();
        for (int k = 0; k < n; k++) {
            double base = 2.0 * Math.PI * k / n;
            for (int s = 0; s < BLADE_SEGMENTS; s++) {
                float t0 = (float) s / BLADE_SEGMENTS;
                float t1 = (float) (s + 1) / BLADE_SEGMENTS;
                bladeSection(base, r0 + (tipR - r0) * t0, t0, chord, pitch, spin, a, b);
                bladeSection(base, r0 + (tipR - r0) * t1, t1, chord, pitch, spin, d, c);
                float shade = 0.75f + 0.25f * (1f - t0);
                mesh.quad(a, b, c, d, cr * shade, cg * shade, cb * shade, 1f);
            }
        }
        return mesh.toArray();
    }

    /**
     * Leading ({@code lead}) and trailing ({@code trail}) edge points of the blade section at
     * radius {@code r}: chord tapered towards the tip, pitched by {@code atan(pitch / 2πr)} with the
     * leading edge (the side moving forward for this spin direction) raised.
     */
    private static void bladeSection(double base, float r, float t, float chord, float pitch, float spin,
                                     Vector3f lead, Vector3f trail) {
        float sin = (float) Math.sin(base);
        float cos = (float) Math.cos(base);
        float c = chord * (1f - 0.45f * t) * 0.5f;
        float beta = (float) Math.atan2(pitch, 2.0 * Math.PI * Math.max(r, 1.0e-4));
        float along = c * (float) Math.cos(beta);
        float rise = c * (float) Math.sin(beta);
        // Radial direction (sin, 0, cos); direction of travel for positive spin (cos, 0, -sin).
        float ex = cos * spin;
        float ez = -sin * spin;
        lead.set(r * sin + ex * along, rise, r * cos + ez * along);
        trail.set(r * sin - ex * along, -rise, r * cos - ez * along);
    }

    /** Blur disc: concentric bands in the build colour, most opaque near the rim. */
    private static float[] disc(DroneBuild build, float rootR, float tipR) {
        Mesh mesh = new Mesh();
        float density = Math.clamp(0.55f + 0.12f * build.blades, 0.7f, 1.1f);
        float[] radii = {Math.min(rootR, tipR * 0.5f), tipR * 0.55f, tipR * 0.96f, tipR, tipR * 1.03f};
        float[] alpha = {0.10f, 0.26f, 0.42f, 0.5f, 0f};
        float r = Math.min(1f, build.red * 0.85f + 0.15f);
        float g = Math.min(1f, build.green * 0.85f + 0.15f);
        float b = Math.min(1f, build.blue * 0.85f + 0.15f);
        for (int band = 0; band < radii.length - 1; band++) {
            float ri = radii[band];
            float ro = radii[band + 1];
            float ai = Math.min(1f, alpha[band] * density);
            float ao = Math.min(1f, alpha[band + 1] * density);
            for (int s = 0; s < DISC_SEGMENTS; s++) {
                double p0 = 2.0 * Math.PI * s / DISC_SEGMENTS;
                double p1 = 2.0 * Math.PI * (s + 1) / DISC_SEGMENTS;
                float s0 = (float) Math.sin(p0);
                float c0 = (float) Math.cos(p0);
                float s1 = (float) Math.sin(p1);
                float c1 = (float) Math.cos(p1);
                mesh.vertex(ri * s0, 0f, ri * c0, r, g, b, ai);
                mesh.vertex(ri * s1, 0f, ri * c1, r, g, b, ai);
                mesh.vertex(ro * s1, 0f, ro * c1, r, g, b, ao);
                mesh.vertex(ro * s0, 0f, ro * c0, r, g, b, ao);
            }
        }
        return mesh.toArray();
    }

    // =============================================================================================
    // Mesh builder

    /** Growable quad list with transform + shading helpers. */
    static final class Mesh {
        private float[] data = new float[1024];
        private int size;
        private final Vector3f p = new Vector3f();
        private final Vector3f n = new Vector3f();
        private final Vector3f[] corners = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};

        void vertex(float x, float y, float z, float r, float g, float b, float a) {
            if (size + FLOATS_PER_VERTEX > data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = x;
            data[size++] = y;
            data[size++] = z;
            data[size++] = r;
            data[size++] = g;
            data[size++] = b;
            data[size++] = a;
        }

        /** Untransformed quad with a flat colour. */
        void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, float r, float g, float bl, float alpha) {
            vertex(a.x, a.y, a.z, r, g, bl, alpha);
            vertex(b.x, b.y, b.z, r, g, bl, alpha);
            vertex(c.x, c.y, c.z, r, g, bl, alpha);
            vertex(d.x, d.y, d.z, r, g, bl, alpha);
        }

        /** Quad given in local coordinates with local normal {@code (nx, ny, nz)}, transformed by {@code m}. */
        private void face(Matrix4f m, float nx, float ny, float nz, float r, float g, float b) {
            m.transformDirection(n.set(nx, ny, nz)).normalize();
            float shade = shade(n);
            for (Vector3f v : corners) {
                m.transformPosition(p.set(v));
                vertex(p.x, p.y, p.z, r * shade, g * shade, b * shade, 1f);
            }
        }

        /** Axis-aligned box (in {@code m}'s local frame) centred at {@code (cx, cy, cz)} with full sizes {@code sx, sy, sz}. */
        void box(Matrix4f m, float cx, float cy, float cz, float sx, float sy, float sz, float r, float g, float b) {
            float x0 = cx - sx * 0.5f;
            float x1 = cx + sx * 0.5f;
            float y0 = cy - sy * 0.5f;
            float y1 = cy + sy * 0.5f;
            float z0 = cz - sz * 0.5f;
            float z1 = cz + sz * 0.5f;
            // Counter-clockwise seen from outside.
            set(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            face(m, 0f, 1f, 0f, r, g, b);
            set(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            face(m, 0f, -1f, 0f, r, g, b);
            set(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            face(m, 0f, 0f, 1f, r, g, b);
            set(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
            face(m, 0f, 0f, -1f, r, g, b);
            set(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
            face(m, 1f, 0f, 0f, r, g, b);
            set(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            face(m, -1f, 0f, 0f, r, g, b);
        }

        /** Closed round prism along +Y from {@code y0} to {@code y0 + height}, centred on {@code (cx, cz)}. */
        void cylinderY(Matrix4f m, float cx, float y0, float cz, float radius, float height, float r, float g,
                       float b) {
            float y1 = y0 + height;
            for (int s = 0; s < ROUND_SIDES; s++) {
                double p0 = 2.0 * Math.PI * s / ROUND_SIDES;
                double p1 = 2.0 * Math.PI * (s + 1) / ROUND_SIDES;
                double pm = 0.5 * (p0 + p1);
                float x0 = cx + radius * (float) Math.sin(p0);
                float z0 = cz + radius * (float) Math.cos(p0);
                float x1 = cx + radius * (float) Math.sin(p1);
                float z1 = cz + radius * (float) Math.cos(p1);
                set(x0, y0, z0, x1, y0, z1, x1, y1, z1, x0, y1, z0);
                face(m, (float) Math.sin(pm), 0f, (float) Math.cos(pm), r, g, b);
                set(cx, y1, cz, x0, y1, z0, x1, y1, z1, cx, y1, cz);
                face(m, 0f, 1f, 0f, r, g, b);
                set(cx, y0, cz, cx, y0, cz, x1, y0, z1, x0, y0, z0);
                face(m, 0f, -1f, 0f, r, g, b);
            }
        }

        /** Round prism along +Z starting at {@code z0} (a lens), centred on {@code (cx, cy)}. */
        void cylinderZ(Matrix4f m, float cx, float cy, float z0, float radius, float length, float r, float g,
                       float b) {
            float z1 = z0 + length;
            for (int s = 0; s < ROUND_SIDES; s++) {
                double p0 = 2.0 * Math.PI * s / ROUND_SIDES;
                double p1 = 2.0 * Math.PI * (s + 1) / ROUND_SIDES;
                double pm = 0.5 * (p0 + p1);
                float x0 = cx + radius * (float) Math.cos(p0);
                float y0 = cy + radius * (float) Math.sin(p0);
                float x1 = cx + radius * (float) Math.cos(p1);
                float y1 = cy + radius * (float) Math.sin(p1);
                set(x0, y0, z0, x1, y1, z0, x1, y1, z1, x0, y0, z1);
                face(m, (float) Math.cos(pm), (float) Math.sin(pm), 0f, r, g, b);
                set(cx, cy, z1, x0, y0, z1, x1, y1, z1, cx, cy, z1);
                face(m, 0f, 0f, 1f, r * 0.6f, g * 0.6f, b * 0.6f);
            }
        }

        private void set(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                         float dx, float dy, float dz) {
            corners[0].set(ax, ay, az);
            corners[1].set(bx, by, bz);
            corners[2].set(cx, cy, cz);
            corners[3].set(dx, dy, dz);
        }

        float[] toArray() {
            return Arrays.copyOf(data, size);
        }

        /** Fixed "sun from above" shade for a unit normal: top 1.0, sides ≈ 0.7, bottom ≈ 0.45. */
        static float shade(Vector3f normal) {
            return 0.72f + 0.28f * normal.y - 0.05f * Math.abs(normal.x);
        }
    }
}
