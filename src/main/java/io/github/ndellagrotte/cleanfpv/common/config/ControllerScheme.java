package io.github.ndellagrotte.cleanfpv.common.config;

/**
 * How a model is flown. RADIO and GAMEPAD read an SDL joystick through {@link ChannelMap} and differ
 * only in their defaults (spec §3.1: the original's {@code isGamepad} flag); KEYBOARD uses the
 * keyboard + mouse path only (spec §3.2) and ignores any joystick.
 */
public enum ControllerScheme {
    RADIO,
    GAMEPAD,
    KEYBOARD;

    public boolean usesJoystick() {
        return this != KEYBOARD;
    }
}
