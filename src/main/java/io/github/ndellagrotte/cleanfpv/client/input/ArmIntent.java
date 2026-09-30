package io.github.ndellagrotte.cleanfpv.client.input;

/**
 * What the pilot's arm controls ask for this frame, as returned by {@link ArmInput#pollArmIntent}.
 * The request is <em>not</em> a decision: orchestration (F) still applies the Hello gate and the
 * throttle-low guard (see {@link InputManager#throttleGuardApplies()}).
 */
public enum ArmIntent {
    /** Nothing to do. */
    NONE,
    /** Arm (only produced while disarmed). */
    ARM,
    /** Disarm (only produced while armed). */
    DISARM;

    /**
     * Combines the arm key and the arm switch (pure; unit-tested).
     *
     * <ul>
     *   <li>Arm key (edge-triggered toggle, PLAN §6.1/§10): any press since the last poll toggles:
     *       {@link #ARM} when disarmed, {@link #DISARM} when armed. It wins over a switch edge in the
     *       same frame.</li>
     *   <li>Arm switch (level-triggered with debounce, see {@link SwitchDebouncer}): only
     *       <em>accepted edges</em> act: rising → {@link #ARM}, falling → {@link #DISARM}. A switch left
     *       ON after a refused or forced disarm does not re-arm until it is cycled (like a real flight
     *       controller's arming prevention).</li>
     *   <li>Requests that would not change the state (arm while armed, disarm while disarmed) are
     *       {@link #NONE}.</li>
     * </ul>
     *
     * @param keyPressed  the arm key was pressed at least once since the last poll
     * @param switchEdge  the debounced arm-switch edge this poll
     * @param armed       the local pilot is currently armed
     */
    public static ArmIntent resolve(boolean keyPressed, SwitchDebouncer.Edge switchEdge, boolean armed) {
        if (keyPressed) {
            return armed ? DISARM : ARM;
        }
        if (switchEdge == SwitchDebouncer.Edge.RISING && !armed) {
            return ARM;
        }
        if (switchEdge == SwitchDebouncer.Edge.FALLING && armed) {
            return DISARM;
        }
        return NONE;
    }
}
