package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;

/**
 * Per-build propeller discretisation for the blade-element model (spec §5.3). Immutable after
 * construction (arrays are not exposed for writing by convention).
 *
 * <ul>
 *   <li>5 radial segments per blade, centre {@code r = (i + 0.5)/5 · R}; segments inside the hub
 *       (motor radius) carry no load</li>
 *   <li>chord ramps linearly from 0 at the axis to the full blade width at one motor
 *       <em>width</em> of radius</li>
 *   <li>geometric blade angle {@code β(r) = atan2(pitch, 2πr)}</li>
 *   <li>the blades are evaluated at {@link #stations} azimuths evenly spread over the disk (a
 *       multiple of the blade count, at least 6) with weight {@code blades / stations}, so the
 *       result is the rotation-averaged force and does not depend on the momentary prop angle
 *       (which the physics does not track; the renderer owns prop angles)</li>
 * </ul>
 */
public final class PropGeometry {

    public static final int SEGMENTS = 5;
    public static final int MIN_STATIONS = 6;

    public final double radius;
    public final double segmentLength;
    public final int blades;
    public final int stations;
    public final double stationWeight;
    /** Per active segment: centre radius, chord and geometric angle. */
    public final double[] segRadius;
    public final double[] segChord;
    public final double[] segBeta;
    /** Per station: cos/sin of the azimuth. */
    public final double[] stationCos;
    public final double[] stationSin;

    public PropGeometry(DroneBuild b) {
        radius = Math.max(1e-3, b.bladeLengthMetres());
        segmentLength = radius / SEGMENTS;
        blades = Math.max(1, b.blades);
        double hub = b.motorWidthMetres() * 0.5;
        double ramp = Math.max(1e-4, b.motorWidthMetres());
        double chord = b.bladeWidthMetres();
        double pitch = Math.max(0.0, b.propPitchMetres());

        int active = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            if ((i + 0.5) / SEGMENTS * radius >= hub) {
                active++;
            }
        }
        segRadius = new double[active];
        segChord = new double[active];
        segBeta = new double[active];
        int k = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            double r = (i + 0.5) / SEGMENTS * radius;
            if (r < hub) {
                continue;
            }
            segRadius[k] = r;
            segChord[k] = chord * Math.min(1.0, r / ramp);
            segBeta[k] = Math.atan2(pitch, 2.0 * Math.PI * r);
            k++;
        }

        stations = blades * (int) Math.ceil((double) MIN_STATIONS / blades);
        stationWeight = (double) blades / stations;
        stationCos = new double[stations];
        stationSin = new double[stations];
        for (int s = 0; s < stations; s++) {
            double a = 2.0 * Math.PI * s / stations;
            stationCos[s] = Math.cos(a);
            stationSin[s] = Math.sin(a);
        }
    }
}
