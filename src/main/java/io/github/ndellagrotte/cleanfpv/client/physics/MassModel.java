package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;

/**
 * All-up mass from the build (spec §5.4 "Mass"), matching the original's effective mass.
 *
 * <p>The computed component mass {@code V} is: 40 g base (flight controller etc.) + 4 stators
 * (solid cylinders) + 2 square frame plates ({@code frameWidth²} × 5 mm) + standoffs (5 mm square ×
 * ({@code frameHeight} − 10 mm), 4 or 8) + centre stack block (24 or 34 mm square × 15 mm) + battery
 * (regression in cells and mAh, ≥ 10 g) + pro camera (hero 126 g / other 74 g, only with eight
 * standoffs). The flown mass is the user's slider value clamped to {@code [V, 2V]}, or
 * {@code 1.5·V} when unset ({@code mass <= 0}). Default 5" build: V ≈ 409 g, flown ≈ 613 g; Tiny
 * Whoop: V ≈ 73 g, flown ≈ 110 g (spec §5.4).
 *
 * <p>Decision (spec §13.1 "mass model"): the three terms the original loses to unit bugs (prop
 * blades, split camera, arms) stay at zero. Weighing them adds ≈ 130 g to the 5" (≈ 21 %), and
 * the propulsion is tuned to the original's thrust-to-weight at the original's mass, so counting
 * them would make every build fly heavier than the reference. Pure, stateless.
 */
public final class MassModel {

    public static final double BASE_GRAMS = 40.0;
    public static final double STATOR_DENSITY = 5527.0;
    public static final double PLATE_DENSITY = 1550.0;
    public static final double PLATE_THICKNESS_M = 0.005;
    public static final double STANDOFF_DENSITY = 2700.0;
    public static final double STANDOFF_SIDE_M = 0.005;
    public static final double STANDOFF_CLEARANCE_M = 0.010;
    public static final double STACK_DENSITY = 750.0;
    public static final double STACK_SIDE_SMALL_M = 0.024;
    public static final double STACK_SIDE_LARGE_M = 0.034;
    public static final double STACK_HEIGHT_M = 0.015;
    public static final double HERO_CAM_GRAMS = 126.0;
    public static final double OTHER_PRO_CAM_GRAMS = 74.0;
    /** Prop plastic density: used for the rotor inertia only (not counted in the mass, see above). */
    public static final double PROP_DENSITY = 1220.0;
    /** Blade thickness, shared with the blade-element drag area (spec §5.3). */
    public static final double BLADE_THICKNESS_M = 0.002;
    public static final double MIN_BATTERY_GRAMS = 10.0;
    public static final double DEFAULT_MASS_FACTOR = 1.5;
    public static final double MAX_MASS_FACTOR = 2.0;

    private MassModel() {}

    /** Computed component mass {@code V} in grams (see class doc). */
    public static double componentMassGrams(DroneBuild b) {
        double motorR = b.motorWidthMetres() * 0.5;
        double motorH = b.motorHeightMetres();
        double stators = 4.0 * STATOR_DENSITY * Math.PI * motorR * motorR * motorH;

        double frameW = b.frameWidth * DroneBuild.MM_TO_METRE;
        double plates = 2.0 * PLATE_DENSITY * frameW * frameW * PLATE_THICKNESS_M;

        boolean eight = b.hasEightStandoffs();
        double standoffLen = Math.max(0.0, b.frameHeight * DroneBuild.MM_TO_METRE - STANDOFF_CLEARANCE_M);
        double standoffs = (eight ? 8 : 4) * STANDOFF_DENSITY * STANDOFF_SIDE_M * STANDOFF_SIDE_M * standoffLen;

        double stackSide = eight ? STACK_SIDE_LARGE_M : STACK_SIDE_SMALL_M;
        double stack = STACK_DENSITY * stackSide * stackSide * STACK_HEIGHT_M;

        double kg = stators + plates + standoffs + stack;
        double grams = BASE_GRAMS + kg * 1000.0 + batteryGrams(b.batteryCells, b.batteryMah);
        if (b.showProCam && eight) {
            grams += b.heroCam ? HERO_CAM_GRAMS : OTHER_PRO_CAM_GRAMS;
        }
        return grams;
    }

    /** Battery mass regression (grams, at least 10 g). */
    public static double batteryGrams(int cells, int mah) {
        double c = cells;
        double m = mah;
        double g = -19.69 + 9.12 * c + 0.02 * m - 0.04 * c * c + 0.02 * c * m;
        return Math.max(MIN_BATTERY_GRAMS, g);
    }

    /**
     * Arm length so the props clear each other at 45°: {@code (bladeLength + frameWidth/2 + 10 mm) /
     * cos 45°} (metres). Also useful to the renderer.
     */
    public static double armLengthMetres(DroneBuild b) {
        return (b.bladeLengthMetres() + b.frameWidth * DroneBuild.MM_TO_METRE * 0.5 + 0.01) / Math.cos(Math.PI / 4.0);
    }

    /** Flown mass in grams: slider clamped to {@code [V, 2V]}, or {@code 1.5·V} when unset. */
    public static double flownMassGrams(DroneBuild b) {
        double v = componentMassGrams(b);
        if (!(b.mass > 0f) || !Float.isFinite(b.mass)) {
            return DEFAULT_MASS_FACTOR * v;
        }
        return Math.clamp(b.mass, v, MAX_MASS_FACTOR * v);
    }

    /** Flown mass in kilograms. */
    public static double flownMassKg(DroneBuild b) {
        return flownMassGrams(b) / 1000.0;
    }
}
