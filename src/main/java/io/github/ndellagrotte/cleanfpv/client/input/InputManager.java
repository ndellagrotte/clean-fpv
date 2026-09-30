package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import io.github.ndellagrotte.cleanfpv.common.config.ControllerScheme;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;

/**
 * The input subsystem's single entry point (PLAN §6.1, spec §3): owns the {@link JoystickService},
 * builds one {@link StickState} per frame, feeds the arm switch and the right-click switch, and
 * holds the per-tick {@link DroneMovementInput}. Client thread only.
 *
 * <h2>What orchestration (F) calls</h2>
 * <ul>
 *   <li>{@link #pollFrame()} — <b>once per rendered frame, from {@code RenderTickEvent} START, every
 *       frame</b> (armed or not, GUI open or not: the settings wizard and stick overlay read
 *       {@link JoystickAccess} / {@code ClientDroneContext.sticks()}). Frame order (api-notes D2):
 *       client ticks → {@code beginFrame} → RenderTick START → {@code updateCameraAndRender}
 *       ({@code MouseTurnEvent} = attitude step) → RenderTick END → SDL pump. The pump that refreshes
 *       joystick state runs after END, so START reads the freshest state and it is in place before
 *       this frame's attitude step; an END poll would read the same data one step later. Client
 *       ticks (physics) run before START and so use the previous frame's sticks.</li>
 *   <li>{@link #arm()}{@code .pollArmIntent(armed)} — once per frame or tick in the arm FSM; then apply
 *       the Hello gate and, when {@link #throttleGuardApplies()}, the {@code sticks().throttleLow()}
 *       guard.</li>
 *   <li>{@link MouseInput} (static) — from F's own {@code MouseTurnEvent} handler.</li>
 *   <li>{@link #reset()} — on disconnect / client world unload.</li>
 * </ul>
 *
 * <h2>Stick rules</h2>
 * <ul>
 *   <li>Scheme RADIO/GAMEPAD with a connected joystick: each channel through
 *       {@link ChannelMap#readAxis}/{@link ChannelMap#readSwitch} (calibration, inversion, virtual
 *       buttons). Source {@link Source#JOYSTICK}.</li>
 *   <li>Scheme KEYBOARD, or a joystick scheme with no device (fallback): base
 *       {@link StickState#KEYBOARD_IDLE} (raw throttle 0 = 50 %), switches off. Source
 *       {@link Source#KEYBOARD}.</li>
 *   <li>While armed and no GUI is open, the movement bindings are combined on top per
 *       {@link KeyboardSticks} (spec §3.2: keys combine with stick values in every scheme). While
 *       disarmed they are not applied, so walking with W never blocks the throttle guard.</li>
 * </ul>
 */
public final class InputManager {

    /** Where this frame's sticks came from. */
    public enum Source { JOYSTICK, KEYBOARD }

    private static final ChannelMap DEFAULT_CHANNELS = ChannelMap.radioDefaults();
    private static final InputManager INSTANCE = new InputManager();

    public static InputManager get() {
        return INSTANCE;
    }

    private final JoystickService joystick = new JoystickService(); // no SDL calls until init()
    private final ArmInput arm = new ArmInput();
    private final RightClickSwitch rightClick = new RightClickSwitch();
    private final DroneMovementInput movement = new DroneMovementInput();

    private Source source = Source.KEYBOARD;
    private String lastModelGuid;

    private InputManager() {}

    /**
     * Registers the joystick hotplug listener on {@code SDL.events()}. Must run during FML
     * initialisation (see {@link JoystickService#registerHotplug()}); {@link InputEvents}' constructor
     * calls it, which {@code ClientProxy.init} runs. Does not start SDL's joystick subsystem.
     */
    public void registerListeners() {
        joystick.registerHotplug();
    }

    /**
     * Initialises the joystick service: SDL background hint, subsystem start, device selection,
     * {@link JoystickAccess#install}. Idempotent and cheap after the first call. Called by
     * {@link InputEvents} on the first client tick and by {@link #pollFrame()}; never from a static
     * initializer.
     */
    public void init() {
        joystick.init();
    }

    /**
     * Polls the joystick, builds this frame's {@link StickState}, publishes it with
     * {@code ClientDroneContext.setSticks}, feeds the arm switch and drives the right-click switch.
     * Never throws for joystick problems (the service degrades to no device).
     *
     * @return the published state
     */
    public StickState pollFrame() {
        init();
        Minecraft mc = Minecraft.getMinecraft();
        ClientDroneContext ctx = ClientDroneContext.get();
        DroneModelConfig model = ctx.activeModel();

        if (joystick.isReady()) {
            String guid = model.controllerGuid != null ? model.controllerGuid : "";
            if (!guid.equals(lastModelGuid)) {
                lastModelGuid = guid;
                joystick.select(guid);
            }
            joystick.poll();
        }

        ControllerScheme scheme = model.scheme != null ? model.scheme : ControllerScheme.RADIO;
        boolean joy = scheme.usesJoystick() && joystick.isConnected();
        StickState s = joy ? readJoystick(model.channels, joystick.rawAxes(), joystick.rawButtons())
                : StickState.KEYBOARD_IDLE;

        boolean guiOpen = mc.currentScreen != null;
        boolean armed = ctx.isArmed();
        GameSettings gs = mc.gameSettings;
        if (armed && !guiOpen && gs != null) {
            s = KeyboardSticks.apply(s, gs.keyBindLeft.isKeyDown(), gs.keyBindRight.isKeyDown(),
                    gs.keyBindForward.isKeyDown(), gs.keyBindBack.isKeyDown(), gs.keyBindJump.isKeyDown());
        }

        ctx.setSticks(s);
        source = joy ? Source.JOYSTICK : Source.KEYBOARD;
        arm.updateSwitch(joy, s.arm(), System.nanoTime() / 1_000_000L);
        rightClick.update(joy && s.rclick(), armed && !guiOpen && mc.player != null);
        return s;
    }

    /** Joystick channels → sticks (calibration, inversion, virtual buttons live in {@link ChannelMap}). */
    static StickState readJoystick(ChannelMap ch, float[] axes, boolean[] buttons) {
        ChannelMap m = ch != null ? ch : DEFAULT_CHANNELS;
        return new StickState(
                m.readAxis(ChannelMap.Axis.THROTTLE, axes),
                m.readAxis(ChannelMap.Axis.ROLL, axes),
                m.readAxis(ChannelMap.Axis.PITCH, axes),
                m.readAxis(ChannelMap.Axis.YAW, axes),
                m.readAxis(ChannelMap.Axis.ANGLE, axes),
                m.readSwitch(ChannelMap.Switch.ARM, axes, buttons),
                m.readSwitch(ChannelMap.Switch.ANGLE, axes, buttons),
                m.readSwitch(ChannelMap.Switch.RIGHT_CLICK, axes, buttons));
    }

    /** Source of the last {@link #pollFrame()}. */
    public Source source() {
        return source;
    }

    /**
     * Whether F should enforce the arming throttle guard ({@code sticks().throttleLow()}): only for
     * joystick sticks. Keyboard flight has no throttle stick and idles at raw 0 (50 %) by design
     * (spec §3.2), so the guard would make keyboard arming impossible.
     */
    public boolean throttleGuardApplies() {
        return source == Source.JOYSTICK;
    }

    /** Arm key / arm switch intent (see {@link ArmInput#pollArmIntent}). */
    public ArmInput arm() {
        return arm;
    }

    /** Per-tick movement-key capture (fed by {@link InputEvents}). */
    public DroneMovementInput movement() {
        return movement;
    }

    /** The joystick as {@link JoystickAccess} ({@link JoystickAccess#NONE} before init or when SDL failed). */
    public JoystickAccess joystick() {
        return JoystickAccess.get();
    }

    /**
     * Gamepad-scheme channel defaults for the selected device (SDL gamepad mapping when known, else
     * {@link ChannelMap#gamepadDefaults(int)}); for the settings GUI. Never {@code null}.
     */
    public ChannelMap gamepadDefaults() {
        init();
        return joystick.gamepadDefaults();
    }

    /**
     * Disconnect / client world unload: releases the use-item binding if the right-click switch holds
     * it, re-baselines the arm switch (a switch left ON does not re-arm on the next world) and drops
     * queued arm-key presses and recorded movement keys. Keeps the joystick selection.
     */
    public void reset() {
        rightClick.release();
        arm.reset();
        movement.clear();
    }
}
