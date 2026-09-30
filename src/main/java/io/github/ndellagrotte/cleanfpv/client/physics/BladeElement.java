package io.github.ndellagrotte.cleanfpv.client.physics;

import org.joml.Vector3d;

/**
 * Propeller blade-element model (spec §5.3): the force of one rotor and its aerodynamic torque
 * about the motor axis, summed over radial segments and azimuth stations ({@link PropGeometry}).
 * No induced inflow and no body-rate term (as in the spec); all rotors act at the centre of mass.
 *
 * <h2>Per segment</h2>
 * With body up {@code u}, blade span direction {@code a} (in the rotor plane) and
 * {@code t = u × a}: the blade's velocity through the air is
 * {@code flow = v + ω·r·t} with the spanwise part removed, i.e. {@code flow = f_u·u + f_t·t}.
 * The motor's design spin direction is {@code dir·t} ({@code dir = −1} for even motors, +1 for odd;
 * mirrored props, so both directions lift upward when spinning the designed way). The inflow angle
 * is {@code φ = atan2(f_u, dir·f_t)}, the angle of attack {@code α = β(r) − φ}. Lift
 * ({@code c_l(α)·q·S}) is perpendicular to the flow and the span, oriented {@code dir·(a × floŵ)}
 * (= +u in hover); drag ({@code c_d(α)·q·S_proj}, projected area
 * {@code |S·sin α| + |ℓ·thickness·cos α|}) acts along the relative wind. Spinning backwards (3D
 * mode) gives {@code α ≈ β − π}, the same lift magnitude pointing down, as with a pitched prop.
 * The torque about {@code u} (the motor's load) is {@code Σ r·F_t} of the <em>drag</em> force only,
 * as in spec §5.3 step 6; the in-plane part of lift still acts on the translation.
 *
 * <h2>Coefficient curves (own fits; spec §5.3 marks the originals as tunable)</h2>
 * <ul>
 *   <li>{@link #liftCoefficient}: flat-plate {@code sin 2α} plus a smooth low-angle boost
 *       {@code B·(g(α) − g(π − α))}, {@code g(x) = (x/α₀)²·e^(1 − (x/α₀)²)}, with the peak placed
 *       where spec §5.3 documents it (≈ 1.0 total lift at α ≈ 10.6°, so {@code B ≈ 0.64});
 *       π-periodic and odd.</li>
 *   <li>{@link #dragCoefficient}: {@code 0.4 + 1.4·sin²α} (0.4 edge-on … 1.8 broadside, the
 *       spec's documented end points).</li>
 * </ul>
 * No global rescale: with these curves the two built-in presets reproduce the reference
 * behaviour of spec §5 (5": hover ≈ 32 % throttle, thrust-to-weight ≈ 5; Tiny Whoop: hover
 * ≈ 57 %, T/W ≈ 2.5), verified by {@code HoverCalibrationTest} and {@code FlightFeelTest}.
 *
 * <p>Instances hold no state between calls; not thread-safe only by convention (single client
 * thread). Allocation-free.
 */
public final class BladeElement {

    public static final double AIR_DENSITY = DragModel.AIR_DENSITY;
    /** Height of the low-angle lift boost; the total {@code c_l} at its peak is ≈ 1.0 (spec §5.3). */
    public static final double LIFT_BOOST = 0.64;
    /** Angle of attack where the lift boost peaks (spec §5.3: ≈ 10.6°). */
    public static final double LIFT_BOOST_PEAK = Math.toRadians(10.6);
    public static final double CD_EDGE = 0.4;
    public static final double CD_BROADSIDE = 1.8;

    /** Lift coefficient for an angle of attack (radians). */
    public static double liftCoefficient(double alpha) {
        if (!Double.isFinite(alpha)) {
            return 0.0;
        }
        double a = alpha - Math.PI * Math.floor(alpha / Math.PI);
        return Math.sin(2.0 * a) + LIFT_BOOST * (boost(a) - boost(Math.PI - a));
    }

    private static double boost(double x) {
        double s = x / LIFT_BOOST_PEAK;
        double s2 = s * s;
        return s2 * Math.exp(1.0 - s2);
    }

    /** Drag coefficient for an angle of attack (radians). */
    public static double dragCoefficient(double alpha) {
        double s = Math.sin(alpha);
        return CD_EDGE + (CD_BROADSIDE - CD_EDGE) * s * s;
    }

    /**
     * Evaluates one rotor.
     *
     * @param g        prop discretisation
     * @param vx       world velocity X (m/s)
     * @param vy       world velocity Y
     * @param vz       world velocity Z
     * @param u        body up (unit, world)
     * @param f        body forward (unit, world), spans the rotor plane with {@code r}
     * @param r        body right (unit, world)
     * @param omega    signed motor speed about {@code u} (rad/s)
     * @param dir      design spin direction (±1)
     * @param forceOut if non-null, receives the added world force (N)
     * @return aerodynamic torque about {@code u} (N·m)
     */
    public double evaluate(PropGeometry g, double vx, double vy, double vz, Vector3d u, Vector3d f, Vector3d r,
                           double omega, int dir, Vector3d forceOut) {
        if (!Double.isFinite(omega)) {
            return 0.0;
        }
        double sumFu = 0.0;
        double sumFx = 0.0;
        double sumFy = 0.0;
        double sumFz = 0.0;
        double torque = 0.0;
        double segLen = g.segmentLength;
        double thickness = MassModel.BLADE_THICKNESS_M;
        for (int s = 0; s < g.stations; s++) {
            double c = g.stationCos[s];
            double sn = g.stationSin[s];
            double ax = c * f.x + sn * r.x;
            double ay = c * f.y + sn * r.y;
            double az = c * f.z + sn * r.z;
            // t = u × a
            double tx = u.y * az - u.z * ay;
            double ty = u.z * ax - u.x * az;
            double tz = u.x * ay - u.y * ax;
            double vu = vx * u.x + vy * u.y + vz * u.z;
            double vt = vx * tx + vy * ty + vz * tz;
            double stationFu = 0.0;
            double stationFt = 0.0;
            double stationTorque = 0.0;
            for (int i = 0; i < g.segRadius.length; i++) {
                double rad = g.segRadius[i];
                double fu = vu;
                double ft = vt + omega * rad;
                double s2 = fu * fu + ft * ft;
                if (s2 < 1e-12) {
                    continue;
                }
                double speed = Math.sqrt(s2);
                double phi = Math.atan2(fu, dir * ft);
                double alpha = g.segBeta[i] - phi;
                double q = 0.5 * AIR_DENSITY * s2;
                double area = segLen * g.segChord[i];
                double lift = liftCoefficient(alpha) * q * area;
                double projected = Math.abs(area * Math.sin(alpha)) + Math.abs(segLen * thickness * Math.cos(alpha));
                double drag = dragCoefficient(alpha) * q * projected;
                // lift dir (u,t) = dir·(ft, −fu)/speed ; drag dir = −(fu, ft)/speed
                double inv = 1.0 / speed;
                double segFu = (lift * dir * ft - drag * fu) * inv;
                double segFt = (-lift * dir * fu - drag * ft) * inv;
                stationFu += segFu;
                stationFt += segFt;
                // Motor load: drag only (spec §5.3 step 6).
                stationTorque += rad * (-drag * ft * inv);
            }
            sumFu += stationFu;
            sumFx += stationFt * tx;
            sumFy += stationFt * ty;
            sumFz += stationFt * tz;
            torque += stationTorque;
        }
        double w = g.stationWeight;
        if (forceOut != null) {
            forceOut.add((sumFu * u.x + sumFx) * w, (sumFu * u.y + sumFy) * w, (sumFu * u.z + sumFz) * w);
        }
        return torque * w;
    }
}
