package io.github.ndellagrotte.cleanfpv.client.input;

/**
 * One frame's stick and switch values after calibration, inversion and keyboard overrides (spec
 * §3.1/§3.2). Immutable; the input layer publishes a new instance per poll into
 * {@code ClientDroneContext.setSticks}.
 *
 * <p>Axes are in [−1, 1]. Throttle −1 = stick low (0 % in normal mode, full reverse in 3D mode).
 * Mouse displacement is <em>not</em> here (it is a per-frame angle add, see {@code MouseInput}).
 *
 * @param thr     throttle
 * @param roll    roll
 * @param pitch   pitch
 * @param yaw     yaw
 * @param angle   camera-angle knob
 * @param arm     arm switch (level)
 * @param angleSw activate-custom-camera-angle switch
 * @param rclick  right-click (use item) switch
 */
public record StickState(float thr, float roll, float pitch, float yaw, float angle, boolean arm,
                         boolean angleSw, boolean rclick) {

    /** Everything centred, throttle low, switches off. */
    public static final StickState NEUTRAL = new StickState(-1f, 0f, 0f, 0f, 0f, false, false, false);

    /** Keyboard-only baseline: raw throttle 0 (= 50 % in normal mode, spec §3.2), switches off. */
    public static final StickState KEYBOARD_IDLE = new StickState(0f, 0f, 0f, 0f, 0f, false, false, false);

    public StickState {
        thr = clampAxis(thr);
        roll = clampAxis(roll);
        pitch = clampAxis(pitch);
        yaw = clampAxis(yaw);
        angle = clampAxis(angle);
    }

    public StickState withThr(float v) {
        return new StickState(v, roll, pitch, yaw, angle, arm, angleSw, rclick);
    }

    public StickState withYaw(float v) {
        return new StickState(thr, roll, pitch, v, angle, arm, angleSw, rclick);
    }

    public StickState withArm(boolean v) {
        return new StickState(thr, roll, pitch, yaw, angle, v, angleSw, rclick);
    }

    /** Arming guard (spec §3.2, enforced here per PLAN §10): {@code (thr+1)/2 ≤ 0.05}. */
    public boolean throttleLow() {
        return (thr + 1f) * 0.5f <= 0.05f;
    }

    private static float clampAxis(float v) {
        if (!Float.isFinite(v)) {
            return 0f;
        }
        return Math.clamp(v, -1f, 1f);
    }
}
