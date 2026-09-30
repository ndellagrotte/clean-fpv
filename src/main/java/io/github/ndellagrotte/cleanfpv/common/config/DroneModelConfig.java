package io.github.ndellagrotte.cleanfpv.common.config;

/**
 * One "model": every per-model setting (spec §10, §11.2 screens, PLAN §6.1/§6.7). Gson-friendly
 * data (no-arg constructor supplies radio defaults, so keys missing from JSON keep defaults); call
 * {@link #normalize()} after deserialising to repair nulls and out-of-range values.
 *
 * <p>Runtime-only state is <em>not</em> here: the activate-angle switch position is
 * {@code StickState.angleSw}, the effective tilt is computed by the camera rig.
 */
public class DroneModelConfig {

    public static final float FOV_MIN = 30f;
    public static final float FOV_MAX = 150f;
    public static final float MOUSE_GAIN_MIN = 0.01f;
    public static final float MOUSE_GAIN_MAX = 20f;

    /** Display name / key in {@link Settings#models} (≤ 24 chars for user presets, spec §11.2). */
    public String name = "";
    /**
     * Built-in preset ("5 Inch 4S" / "Tiny Whoop", {@link Presets}). Its build is always the built-in values (stored build keys
     * are ignored on load) and it is delete-protected (PLAN §6.7); controller/rate/display keys are
     * persisted like any model.
     */
    public boolean preset;

    // --- Controller --------------------------------------------------------------------------
    public ControllerScheme scheme = ControllerScheme.RADIO;
    /** SDL joystick GUID string of the selected controller ("" = none / first available). */
    public String controllerGuid = "";
    /** SDL joystick name, used for display and as a fallback match. */
    public String controllerName = "";
    public ChannelMap channels = ChannelMap.radioDefaults();

    // --- Rates (spec §4.2) -------------------------------------------------------------------
    public RateTriple rollRates = RateTriple.radio();
    public RateTriple pitchRates = RateTriple.radio();
    public RateTriple yawRates = RateTriple.radio();

    /** Mouse multiplier on top of vanilla-scaled {@code MouseTurnEvent} units (PLAN §6.1). */
    public float mouseGain = 1.0f;

    // --- Display (spec §6.4, §11.2 "Other") ---------------------------------------------------
    /** Keep vanilla's crosshair while armed (radio default off, gamepad on). */
    public boolean showCrosshairs;
    /** Keep vanilla's block outline while armed. */
    public boolean showBlockOutline = true;
    /** Draw the two stick gimbals. */
    public boolean showStickOverlay = true;

    // --- Camera (spec §4.3, §6.1, §6.2) -----------------------------------------------------
    /** Fixed camera tilt (degrees) used while the activate-angle switch is off ("Default Angle"). */
    public float switchlessAngle = 30f;
    /** Diagonal FOV in degrees (slider 30..150); converted to vertical per frame. */
    public float fov = 135f;
    /** Fisheye post effect (first person only, PLAN §10 b). */
    public boolean useFisheye = true;

    // --- Flight / physics (spec §4.4, §5.1, PLAN §6.3) ---------------------------------------
    /** 3D mode: signed throttle. */
    public boolean flightMode3d;
    /** "High Performance Mode": physics per rendered frame instead of per tick. */
    public boolean useRealtimePhysics;
    /** Physics v2 (blade element / motor ODE / battery / thermal) instead of v1 (k·ω²). */
    public boolean highFidelity = true;

    // --- Build (spec §5.5) --------------------------------------------------------------------
    public DroneBuild build = DroneBuild.fiveInch();

    public DroneModelConfig() {}

    /** Radio (Mode 2) model with the "Fast Reset" rates. */
    public static DroneModelConfig radio(String name) {
        DroneModelConfig m = new DroneModelConfig();
        m.name = name;
        return m;
    }

    /** Gamepad model: gamepad channel defaults, "Slow Reset" rates, crosshairs on. */
    public static DroneModelConfig gamepad(String name, int axisCount) {
        DroneModelConfig m = new DroneModelConfig();
        m.name = name;
        m.scheme = ControllerScheme.GAMEPAD;
        m.channels = ChannelMap.gamepadDefaults(axisCount);
        m.applyGamepadRates();
        m.showCrosshairs = true;
        return m;
    }

    /** Keyboard + mouse model (radio rates; channels unused). */
    public static DroneModelConfig keyboard(String name) {
        DroneModelConfig m = new DroneModelConfig();
        m.name = name;
        m.scheme = ControllerScheme.KEYBOARD;
        return m;
    }

    /** "Fast Reset" (spec §11.2): radio rates on all three axes. */
    public void applyRadioRates() {
        rollRates = RateTriple.radio();
        pitchRates = RateTriple.radio();
        yawRates = RateTriple.radio();
    }

    /** "Slow Reset" (spec §11.2): gamepad rates. Does not change {@link #scheme}. */
    public void applyGamepadRates() {
        rollRates = RateTriple.gamepad();
        pitchRates = RateTriple.gamepad();
        yawRates = RateTriple.gamepadYaw();
    }

    /**
     * Switches this model to {@code newScheme}, applying that scheme's rate and display defaults
     * (gamepad: "Slow Reset" rates and crosshairs on; radio / keyboard: "Fast Reset" rates and
     * crosshairs off) when the scheme actually changes. Re-applying the current scheme keeps the
     * pilot's tuned rates. Channel mapping and calibration are left alone (the wizard re-detects
     * them; the controller screen has its own "Defaults" button). Name, preset flag and build are
     * never touched, so running the wizard on a built-in model keeps it that model.
     *
     * @return whether the scheme changed
     */
    public boolean applyScheme(ControllerScheme newScheme) {
        if (newScheme == null || newScheme == scheme) {
            return false;
        }
        scheme = newScheme;
        if (newScheme == ControllerScheme.GAMEPAD) {
            applyGamepadRates();
            showCrosshairs = true;
        } else {
            applyRadioRates();
            showCrosshairs = false;
        }
        return true;
    }

    /** Deep copy. */
    public DroneModelConfig copy() {
        DroneModelConfig m = new DroneModelConfig();
        m.name = name;
        m.preset = preset;
        m.scheme = scheme;
        m.controllerGuid = controllerGuid;
        m.controllerName = controllerName;
        m.channels = channels == null ? null : channels.copy();
        m.rollRates = rollRates == null ? null : rollRates.copy();
        m.pitchRates = pitchRates == null ? null : pitchRates.copy();
        m.yawRates = yawRates == null ? null : yawRates.copy();
        m.mouseGain = mouseGain;
        m.showCrosshairs = showCrosshairs;
        m.showBlockOutline = showBlockOutline;
        m.showStickOverlay = showStickOverlay;
        m.switchlessAngle = switchlessAngle;
        m.fov = fov;
        m.useFisheye = useFisheye;
        m.flightMode3d = flightMode3d;
        m.useRealtimePhysics = useRealtimePhysics;
        m.highFidelity = highFidelity;
        m.build = build == null ? null : build.copy();
        return m;
    }

    /** Deep copy under a new name, never a preset ("New Preset" clones the current model, §10 c). */
    public DroneModelConfig copyAs(String newName) {
        DroneModelConfig m = copy();
        m.name = newName;
        m.preset = false;
        return m;
    }

    /** Repairs nulls and out-of-range values in place (after Gson load); returns {@code this}. */
    public DroneModelConfig normalize() {
        if (name == null) {
            name = "";
        }
        if (scheme == null) {
            scheme = ControllerScheme.RADIO;
        }
        if (controllerGuid == null) {
            controllerGuid = "";
        }
        if (controllerName == null) {
            controllerName = "";
        }
        if (channels == null) {
            channels = scheme == ControllerScheme.GAMEPAD ? ChannelMap.gamepadDefaults(6) : ChannelMap.radioDefaults();
        }
        channels.ensureAxisCount(ChannelMap.DEFAULT_AXIS_SLOTS);
        boolean gp = scheme == ControllerScheme.GAMEPAD;
        if (rollRates == null || !rollRates.isValid()) {
            rollRates = gp ? RateTriple.gamepad() : RateTriple.radio();
        }
        if (pitchRates == null || !pitchRates.isValid()) {
            pitchRates = gp ? RateTriple.gamepad() : RateTriple.radio();
        }
        if (yawRates == null || !yawRates.isValid()) {
            yawRates = gp ? RateTriple.gamepadYaw() : RateTriple.radio();
        }
        if (!Float.isFinite(mouseGain)) {
            mouseGain = 1f;
        }
        mouseGain = Math.clamp(mouseGain, MOUSE_GAIN_MIN, MOUSE_GAIN_MAX);
        if (!Float.isFinite(switchlessAngle)) {
            switchlessAngle = 30f;
        }
        switchlessAngle = Math.clamp(switchlessAngle, -90f, 90f);
        if (!Float.isFinite(fov)) {
            fov = 135f;
        }
        fov = Math.clamp(fov, FOV_MIN, FOV_MAX);
        if (build == null) {
            build = DroneBuild.fiveInch();
        }
        build.sanitize();
        return this;
    }
}
