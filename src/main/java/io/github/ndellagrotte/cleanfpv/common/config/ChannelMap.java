package io.github.ndellagrotte.cleanfpv.common.config;

import java.util.Arrays;

/**
 * Joystick channel mapping + calibration for one model (spec §3.1, PLAN §6.1). Gson-friendly data
 * with the pure read helpers shared by the input layer (B) and the wizard/settings GUI (E).
 *
 * <h2>Axes</h2>
 * Five axis channels hold indices into the raw SDL axis array. Raw values are calibrated per
 * <em>physical axis</em> with {@link #calMin}/{@link #calMax} (spec formula
 * {@code clamp((v−min)/(max−min),0,1)·2−1}, degenerate range {@code < 1e-4} → [−1,1]), then the
 * <em>channel's</em> invert flag negates.
 *
 * <h2>Switches</h2>
 * Three switch channels: index {@code >= 0} is a button index (pressed = down); index {@code < 0}
 * is a <em>virtual button</em> reading axis {@code axisCount + index} (so −1 = last axis), pressed
 * when the calibrated value is {@code > 0.1}. The channel's invert flag flips the result. Indices
 * out of range read as "not pressed" (never throw). All eight invert flags and the calibration
 * of every axis are persisted (fixes spec §10).
 */
public class ChannelMap {

    /** Virtual-button threshold on a calibrated axis value. */
    public static final float VIRTUAL_BUTTON_THRESHOLD = 0.1f;
    /** Degenerate calibration range (spec §3.1). */
    public static final float MIN_CAL_RANGE = 1e-4f;
    /** Default number of calibrated axes (spec: 8 pairs). Grown on demand by {@link #ensureAxisCount}. */
    public static final int DEFAULT_AXIS_SLOTS = 8;

    public enum Axis { THROTTLE, ROLL, PITCH, YAW, ANGLE }

    public enum Switch { ARM, ANGLE, RIGHT_CLICK }

    public int throttleAxis = 0;
    public int rollAxis = 1;
    public int pitchAxis = 2;
    public int yawAxis = 3;
    public int angleAxis = 4;

    public int armSwitch = 0;
    public int angleSwitch = 1;
    public int rightClickSwitch = 2;

    public boolean invertThrottle;
    public boolean invertRoll;
    public boolean invertPitch;
    public boolean invertYaw;
    public boolean invertAngle;
    public boolean invertArm;
    public boolean invertAngleSwitch;
    public boolean invertRightClick;

    /** Per physical axis calibration minimum (raw units). */
    public float[] calMin = filled(DEFAULT_AXIS_SLOTS, -1f);
    /** Per physical axis calibration maximum (raw units). */
    public float[] calMax = filled(DEFAULT_AXIS_SLOTS, 1f);

    public ChannelMap() {}

    /** Radio (Mode 2) defaults: axes 0..4, buttons 0/1/2, nothing inverted (spec §3.1). */
    public static ChannelMap radioDefaults() {
        return new ChannelMap();
    }

    /**
     * Gamepad defaults (spec §3.1): axes thr 1 / roll 2 / pitch 3 / yaw 0 / angle 4, throttle and
     * pitch inverted, switches at the spec's {@code 10−axisCount / 8−axisCount / 11−axisCount}
     * interpreted with this class's index convention. The input implementer may retune these once
     * real SDL devices have been tested.
     */
    public static ChannelMap gamepadDefaults(int axisCount) {
        ChannelMap m = new ChannelMap();
        m.throttleAxis = 1;
        m.rollAxis = 2;
        m.pitchAxis = 3;
        m.yawAxis = 0;
        m.angleAxis = 4;
        m.armSwitch = 10 - axisCount;
        m.angleSwitch = 8 - axisCount;
        m.rightClickSwitch = 11 - axisCount;
        m.invertThrottle = true;
        m.invertPitch = true;
        return m;
    }

    // ---------------------------------------------------------------------------------------------
    // Channel accessors (for GUI rows / wizard)

    public int axisIndex(Axis a) {
        return switch (a) {
            case THROTTLE -> throttleAxis;
            case ROLL -> rollAxis;
            case PITCH -> pitchAxis;
            case YAW -> yawAxis;
            case ANGLE -> angleAxis;
        };
    }

    public void setAxisIndex(Axis a, int index) {
        switch (a) {
            case THROTTLE -> throttleAxis = index;
            case ROLL -> rollAxis = index;
            case PITCH -> pitchAxis = index;
            case YAW -> yawAxis = index;
            case ANGLE -> angleAxis = index;
        }
    }

    public boolean isInverted(Axis a) {
        return switch (a) {
            case THROTTLE -> invertThrottle;
            case ROLL -> invertRoll;
            case PITCH -> invertPitch;
            case YAW -> invertYaw;
            case ANGLE -> invertAngle;
        };
    }

    public void setInverted(Axis a, boolean inverted) {
        switch (a) {
            case THROTTLE -> invertThrottle = inverted;
            case ROLL -> invertRoll = inverted;
            case PITCH -> invertPitch = inverted;
            case YAW -> invertYaw = inverted;
            case ANGLE -> invertAngle = inverted;
        }
    }

    public int switchIndex(Switch s) {
        return switch (s) {
            case ARM -> armSwitch;
            case ANGLE -> angleSwitch;
            case RIGHT_CLICK -> rightClickSwitch;
        };
    }

    public void setSwitchIndex(Switch s, int index) {
        switch (s) {
            case ARM -> armSwitch = index;
            case ANGLE -> angleSwitch = index;
            case RIGHT_CLICK -> rightClickSwitch = index;
        }
    }

    public boolean isInverted(Switch s) {
        return switch (s) {
            case ARM -> invertArm;
            case ANGLE -> invertAngleSwitch;
            case RIGHT_CLICK -> invertRightClick;
        };
    }

    public void setInverted(Switch s, boolean inverted) {
        switch (s) {
            case ARM -> invertArm = inverted;
            case ANGLE -> invertAngleSwitch = inverted;
            case RIGHT_CLICK -> invertRightClick = inverted;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reading

    /** Grows the calibration arrays (new slots [−1, 1]) so {@code axisCount} axes are addressable. */
    public void ensureAxisCount(int axisCount) {
        if (calMin == null || calMax == null || calMin.length != calMax.length) {
            resetCalibration(Math.max(axisCount, DEFAULT_AXIS_SLOTS));
            return;
        }
        if (calMin.length < axisCount) {
            int old = calMin.length;
            calMin = Arrays.copyOf(calMin, axisCount);
            calMax = Arrays.copyOf(calMax, axisCount);
            for (int i = old; i < axisCount; i++) {
                calMin[i] = -1f;
                calMax[i] = 1f;
            }
        }
    }

    /** Resets calibration of {@code slots} axes to [−1, 1]. */
    public void resetCalibration(int slots) {
        calMin = filled(slots, -1f);
        calMax = filled(slots, 1f);
    }

    /** Spec §3.1 calibration of a raw value on physical axis {@code axis}; unknown axes use [−1, 1]. */
    public float calibrate(int axis, float raw) {
        float min = -1f;
        float max = 1f;
        if (calMin != null && calMax != null && axis >= 0 && axis < calMin.length && axis < calMax.length) {
            min = calMin[axis];
            max = calMax[axis];
        }
        if (!Float.isFinite(min) || !Float.isFinite(max) || max - min < MIN_CAL_RANGE) {
            min = -1f;
            max = 1f;
        }
        if (!Float.isFinite(raw)) {
            return 0f;
        }
        float t = Math.clamp((raw - min) / (max - min), 0f, 1f);
        return t * 2f - 1f;
    }

    /** Calibrated, inverted value of an axis channel in [−1, 1]; 0 when the index is out of range. */
    public float readAxis(Axis channel, float[] rawAxes) {
        int idx = axisIndex(channel);
        if (rawAxes == null || idx < 0 || idx >= rawAxes.length) {
            return 0f;
        }
        float v = calibrate(idx, rawAxes[idx]);
        return isInverted(channel) ? -v : v;
    }

    /** State of a switch channel (see class doc); out-of-range indices read as released. */
    public boolean readSwitch(Switch channel, float[] rawAxes, boolean[] buttons) {
        boolean pressed = rawSwitch(switchIndex(channel), rawAxes, buttons);
        return isInverted(channel) != pressed;
    }

    /** Un-inverted state of a switch index (button or virtual button). */
    public boolean rawSwitch(int index, float[] rawAxes, boolean[] buttons) {
        if (index >= 0) {
            return buttons != null && index < buttons.length && buttons[index];
        }
        if (rawAxes == null) {
            return false;
        }
        int axis = rawAxes.length + index;
        if (axis < 0 || axis >= rawAxes.length) {
            return false;
        }
        return calibrate(axis, rawAxes[axis]) > VIRTUAL_BUTTON_THRESHOLD;
    }

    /** Switch index that addresses axis {@code axis} as a virtual button. */
    public static int virtualIndex(int axis, int axisCount) {
        return axis - axisCount;
    }

    public static boolean isVirtual(int switchIndex) {
        return switchIndex < 0;
    }

    public ChannelMap copy() {
        ChannelMap m = new ChannelMap();
        m.throttleAxis = throttleAxis;
        m.rollAxis = rollAxis;
        m.pitchAxis = pitchAxis;
        m.yawAxis = yawAxis;
        m.angleAxis = angleAxis;
        m.armSwitch = armSwitch;
        m.angleSwitch = angleSwitch;
        m.rightClickSwitch = rightClickSwitch;
        m.invertThrottle = invertThrottle;
        m.invertRoll = invertRoll;
        m.invertPitch = invertPitch;
        m.invertYaw = invertYaw;
        m.invertAngle = invertAngle;
        m.invertArm = invertArm;
        m.invertAngleSwitch = invertAngleSwitch;
        m.invertRightClick = invertRightClick;
        m.calMin = calMin == null ? null : calMin.clone();
        m.calMax = calMax == null ? null : calMax.clone();
        return m;
    }

    private static float[] filled(int n, float v) {
        float[] a = new float[n];
        Arrays.fill(a, v);
        return a;
    }
}
