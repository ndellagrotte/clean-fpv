package io.github.ndellagrotte.cleanfpv.common.config;

import io.netty.buffer.ByteBuf;

/**
 * The physical build of one drone (spec §5.5): motors, battery, props, frame, accessories, colours
 * and camera tilt. Plain mutable data: Gson-friendly (public no-arg constructor, non-final fields,
 * field names are the JSON keys), with a fixed binary wire form and a range-clamping
 * {@link #sanitize()}.
 *
 * <h2>Units (persisted, in memory and on the wire alike)</h2>
 * motorKv RPM/V; motorWidth/Height, bladeWidth, frame*, arm*, antennaLength millimetres;
 * propDiameter/propPitch inches; red/green/blue 0..1; cameraAngle degrees; mass grams. The spec's
 * wire form used metres; this mod sends the in-memory units unchanged (no lossy conversion) and
 * offers metre helpers ({@link #bladeLengthMetres()} etc.) for physics and rendering.
 *
 * <h2>Wire form (22 fields, PLAN §6.3 / §10 d)</h2>
 * The spec's 20 fields in spec order, then {@code motorKv} and {@code propPitch}: 9 floats (red,
 * green, blue, cameraAngle, frameWidth, frameHeight, frameLength, motorWidth, motorHeight), 3 ints
 * (batteryCells, batteryMah, blades), 5 floats (propDiameter, bladeWidth, armWidth, armThickness,
 * antennaLength), 3 booleans (showProCam, heroCam, toothpick), 2 floats (motorKv, propPitch).
 * {@link #mass} is local-only (physics) and is not sent. {@link #read(ByteBuf)} sanitizes.
 *
 * <h2>cameraAngle</h2>
 * The <em>effective</em> camera tilt the owner currently flies with (flight code copies the live
 * tilt in before sending a Build packet, so other players see the tilt move). The user's fixed
 * "Default Angle" setting is {@link DroneModelConfig#switchlessAngle}.
 */
public class DroneBuild {

    /** RPM → rad/s. */
    public static final double RPM_TO_RAD_S = 2.0 * Math.PI / 60.0;
    /** Fully charged cell voltage (spec §5.4); ω clamp = Kv_rad · cells · 4.2. */
    public static final double MAX_CELL_VOLTAGE = 4.2;
    public static final double INCH_TO_METRE = 0.0254;
    public static final double MM_TO_METRE = 0.001;
    /**
     * Lowest motor Kv (RPM/V) anything uses: {@link #sanitize()} clamps to it and {@link #kvRad()}
     * floors at it, so the client motor model and the server's ω limit agree for every build.
     */
    public static final float MIN_KV = 50f;

    /** Motor velocity constant, RPM/V ({@link #MIN_KV}..20000). */
    public float motorKv = 2400f;
    /** Stator diameter, mm (>= 5). */
    public float motorWidth = 22f;
    /** Stator height, mm (>= 2). */
    public float motorHeight = 7f;
    /** Series cell count (1..12). */
    public int batteryCells = 4;
    /** Capacity, mAh (>= 300). */
    public int batteryMah = 1300;
    /** Prop diameter, inches (GUI range 1.5748..13; sanitize accepts 1.5 for the Tiny Whoop preset). */
    public float propDiameter = 5f;
    /** Prop pitch, inches (0..20). */
    public float propPitch = 4.6f;
    /** Blade count (2..16; only 2..5 have dedicated disc textures). */
    public int blades = 3;
    /** Blade chord, mm (6..motorWidth). Spec JSON key "propWidth". */
    public float bladeWidth = 11f;
    /** Frame width, mm (25..5000). */
    public float frameWidth = 30f;
    /** Frame height, mm (25..5000). */
    public float frameHeight = 30f;
    /** Frame length, mm (25..5000); >= 80 mm means 8 standoffs. */
    public float frameLength = 200f;
    /** Arm width, mm (5..500), visual only. */
    public float armWidth = 15f;
    /** Arm thickness, mm (5..500), visual only. */
    public float armThickness = 5f;
    /** Antenna length, mm (5..200), visual only. */
    public float antennaLength = 17f;
    /** Build colour (standoffs, hubs, prop discs), 0..1. */
    public float red = 1f;
    public float green = 1f;
    public float blue = 1f;
    /** Effective camera tilt, degrees (see class doc). */
    public float cameraAngle = 30f;
    /** Draw (and weigh) the "pro" camera body. */
    public boolean showProCam = true;
    /** Pro camera is the heavier "hero" style (126 g) rather than the other (74 g). */
    public boolean heroCam = true;
    /** Persisted and sent; no behavioural effect (spec §5.5). */
    public boolean toothpick = false;
    /**
     * All-up mass in grams, clamped by the mass model to [V, 2V] (V = computed component mass).
     * {@code <= 0} means "default" = 1.5·V, resolved by the physics mass model. Local only.
     */
    public float mass = 0f;

    public DroneBuild() {}

    /** Built-in "5 Inch 4S" preset (the field defaults). */
    public static DroneBuild fiveInch() {
        return new DroneBuild();
    }

    /** Built-in "Tiny Whoop" preset (spec §5.5). */
    public static DroneBuild tinyWhoop() {
        DroneBuild b = new DroneBuild();
        b.motorKv = 13000f;
        b.motorWidth = 8f;
        b.motorHeight = 2.5f;
        b.batteryCells = 1;
        b.batteryMah = 450;
        b.propDiameter = 1.5f;
        b.propPitch = 1.5f;
        b.blades = 4;
        b.bladeWidth = 8f;
        b.frameWidth = 25f;
        b.frameHeight = 25f;
        b.frameLength = 25f;
        b.armWidth = 5f;
        b.armThickness = 5f;
        b.showProCam = false;
        b.heroCam = false;
        b.toothpick = true;
        b.mass = 0f;
        return b;
    }

    public DroneBuild copy() {
        DroneBuild b = new DroneBuild();
        b.copyFrom(this);
        return b;
    }

    public void copyFrom(DroneBuild o) {
        motorKv = o.motorKv;
        motorWidth = o.motorWidth;
        motorHeight = o.motorHeight;
        batteryCells = o.batteryCells;
        batteryMah = o.batteryMah;
        propDiameter = o.propDiameter;
        propPitch = o.propPitch;
        blades = o.blades;
        bladeWidth = o.bladeWidth;
        frameWidth = o.frameWidth;
        frameHeight = o.frameHeight;
        frameLength = o.frameLength;
        armWidth = o.armWidth;
        armThickness = o.armThickness;
        antennaLength = o.antennaLength;
        red = o.red;
        green = o.green;
        blue = o.blue;
        cameraAngle = o.cameraAngle;
        showProCam = o.showProCam;
        heroCam = o.heroCam;
        toothpick = o.toothpick;
        mass = o.mass;
    }

    // ---------------------------------------------------------------------------------------------
    // Derived values

    /** Motor Kv in rad/s per volt, floored at {@link #MIN_KV} (non-finite → the default Kv). */
    public double kvRad() {
        double kv = Float.isFinite(motorKv) ? Math.max(MIN_KV, motorKv) : DEFAULTS.motorKv;
        return kv * RPM_TO_RAD_S;
    }

    /** No-load angular speed {@code Kv_rad · cells · 4.2} (rad/s): the ω clamp, audio ωmax, server check. */
    public double noLoadOmega() {
        return kvRad() * Math.max(1, batteryCells) * MAX_CELL_VOLTAGE;
    }

    /** Blade length (prop radius) in metres: {@code propDiameter · 0.5 · 0.0254}. */
    public double bladeLengthMetres() {
        return propDiameter * 0.5 * INCH_TO_METRE;
    }

    public double propPitchMetres() {
        return propPitch * INCH_TO_METRE;
    }

    public double motorWidthMetres() {
        return motorWidth * MM_TO_METRE;
    }

    public double motorHeightMetres() {
        return motorHeight * MM_TO_METRE;
    }

    public double bladeWidthMetres() {
        return bladeWidth * MM_TO_METRE;
    }

    /** Eight standoffs (and the pro cam is allowed) when the frame is at least 80 mm long. */
    public boolean hasEightStandoffs() {
        return frameLength >= 80f;
    }

    // ---------------------------------------------------------------------------------------------
    // Validation

    /**
     * Clamps every field into its spec range in place (non-finite → default) and returns
     * {@code this}. Upper bounds the spec leaves open are capped generously (motor 100 mm,
     * 100 000 mAh, 100 000 g, camera angle ±90°).
     */
    public DroneBuild sanitize() {
        DroneBuild d = DEFAULTS;
        motorKv = clamp(motorKv, MIN_KV, 20000f, d.motorKv);
        motorWidth = clamp(motorWidth, 5f, 100f, d.motorWidth);
        motorHeight = clamp(motorHeight, 2f, 100f, d.motorHeight);
        batteryCells = Math.clamp(batteryCells, 1, 12);
        batteryMah = Math.clamp(batteryMah, 300, 100000);
        propDiameter = clamp(propDiameter, 1.5f, 13f, d.propDiameter);
        propPitch = clamp(propPitch, 0f, 20f, d.propPitch);
        blades = Math.clamp(blades, 2, 16);
        bladeWidth = clamp(bladeWidth, 6f, Math.max(6f, motorWidth), d.bladeWidth);
        frameWidth = clamp(frameWidth, 25f, 5000f, d.frameWidth);
        frameHeight = clamp(frameHeight, 25f, 5000f, d.frameHeight);
        frameLength = clamp(frameLength, 25f, 5000f, d.frameLength);
        armWidth = clamp(armWidth, 5f, 500f, d.armWidth);
        armThickness = clamp(armThickness, 5f, 500f, d.armThickness);
        antennaLength = clamp(antennaLength, 5f, 200f, d.antennaLength);
        red = clamp(red, 0f, 1f, 1f);
        green = clamp(green, 0f, 1f, 1f);
        blue = clamp(blue, 0f, 1f, 1f);
        cameraAngle = clamp(cameraAngle, -90f, 90f, d.cameraAngle);
        mass = clamp(mass, 0f, 100000f, 0f);
        return this;
    }

    private static final DroneBuild DEFAULTS = new DroneBuild();

    private static float clamp(float v, float min, float max, float fallback) {
        if (!Float.isFinite(v)) {
            return fallback;
        }
        return Math.clamp(v, min, max);
    }

    // ---------------------------------------------------------------------------------------------
    // Wire form

    public void write(ByteBuf buf) {
        buf.writeFloat(red);
        buf.writeFloat(green);
        buf.writeFloat(blue);
        buf.writeFloat(cameraAngle);
        buf.writeFloat(frameWidth);
        buf.writeFloat(frameHeight);
        buf.writeFloat(frameLength);
        buf.writeFloat(motorWidth);
        buf.writeFloat(motorHeight);
        buf.writeInt(batteryCells);
        buf.writeInt(batteryMah);
        buf.writeInt(blades);
        buf.writeFloat(propDiameter);
        buf.writeFloat(bladeWidth);
        buf.writeFloat(armWidth);
        buf.writeFloat(armThickness);
        buf.writeFloat(antennaLength);
        buf.writeBoolean(showProCam);
        buf.writeBoolean(heroCam);
        buf.writeBoolean(toothpick);
        buf.writeFloat(motorKv);
        buf.writeFloat(propPitch);
    }

    /** Reads the 22-field wire form into a new, sanitized build ({@link #mass} = 0, i.e. default). */
    public static DroneBuild read(ByteBuf buf) {
        DroneBuild b = new DroneBuild();
        b.red = buf.readFloat();
        b.green = buf.readFloat();
        b.blue = buf.readFloat();
        b.cameraAngle = buf.readFloat();
        b.frameWidth = buf.readFloat();
        b.frameHeight = buf.readFloat();
        b.frameLength = buf.readFloat();
        b.motorWidth = buf.readFloat();
        b.motorHeight = buf.readFloat();
        b.batteryCells = buf.readInt();
        b.batteryMah = buf.readInt();
        b.blades = buf.readInt();
        b.propDiameter = buf.readFloat();
        b.bladeWidth = buf.readFloat();
        b.armWidth = buf.readFloat();
        b.armThickness = buf.readFloat();
        b.antennaLength = buf.readFloat();
        b.showProCam = buf.readBoolean();
        b.heroCam = buf.readBoolean();
        b.toothpick = buf.readBoolean();
        b.motorKv = buf.readFloat();
        b.propPitch = buf.readFloat();
        b.mass = 0f;
        return b.sanitize();
    }

    /** Field-wise equality of the 22 wire fields plus {@link #mass}. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DroneBuild b)) {
            return false;
        }
        return Float.compare(motorKv, b.motorKv) == 0
                && Float.compare(motorWidth, b.motorWidth) == 0
                && Float.compare(motorHeight, b.motorHeight) == 0
                && batteryCells == b.batteryCells
                && batteryMah == b.batteryMah
                && Float.compare(propDiameter, b.propDiameter) == 0
                && Float.compare(propPitch, b.propPitch) == 0
                && blades == b.blades
                && Float.compare(bladeWidth, b.bladeWidth) == 0
                && Float.compare(frameWidth, b.frameWidth) == 0
                && Float.compare(frameHeight, b.frameHeight) == 0
                && Float.compare(frameLength, b.frameLength) == 0
                && Float.compare(armWidth, b.armWidth) == 0
                && Float.compare(armThickness, b.armThickness) == 0
                && Float.compare(antennaLength, b.antennaLength) == 0
                && Float.compare(red, b.red) == 0
                && Float.compare(green, b.green) == 0
                && Float.compare(blue, b.blue) == 0
                && Float.compare(cameraAngle, b.cameraAngle) == 0
                && showProCam == b.showProCam
                && heroCam == b.heroCam
                && toothpick == b.toothpick
                && Float.compare(mass, b.mass) == 0;
    }

    @Override
    public int hashCode() {
        int h = Float.hashCode(motorKv);
        h = 31 * h + Float.hashCode(motorWidth);
        h = 31 * h + Float.hashCode(motorHeight);
        h = 31 * h + batteryCells;
        h = 31 * h + batteryMah;
        h = 31 * h + Float.hashCode(propDiameter);
        h = 31 * h + Float.hashCode(propPitch);
        h = 31 * h + blades;
        h = 31 * h + Float.hashCode(bladeWidth);
        h = 31 * h + Float.hashCode(frameWidth);
        h = 31 * h + Float.hashCode(frameHeight);
        h = 31 * h + Float.hashCode(frameLength);
        h = 31 * h + Float.hashCode(armWidth);
        h = 31 * h + Float.hashCode(armThickness);
        h = 31 * h + Float.hashCode(antennaLength);
        h = 31 * h + Float.hashCode(red);
        h = 31 * h + Float.hashCode(green);
        h = 31 * h + Float.hashCode(blue);
        h = 31 * h + Float.hashCode(cameraAngle);
        h = 31 * h + Boolean.hashCode(showProCam);
        h = 31 * h + Boolean.hashCode(heroCam);
        h = 31 * h + Boolean.hashCode(toothpick);
        h = 31 * h + Float.hashCode(mass);
        return h;
    }
}
