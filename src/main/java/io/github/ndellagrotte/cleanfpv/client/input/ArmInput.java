package io.github.ndellagrotte.cleanfpv.client.input;

import net.minecraft.client.Minecraft;

/**
 * Arm controls (spec §3.2, PLAN §6.1): the arm key ({@link KeyBindings#ARM}, edge-triggered toggle)
 * and the joystick arm switch (level-triggered, {@value SwitchDebouncer#DEBOUNCE_MS} ms debounce).
 * Owned by {@link InputManager}; orchestration (F) calls {@link #pollArmIntent(boolean)}.
 *
 * <p>The switch is fed by {@link InputManager#pollFrame()} every frame; its accepted edge is
 * latched here until the next {@link #pollArmIntent} (a later edge replaces an unread one). Key
 * presses accumulate in the vanilla binding and are drained by {@link #pollArmIntent}. Client thread
 * only.
 */
public final class ArmInput {

    private final SwitchDebouncer armSwitch = new SwitchDebouncer();
    private SwitchDebouncer.Edge pendingEdge = SwitchDebouncer.Edge.NONE;

    ArmInput() {}

    /** Feeds this frame's arm-switch reading ({@code available = false} without a joystick). */
    void updateSwitch(boolean available, boolean level, long nowMs) {
        SwitchDebouncer.Edge e = armSwitch.update(available, level, nowMs);
        if (e != SwitchDebouncer.Edge.NONE) {
            pendingEdge = e;
        }
    }

    /**
     * What the pilot asks for since the last call (see {@link ArmIntent#resolve} for the rules).
     * Drains the arm key's queued presses; presses are ignored while a GUI is open.
     *
     * <p><b>Not checked here (F's job):</b> the Hello gate and the throttle-low guard
     * ({@code ClientDroneContext.sticks().throttleLow()}, applied only when
     * {@link InputManager#throttleGuardApplies()}; keyboard flight idles at 50 % by design).
     *
     * @param armed whether the local pilot is armed right now
     */
    public ArmIntent pollArmIntent(boolean armed) {
        boolean key = false;
        while (KeyBindings.ARM.isPressed()) {
            key = true;
        }
        if (Minecraft.getMinecraft().currentScreen != null) {
            key = false;
        }
        SwitchDebouncer.Edge edge = pendingEdge;
        pendingEdge = SwitchDebouncer.Edge.NONE;
        return ArmIntent.resolve(key, edge, armed);
    }

    /** Debounced arm-switch level (for the HUD / wizard); {@code false} without a joystick. */
    public boolean switchState() {
        return armSwitch.state();
    }

    /** Forgets switch history and queued key presses (disconnect / world change). */
    void reset() {
        armSwitch.reset();
        pendingEdge = SwitchDebouncer.Edge.NONE;
        while (KeyBindings.ARM.isPressed()) {
            // drain
        }
    }
}
