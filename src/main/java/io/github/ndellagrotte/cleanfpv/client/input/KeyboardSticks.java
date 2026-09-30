package io.github.ndellagrotte.cleanfpv.client.input;

/**
 * Keyboard contribution to the sticks (spec §3.2). Pure: no Minecraft access, so it is unit-tested
 * directly; {@link InputManager} reads the vanilla movement bindings and passes their states in.
 *
 * <p>Rules, applied on top of a base {@link StickState} (the joystick reading, or
 * {@link StickState#KEYBOARD_IDLE} when there is none):
 * <ul>
 *   <li>left (A) adds {@value #YAW_STEP}·(−1) to yaw, right (D) adds {@value #YAW_STEP}; both cancel;</li>
 *   <li>jump (Space) <em>overrides</em> the raw throttle with {@value #THROTTLE_JUMP};</li>
 *   <li>forward (W) overrides it with {@value #THROTTLE_FORWARD} (75 % in normal mode) unless
 *       Space is also held: Space wins, the stronger command (the spec lists both as overrides
 *       without an order);</li>
 *   <li>back (S) then <em>adds</em> {@value #THROTTLE_BACK} (full reverse in 3D mode, 0 % in normal
 *       mode from the keyboard idle).</li>
 * </ul>
 * Results are clamped to [−1, 1] by {@link StickState}. With no key held the base is returned
 * unchanged (same instance).
 */
public final class KeyboardSticks {

    /** Yaw stick deflection per held A/D key. */
    public static final float YAW_STEP = 0.5f;
    /** Raw throttle while Space is held. */
    public static final float THROTTLE_JUMP = 1.0f;
    /** Raw throttle while W is held. */
    public static final float THROTTLE_FORWARD = 0.5f;
    /** Raw throttle added while S is held. */
    public static final float THROTTLE_BACK = -1.0f;

    private KeyboardSticks() {}

    /**
     * Combines key states with {@code base}.
     *
     * @param base    stick state before keys (never {@code null})
     * @param left    strafe-left binding held (A)
     * @param right   strafe-right binding held (D)
     * @param forward forward binding held (W)
     * @param back    back binding held (S)
     * @param jump    jump binding held (Space)
     * @return the combined state
     */
    public static StickState apply(StickState base, boolean left, boolean right, boolean forward,
                                   boolean back, boolean jump) {
        if (!left && !right && !forward && !back && !jump) {
            return base;
        }
        float yaw = base.yaw();
        if (left) {
            yaw -= YAW_STEP;
        }
        if (right) {
            yaw += YAW_STEP;
        }
        float thr = base.thr();
        if (jump) {
            thr = THROTTLE_JUMP;
        } else if (forward) {
            thr = THROTTLE_FORWARD;
        }
        if (back) {
            thr += THROTTLE_BACK;
        }
        return new StickState(thr, base.roll(), base.pitch(), yaw, base.angle(), base.arm(), base.angleSw(),
                base.rclick());
    }
}
