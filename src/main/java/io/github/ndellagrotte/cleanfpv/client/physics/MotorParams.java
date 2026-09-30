package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;

/**
 * Per-build motor constants for the v2 model (spec §5.4), precomputed once per build. Immutable.
 *
 * <h2>Winding resistance</h2>
 * The turn count is estimated from Kv with a simple magnetic-circuit model: gap flux density
 * {@code B = Br·t_m / (t_m + g)} (remanence 1.48 T, 1 mm magnet, 1.59 mm air gap); one turn on one
 * tooth contributes back-EMF {@code 2·B·L·r} per rad/s; a phase has {@code slots/3} teeth, the line
 * voltage sees {@code √3} of the phase EMF and a 0.9 winding factor. Solving
 * {@code Ke = 1/Kv_rad} gives turns per tooth. Wire length = 2 phases × teeth × turns × the turn
 * perimeter {@code 2·h + 2·toothWidth} (tooth width = stator circumference / slots − 0.5 mm gap);
 * resistance uses 26 Ω per 1000 ft (the spec's constant). Slots 12 (poles 14) for stators ≥ 18 mm,
 * else 9 (poles 12). The default 2207 2400 Kv motor comes out at ≈ 7 turns and ≈ 0.12 Ω.
 *
 * <h2>Inertia</h2>
 * Blades as thin plates about one end, {@code n/12·m·(4R² + w²)} with {@code m = ρ·R·w·2 mm}, plus
 * a rotor bell (1 mm steel shell ρ 8050 + 1 mm magnet ring ρ 7000 over the motor height) at the
 * stator radius + 2 mm.
 *
 * <h2>Thermal</h2>
 * Stator cylinder heat capacity {@code 425.5 J/(kg·K) × 5527 kg/m³ × πr²h}, surface
 * {@code 2πrh + 2πr²}, convection 2000 W/(m²·K), {@code τ = C / (h·A)}.
 *
 * @param kvRad        Kv in rad/s per volt (≥ 50 RPM/V to keep the ODE finite)
 * @param noLoadOmega  ω clamp {@code Kv_rad · cells · 4.2} (rad/s)
 * @param cells        series cell count
 * @param coldOhms     winding resistance at ambient (Ω, line to line)
 * @param inertia      rotor + prop moment of inertia (kg·m²)
 * @param heatCapacity winding/stator heat capacity (J/K)
 * @param coolingTau   thermal time constant (s)
 */
public record MotorParams(double kvRad, double noLoadOmega, int cells, double coldOhms, double inertia,
                          double heatCapacity, double coolingTau) {

    /** Kv floor (RPM/V), shared with the server's ω limit via {@link DroneBuild#kvRad()}. */
    public static final double MIN_KV = DroneBuild.MIN_KV;
    public static final double REMANENCE_T = 1.48;
    public static final double MAGNET_THICKNESS_M = 0.001;
    public static final double AIR_GAP_M = 0.00159;
    public static final double TOOTH_GAP_M = 0.0005;
    public static final double WINDING_FACTOR = 0.9;
    public static final double OHMS_PER_KFT = 26.0;
    public static final double INCH_PER_METRE = 39.3701;
    public static final double STEEL_DENSITY = 8050.0;
    public static final double MAGNET_DENSITY = 7000.0;
    public static final double SHELL_THICKNESS_M = 0.001;
    public static final double BELL_CLEARANCE_M = 0.002;
    public static final double STATOR_SPECIFIC_HEAT = 425.5;
    public static final double CONVECTION = 2000.0;
    public static final double MIN_OHMS = 1e-3;
    public static final double MIN_INERTIA = 1e-9;

    public static MotorParams of(DroneBuild b) {
        double kvRad = b.kvRad(); // floored at DroneBuild.MIN_KV, same as TransformRules.omegaLimit
        int cells = Math.max(1, b.batteryCells);
        double noLoad = kvRad * cells * DroneBuild.MAX_CELL_VOLTAGE;

        double statorR = b.motorWidthMetres() * 0.5;
        double h = b.motorHeightMetres();
        int slots = b.motorWidth >= 18f ? 12 : 9;
        int teethPerPhase = slots / 3;
        double gapB = REMANENCE_T * MAGNET_THICKNESS_M / (MAGNET_THICKNESS_M + AIR_GAP_M);
        double emfPerTurn = 2.0 * gapB * h * statorR * teethPerPhase * Math.sqrt(3.0) * WINDING_FACTOR;
        double turns = Math.max(1.0, 1.0 / (kvRad * Math.max(emfPerTurn, 1e-12)));
        double toothWidth = Math.max(TOOTH_GAP_M, 2.0 * Math.PI * statorR / slots - TOOTH_GAP_M);
        double wireMetres = 2.0 * teethPerPhase * turns * (2.0 * h + 2.0 * toothWidth);
        double ohms = Math.max(MIN_OHMS, wireMetres * INCH_PER_METRE / 12.0 / 1000.0 * OHMS_PER_KFT);

        double bladeR = b.bladeLengthMetres();
        double chord = b.bladeWidthMetres();
        double bladeMass = MassModel.PROP_DENSITY * bladeR * chord * MassModel.BLADE_THICKNESS_M;
        double bladeInertia = b.blades / 12.0 * bladeMass * (4.0 * bladeR * bladeR + chord * chord);
        double bellR = statorR + BELL_CLEARANCE_M;
        double shellMass = (STEEL_DENSITY + MAGNET_DENSITY) * 2.0 * Math.PI * bellR * SHELL_THICKNESS_M * h;
        double inertia = Math.max(MIN_INERTIA, bladeInertia + shellMass * bellR * bellR);

        double heatCap = STATOR_SPECIFIC_HEAT * MassModel.STATOR_DENSITY * Math.PI * statorR * statorR * h;
        double area = 2.0 * Math.PI * statorR * h + 2.0 * Math.PI * statorR * statorR;
        double tau = heatCap / (CONVECTION * area);

        return new MotorParams(kvRad, noLoad, cells, ohms, inertia, heatCap, tau);
    }

    /** Electromechanical time constant {@code J·R·Kv_rad²} (s): how fast the motor spools unloaded. */
    public double spoolTimeConstant(double ohms) {
        return inertia * ohms * kvRad * kvRad;
    }
}
