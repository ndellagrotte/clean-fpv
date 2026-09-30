package io.github.ndellagrotte.cleanfpv.client.input;

import java.util.List;

/**
 * Read access to the selected joystick for code outside the input package (settings screens,
 * the wizard, the stick overlay). Implemented by input (B)'s {@code JoystickService}, which
 * installs itself with {@link #install}. Client thread only.
 */
public interface JoystickAccess {

    /** One connected device. {@code guid} is SDL's GUID string, used to reselect on reconnect. */
    record Device(String guid, String name, int axisCount, int buttonCount) {}

    /** Connected devices, in SDL order. */
    List<Device> devices();

    /** The selected device, or {@code null} when none is connected. */
    Device selected();

    /** Selects the device with this GUID (persisted in the model); falls back to the first stick. */
    void select(String guid);

    /** Latest polled raw axes in [-1, 1], no deadzone; length = selected axis count (empty if none). */
    float[] rawAxes();

    /** Latest polled buttons (hats appended as four buttons each: up, right, down, left). */
    boolean[] rawButtons();

    default boolean isConnected() {
        return selected() != null;
    }

    JoystickAccess NONE = new JoystickAccess() {
        @Override public List<Device> devices() { return List.of(); }
        @Override public Device selected() { return null; }
        @Override public void select(String guid) {}
        @Override public float[] rawAxes() { return new float[0]; }
        @Override public boolean[] rawButtons() { return new boolean[0]; }
    };

    static JoystickAccess get() {
        return Holder.current;
    }

    static void install(JoystickAccess access) {
        Holder.current = access != null ? access : NONE;
    }

    final class Holder {
        private static JoystickAccess current = NONE;

        private Holder() {}
    }
}
