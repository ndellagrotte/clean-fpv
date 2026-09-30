package io.github.ndellagrotte.cleanfpv.common.config;

import java.util.List;

/**
 * The two built-in, delete-protected models (spec §5.5/§10, PLAN §6.7): {@value #FIVE_INCH} (the
 * default on a fresh install, {@link DroneBuild#fiveInch()}) and {@value #TINY_WHOOP}
 * ({@link DroneBuild#tinyWhoop()}). A preset is recognised by its name: its build is always the
 * built-in values (stored build keys are ignored on load and never written), while its controller,
 * rate, display and camera settings are persisted like any other model. Presets cannot be deleted
 * or renamed; "New" in the model list clones the current model (PLAN §10 c), so a user who wants a
 * different build starts from a preset and edits the copy.
 *
 * <p>The controller scheme (radio / gamepad / keyboard) is an ordinary per-model setting, not part
 * of a preset's identity: the setup wizard applies the chosen scheme's defaults onto the current
 * model ({@link DroneModelConfig#applyScheme}). Both presets start with radio defaults.
 */
public final class Presets {

    public static final String FIVE_INCH = "5 Inch 4S";
    public static final String TINY_WHOOP = "Tiny Whoop";

    /** Axis count assumed for the fixed gamepad channel table (typical SDL pad: 6). */
    public static final int DEFAULT_GAMEPAD_AXES = 6;

    /** Canonical (display) order. */
    private static final List<String> NAMES = List.of(FIVE_INCH, TINY_WHOOP);

    /**
     * Preset names of settings schema 1 (the first Clean FPV builds), which split the 5-inch build
     * into one preset per controller scheme. {@link SettingsStore} migrates them onto
     * {@link #FIVE_INCH}; they are not reserved names any more.
     */
    public static final List<String> LEGACY_FIVE_INCH = List.of("5in Radio", "5in Gamepad", "5in Keyboard");

    /** Every preset name of settings schema 1 (the three legacy 5-inch presets plus Tiny Whoop). */
    public static final List<String> LEGACY_V1_NAMES =
            List.of("5in Radio", "5in Gamepad", "5in Keyboard", TINY_WHOOP);

    private Presets() {}

    /** Preset names in display order. */
    public static List<String> names() {
        return NAMES;
    }

    public static boolean isPreset(String name) {
        return name != null && NAMES.contains(name);
    }

    /** The model selected on a fresh install. */
    public static String defaultModel() {
        return FIVE_INCH;
    }

    /** A fresh copy of the preset's built-in build, or {@code null} for a non-preset name. */
    public static DroneBuild builtInBuild(String name) {
        if (!isPreset(name)) {
            return null;
        }
        return TINY_WHOOP.equals(name) ? DroneBuild.tinyWhoop() : DroneBuild.fiveInch();
    }

    /** A fresh preset model with all (radio) defaults, or {@code null} for a non-preset name. */
    public static DroneModelConfig create(String name) {
        if (!isPreset(name)) {
            return null;
        }
        DroneModelConfig m = DroneModelConfig.radio(name);
        m.preset = true;
        m.build = builtInBuild(name);
        return m.normalize();
    }
}
