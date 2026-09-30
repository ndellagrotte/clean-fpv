package io.github.ndellagrotte.cleanfpv.client.physics;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * Body (frame) aerodynamic drag, shared by both thrust models (spec §5.2): quadratic drag on a
 * characteristic disk whose radius is that of a sphere of the drone's mass at 1900 kg/m³.
 * {@code r = cbrt(3m / (1900·4π))}, {@code A = πr²}, {@code k = ρ·A·Cd / 2}, force {@code −k·|v|·v}.
 * Pure, stateless.
 */
public final class DragModel {

    public static final double AIR_DENSITY = 1.225;
    public static final double BODY_CD = 1.05;
    /** Density of the equivalent sphere that sizes the drag disk (kg/m³). */
    public static final double DISK_DENSITY = 1900.0;

    private DragModel() {}

    /** Quadratic drag factor {@code k} (N per (m/s)²) for a mass in kilograms. */
    public static double dragFactor(double massKg) {
        if (!(massKg > 0.0) || !Double.isFinite(massKg)) {
            return 0.0;
        }
        double r = Math.cbrt(3.0 * massKg / (DISK_DENSITY * 4.0 * Math.PI));
        double area = Math.PI * r * r;
        return AIR_DENSITY * area * BODY_CD * 0.5;
    }

    /** Adds the body drag {@code −k·|v|·v} for {@code velocity} to {@code forceOut}. */
    public static Vector3d addDrag(Vector3dc velocity, double factor, Vector3d forceOut) {
        double speed = velocity.length();
        if (speed > 0.0 && Double.isFinite(speed)) {
            double s = -factor * speed;
            forceOut.add(velocity.x() * s, velocity.y() * s, velocity.z() * s);
        }
        return forceOut;
    }
}
