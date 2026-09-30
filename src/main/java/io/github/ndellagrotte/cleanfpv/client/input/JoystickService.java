package io.github.ndellagrotte.cleanfpv.client.input;

import com.cleanroommc.client.sdl.SDL;
import com.cleanroommc.client.sdl.events.JoystickEvent;
import com.cleanroommc.client.sdl.input.Gamepad;
import com.cleanroommc.client.sdl.input.Joystick;
import com.cleanroommc.client.sdl.input.JoystickHat;
import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.sdl.SDLHints;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The selected SDL joystick, polled once per frame on the client thread (spec §3.1, PLAN §6.1).
 * Implements {@link JoystickAccess} for the GUI/HUD and installs itself there after a successful
 * {@link #init()}.
 *
 * <h2>SDL facts this relies on (api-notes §1)</h2>
 * <ul>
 *   <li>The first {@code SDL.joysticks().list()} brings up {@code SDL_INIT_JOYSTICK} and opens every
 *       connected stick. {@code SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS} is set before that, so a
 *       radio keeps reporting while the window is unfocused.</li>
 *   <li>Cleanroom's window pump ({@code SDL_PollEvent}, after {@code RenderTickEvent} END) refreshes
 *       device state; axis/button <em>events</em> are dropped by the pump, which is why this class
 *       polls {@code axis(i, 0f)} / {@code button(i)} / {@code hat(i)} (deadzone 0: calibration is
 *       ours).</li>
 *   <li>Hotplug arrives on {@code SDL.events()} (a separate FML {@code EventBus}) as
 *       {@link JoystickEvent.Added} (also re-sent for sticks already open at init, so handling is
 *       idempotent) and {@link JoystickEvent.Removed}, which fires <em>after</em> the handle is
 *       closed: the reference is dropped without calling into it.</li>
 * </ul>
 *
 * <h2>Selection</h2>
 * A preferred GUID (the model's {@code controllerGuid}, or whatever the GUI passed to
 * {@link #select}) is reselected whenever it is present; otherwise the first stick in SDL order is
 * used. Identical devices share a GUID; the first one wins.
 *
 * <h2>Failure policy</h2>
 * Nothing here throws. If SDL cannot initialise, the service stays uninstalled
 * ({@link JoystickAccess#get()} keeps {@link JoystickAccess#NONE}) and input falls back to keyboard +
 * mouse, with one log line. A poll failure drops the selection (keyboard fallback) with one log
 * line; the next hotplug event may select a device again.
 */
public final class JoystickService implements JoystickAccess {

    private enum Status { NEW, READY, FAILED }

    private static final float[] NO_AXES = new float[0];
    private static final boolean[] NO_BUTTONS = new boolean[0];

    private Status status = Status.NEW;

    /** Open sticks in SDL order, parallel to {@link #devices}. */
    private final List<Joystick> sticks = new ArrayList<>();
    private List<Device> devices = List.of();

    private Joystick selected;
    private Device selectedDevice;
    private String preferredGuid = "";

    private float[] axes = NO_AXES;
    private boolean[] buttons = NO_BUTTONS;

    private boolean hotplugRegistered;
    private boolean pollFailureLogged;
    private boolean eventFailureLogged;

    /**
     * Registers the hotplug listener on {@code SDL.events()} without touching the joystick subsystem.
     * Call during FML initialisation (the input subscriber's constructor does): FML's {@code EventBus}
     * records the active mod container at registration and logs a stack-traced error when there is
     * none, as on a later client tick. Idempotent; never throws.
     */
    public void registerHotplug() {
        if (hotplugRegistered) {
            return;
        }
        hotplugRegistered = true;
        try {
            SDL.events().register(this);
        } catch (RuntimeException | LinkageError e) {
            CleanFpv.LOGGER.warn("Could not listen for joystick hotplug ({})", e.toString());
        }
    }

    /**
     * Brings up SDL joystick support, registers the hotplug listener if
     * {@link #registerHotplug()} was not called yet, selects a device and installs
     * this service as {@link JoystickAccess}. Idempotent; call on the client thread after the window
     * exists (the input subscriber does it on the first client tick).
     *
     * @return whether joystick input is available
     */
    public boolean init() {
        if (status != Status.NEW) {
            return status == Status.READY;
        }
        try {
            SDL.hint(SDLHints.SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1");
        } catch (RuntimeException | LinkageError e) {
            CleanFpv.LOGGER.warn("Could not set the SDL background-joystick hint ({}); a controller may stop"
                    + " reporting while the window is unfocused", e.toString());
        }
        try {
            registerHotplug();
            refreshDevices();
            reselect();
            status = Status.READY;
        } catch (RuntimeException | LinkageError e) {
            status = Status.FAILED;
            dropSelection();
            CleanFpv.LOGGER.warn("Joystick input unavailable ({}); using keyboard and mouse only", e.toString());
            return false;
        }
        JoystickAccess.install(this);
        logDevices("Joysticks at startup");
        return true;
    }

    /** Whether {@link #init()} succeeded. */
    public boolean isReady() {
        return status == Status.READY;
    }

    /**
     * Copies the selected stick's current axes, buttons and hats into the reused arrays. Main thread,
     * once per frame. Never throws: on an SDL failure the selection is dropped (one log line).
     */
    public void poll() {
        Joystick stick = selected;
        if (status != Status.READY || stick == null) {
            return;
        }
        try {
            int na = stick.axes();
            int nb = stick.buttons();
            int nh = stick.hats();
            ensureArrays(na, nb, nh);
            for (int i = 0; i < na; i++) {
                axes[i] = stick.axis(i, 0f);
            }
            for (int i = 0; i < nb; i++) {
                buttons[i] = stick.button(i);
            }
            for (int h = 0; h < nh; h++) {
                JoystickHat hat = stick.hat(h);
                int o = nb + h * 4;
                buttons[o] = hat.up();
                buttons[o + 1] = hat.right();
                buttons[o + 2] = hat.down();
                buttons[o + 3] = hat.left();
            }
        } catch (RuntimeException | LinkageError e) {
            if (!pollFailureLogged) {
                pollFailureLogged = true;
                CleanFpv.LOGGER.warn("Joystick '{}' could not be read ({}); falling back to keyboard input",
                        selectedDevice != null ? selectedDevice.name() : "?", e.toString());
            }
            dropSelection();
        }
    }

    // =============================================================================================
    // JoystickAccess

    @Override
    public List<Device> devices() {
        return devices;
    }

    @Override
    public Device selected() {
        return selectedDevice;
    }

    /**
     * Prefers the device with this GUID ({@code null}/empty = first stick) and reselects now. The
     * preference stays until another {@code select} (the input manager forwards the active model's
     * {@code controllerGuid} only when that value changes).
     */
    @Override
    public void select(String guid) {
        String g = guid == null ? "" : guid;
        if (g.equals(preferredGuid) && (selected != null || sticks.isEmpty())) {
            return;
        }
        preferredGuid = g;
        if (status == Status.READY) {
            reselect();
        }
    }

    /** Live, reused array (length = selected axis count); contents change every poll. Clone to keep. */
    @Override
    public float[] rawAxes() {
        return axes;
    }

    /** Live, reused array: buttons then four per hat (up, right, down, left). Clone to keep. */
    @Override
    public boolean[] rawButtons() {
        return buttons;
    }

    // =============================================================================================
    // Gamepad pre-fill

    /**
     * Gamepad-scheme channel defaults for the selected device: if SDL knows it as a gamepad, its
     * mapping places the sticks (see {@link GamepadMapping}); otherwise, or with no device,
     * {@link ChannelMap#gamepadDefaults(int)}. For the settings GUI (E); the first call initialises
     * SDL's gamepad subsystem. Never throws.
     */
    public ChannelMap gamepadDefaults() {
        Joystick stick = selected;
        if (stick == null || status != Status.READY) {
            return ChannelMap.gamepadDefaults(selectedDevice != null ? selectedDevice.axisCount() : 6);
        }
        int axisCount = selectedDevice.axisCount();
        try {
            Gamepad pad = SDL.gamepads().byId(stick.id());
            String mapping = pad != null ? pad.mapping() : null;
            return GamepadMapping.channelMap(mapping, axisCount, stick.buttons());
        } catch (RuntimeException | LinkageError e) {
            CleanFpv.LOGGER.debug("No gamepad mapping for '{}': {}", selectedDevice.name(), e.toString());
            return ChannelMap.gamepadDefaults(axisCount);
        }
    }

    // =============================================================================================
    // Hotplug (SDL.events(), main thread)

    @SubscribeEvent
    public void onJoystickAdded(JoystickEvent.Added event) {
        if (status != Status.READY) {
            return; // another mod started SDL joysticks before our init; init() lists them
        }
        try {
            int before = sticks.size();
            refreshDevices();
            Device prev = selectedDevice;
            Joystick added = event.joystick();
            boolean preferred = added != null && !preferredGuid.isEmpty() && preferredGuid.equals(guidOf(added));
            if (selected == null || (preferred && !preferredGuid.equals(prev != null ? prev.guid() : ""))) {
                reselect();
            }
            if (sticks.size() != before) {
                logDevices("Joystick connected");
            }
        } catch (RuntimeException | LinkageError e) {
            logEventFailure(e);
        }
    }

    @SubscribeEvent
    public void onJoystickRemoved(JoystickEvent.Removed event) {
        if (status != Status.READY) {
            return;
        }
        try {
            boolean wasSelected = selected != null && selected.id() == event.instanceId();
            if (wasSelected) {
                // The handle is already closed: never call into this Joystick again.
                dropSelection();
            }
            refreshDevices(); // Cleanroom removed the closed stick from its list before posting
            if (selected == null) {
                reselect();
            }
            logDevices("Joystick disconnected");
        } catch (RuntimeException | LinkageError e) {
            logEventFailure(e);
        }
    }

    // =============================================================================================
    // Internals

    private void refreshDevices() {
        List<Joystick> list = SDL.joysticks().list();
        List<Device> infos = new ArrayList<>(list.size());
        sticks.clear();
        for (Joystick s : list) {
            sticks.add(s);
            infos.add(describe(s));
        }
        devices = List.copyOf(infos);
        // Keep the selected Device record in sync (same instance id).
        if (selected != null) {
            int idx = indexOf(selected.id());
            if (idx < 0) {
                dropSelection();
            } else {
                selectedDevice = devices.get(idx);
            }
        }
    }

    private void reselect() {
        int idx = -1;
        if (!preferredGuid.isEmpty()) {
            for (int i = 0; i < devices.size(); i++) {
                if (preferredGuid.equals(devices.get(i).guid())) {
                    idx = i;
                    break;
                }
            }
        }
        if (idx < 0 && !sticks.isEmpty()) {
            idx = 0;
        }
        Joystick next = idx >= 0 ? sticks.get(idx) : null;
        if (next == selected) {
            return;
        }
        selected = next;
        selectedDevice = idx >= 0 ? devices.get(idx) : null;
        axes = NO_AXES;
        buttons = NO_BUTTONS;
        pollFailureLogged = false;
        if (selectedDevice != null) {
            CleanFpv.LOGGER.info("Using joystick '{}' ({} axes, {} buttons incl. hats, guid {})",
                    selectedDevice.name(), selectedDevice.axisCount(), selectedDevice.buttonCount(),
                    selectedDevice.guid());
        }
    }

    private void dropSelection() {
        selected = null;
        selectedDevice = null;
        axes = NO_AXES;
        buttons = NO_BUTTONS;
    }

    private void ensureArrays(int na, int nb, int nh) {
        if (axes.length != na) {
            axes = new float[na];
        }
        int total = nb + nh * 4;
        if (buttons.length != total) {
            buttons = new boolean[total];
        }
    }

    private int indexOf(int instanceId) {
        for (int i = 0; i < sticks.size(); i++) {
            if (sticks.get(i).id() == instanceId) {
                return i;
            }
        }
        return -1;
    }

    private static Device describe(Joystick s) {
        return new Device(guidOf(s), s.name(), s.axes(), s.buttons() + s.hats() * 4);
    }

    private static String guidOf(Joystick s) {
        return Objects.requireNonNullElse(s.guid(), "");
    }

    private void logDevices(String header) {
        if (devices.isEmpty()) {
            CleanFpv.LOGGER.info("{}: none. (On Linux a radio or gamepad needs read access to its"
                    + " /dev/hidraw* or /dev/input/event* node, usually via a udev rule.)", header);
            return;
        }
        StringBuilder sb = new StringBuilder(header).append(':');
        for (Device d : devices) {
            sb.append(" ['").append(d.name()).append("' axes=").append(d.axisCount())
                    .append(" buttons=").append(d.buttonCount()).append(" guid=").append(d.guid()).append(']');
        }
        CleanFpv.LOGGER.info(sb.toString());
    }

    private void logEventFailure(Throwable e) {
        if (!eventFailureLogged) {
            eventFailureLogged = true;
            CleanFpv.LOGGER.warn("Joystick hotplug handling failed ({})", e.toString());
        }
    }
}
