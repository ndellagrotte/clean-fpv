package io.github.ndellagrotte.cleanfpv.client.gui.wizard;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.AxisDetector;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.RangeCapture;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.SwitchDetector;
import io.github.ndellagrotte.cleanfpv.client.gui.screen.ControllerScreen;
import io.github.ndellagrotte.cleanfpv.client.gui.screen.FpvScreen;
import io.github.ndellagrotte.cleanfpv.client.gui.screen.SettingsHomeScreen;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvButton;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvSlider;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.GuiDraw;
import io.github.ndellagrotte.cleanfpv.client.input.InputManager;
import io.github.ndellagrotte.cleanfpv.client.input.JoystickAccess;
import io.github.ndellagrotte.cleanfpv.client.input.KeyBindings;
import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import io.github.ndellagrotte.cleanfpv.common.config.ControllerScheme;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import io.github.ndellagrotte.cleanfpv.common.config.SettingsStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.SoundEvents;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * First-run setup wizard (spec §11.1): a linear sequence of steps with Back navigation.
 *
 * <pre>
 * WELCOME ─ returning pilot ──────────────────────────────────────────────► (done, settings home)
 *    │ new pilot
 * HAS_CONTROLLER ─ no ─► KEYBOARD (arm key, mouse gain) ───────────────────► COMPLETE
 *    │ yes
 * CHOOSE_CONTROLLER (device + radio/gamepad)
 *    → CENTER → THROTTLE → YAW → PITCH → ROLL   (each: detect axis &gt; 0.3, invert from sign,
 *                                               click sound, wait for recenter)
 *    → RANGE (all axes; required four span ≥ 1.0 and 1.5 s without a new extreme)
 *    → VERIFY ("No, try again" rewinds to CENTER)
 *    → ARM_BEGIN → ARM_FLIP (button change or axis move) → ARM_VERIFY ("It's backwards")
 *    → COMPLETE
 * </pre>
 *
 * All edits go into a draft copy of the <em>current</em> model (built-in or user): the chosen
 * scheme's defaults are applied onto it ({@link DroneModelConfig#applyScheme}) and the detected
 * channels written into it, but its name, preset status and build never change, so on a fresh
 * install the wizard configures "5 Inch 4S". The draft is committed (stored over the current model,
 * {@code firstTimeSetup} cleared, saved) only on COMPLETE; Esc abandons it. Stick directions assume Mode 2 (throttle and
 * yaw on the left stick): "up" and "right" map to +1, so the invert flag is the sign of the move.
 */
public class WizardScreen extends FpvScreen {

    enum Step {
        WELCOME, HAS_CONTROLLER, CHOOSE_CONTROLLER, CENTER, THROTTLE, YAW, PITCH, ROLL, RANGE, VERIFY,
        ARM_BEGIN, ARM_FLIP, ARM_VERIFY, KEYBOARD, COMPLETE
    }

    private static final Step[] STICK_STEPS = {Step.THROTTLE, Step.YAW, Step.PITCH, Step.ROLL};
    private static final int MAX_DEVICE_ROWS = 5;

    private final Deque<Step> history = new ArrayDeque<>();
    private Step step = Step.WELCOME;

    private DroneModelConfig draft;
    private JoystickAccess.Device device;
    private List<JoystickAccess.Device> shownDevices = List.of();

    private final AxisDetector axisDetector = new AxisDetector();
    private final Map<ChannelMap.Axis, Integer> detected = new EnumMap<>(ChannelMap.Axis.class);
    private boolean waitingForCenter;
    private final RangeCapture range = new RangeCapture();
    private final SwitchDetector switchDetector = new SwitchDetector();
    private boolean awaitingKey;

    public WizardScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.wizard.title");
    }

    @Override
    protected String title() {
        return t("cleanfpv.gui.wizard.title") + " §7- " + t(stepKey() + ".title");
    }

    private String stepKey() {
        return "cleanfpv.gui.wizard." + step.name().toLowerCase(Locale.ROOT);
    }

    private static SettingsStore store() {
        return ClientSettings.store();
    }

    // =============================================================================================
    // Navigation

    private void goTo(Step next) {
        history.push(step);
        step = next;
        enter();
    }

    private void back() {
        if (history.isEmpty()) {
            close();
            return;
        }
        step = history.pop();
        enter();
    }

    /** Pops history back to {@code target} (inclusive) and re-enters it. */
    private void rewindTo(Step target) {
        while (!history.isEmpty() && step != target) {
            step = history.pop();
        }
        enter();
    }

    private void enter() {
        awaitingKey = false;
        waitingForCenter = false;
        switch (step) {
            case THROTTLE, YAW, PITCH, ROLL -> {
                boolean clear = false;
                for (Step s : STICK_STEPS) {
                    clear |= s == step;
                    if (clear) {
                        detected.remove(axisFor(s));
                    }
                }
            }
            case CENTER -> detected.clear();
            case RANGE -> range.reset();
            default -> {
            }
        }
        requestRebuild();
    }

    private static ChannelMap.Axis axisFor(Step s) {
        return switch (s) {
            case THROTTLE -> ChannelMap.Axis.THROTTLE;
            case YAW -> ChannelMap.Axis.YAW;
            case PITCH -> ChannelMap.Axis.PITCH;
            case ROLL -> ChannelMap.Axis.ROLL;
            default -> null;
        };
    }

    private static Step nextStickStep(Step s) {
        return switch (s) {
            case THROTTLE -> Step.YAW;
            case YAW -> Step.PITCH;
            case PITCH -> Step.ROLL;
            default -> Step.RANGE;
        };
    }

    // =============================================================================================
    // Draft handling

    /**
     * A draft copy of the current model switched to {@code scheme}: the scheme's rate and display
     * defaults are applied only when the scheme changes, so re-running the wizard keeps tuned rates.
     */
    private static DroneModelConfig draftFor(ControllerScheme scheme) {
        DroneModelConfig d = store().current().copy();
        d.applyScheme(scheme);
        return d;
    }

    private void startController(ControllerScheme scheme) {
        if (device == null) {
            return;
        }
        draft = draftFor(scheme);
        // Select first: the SDL gamepad-mapping pre-fill (PLAN §6.1) reads the selected joystick.
        ControllerScreen.selectDevice(draft, device);
        draft.channels = scheme == ControllerScheme.GAMEPAD
                ? InputManager.get().gamepadDefaults() : ChannelMap.radioDefaults();
        draft.channels.ensureAxisCount(Math.max(device.axisCount(), ChannelMap.DEFAULT_AXIS_SLOTS));
        goTo(Step.CENTER);
    }

    private void startKeyboard() {
        draft = draftFor(ControllerScheme.KEYBOARD);
        goTo(Step.KEYBOARD);
    }

    private void commit(boolean openSettings) {
        if (draft != null) {
            DroneModelConfig stored = store().put(draft);
            if (stored != null) {
                store().select(stored.name);
            }
        }
        store().setFirstTimeSetup(false);
        ClientSettings.save();
        if (openSettings) {
            openSettingsHome();
        } else {
            close();
        }
    }

    private void returningPilot() {
        store().setFirstTimeSetup(false);
        ClientSettings.save();
        openSettingsHome();
    }

    /**
     * Committed or abandoned: flight polls the active model's controller again. Choosing a device
     * switched the live joystick selection ({@link ControllerScreen#selectDevice}), and an abandoned
     * draft must not leave flight reading that device through the active model's channel map. On
     * commit the store listener has already made the committed model active.
     */
    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        DroneModelConfig active = ClientDroneContext.get().activeModel();
        JoystickAccess.get().select(active.controllerGuid != null ? active.controllerGuid : "");
    }

    /** Returns to the settings home this wizard was opened from, or opens one over our parent. */
    private void openSettingsHome() {
        mc.displayGuiScreen(parent instanceof SettingsHomeScreen ? parent : new SettingsHomeScreen(parent));
    }

    // =============================================================================================
    // Widgets per step

    @Override
    protected void build() {
        int cx = width / 2;
        int by = height - 52;
        switch (step) {
            case WELCOME -> {
                button(cx - 154, by, 150, t("cleanfpv.gui.wizard.welcome.new"), () -> goTo(Step.HAS_CONTROLLER));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.welcome.returning"), this::returningPilot)
                        .tooltip(t("cleanfpv.gui.wizard.welcome.returning.tooltip"));
            }
            case HAS_CONTROLLER -> {
                button(cx - 154, by, 150, t("cleanfpv.gui.wizard.has_controller.yes"), () -> goTo(Step.CHOOSE_CONTROLLER));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.has_controller.no"), this::startKeyboard);
            }
            case CHOOSE_CONTROLLER -> buildChooseController(cx, by);
            case CENTER -> button(cx + 4, by, 150, t("cleanfpv.gui.wizard.next"), () -> {
                axisDetector.snapshot(JoystickAccess.get().rawAxes());
                goTo(Step.THROTTLE);
            });
            case THROTTLE, YAW, PITCH, ROLL -> {
                final Step s = step;
                button(cx + 4, by, 150, "", () -> goTo(nextStickStep(s)))
                        .label(() -> detected.containsKey(axisFor(s)) ? t("cleanfpv.gui.wizard.next") : t("cleanfpv.gui.wizard.skip"));
            }
            case RANGE -> button(cx + 4, by, 150, t("cleanfpv.gui.wizard.skip"), () -> goTo(Step.VERIFY))
                    .tooltip(t("cleanfpv.gui.wizard.range.skip.tooltip"));
            case VERIFY -> {
                button(cx - 154, by, 150, t("cleanfpv.gui.wizard.verify.retry"), () -> rewindTo(Step.CENTER));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.verify.yes"), () -> goTo(Step.ARM_BEGIN));
            }
            case ARM_BEGIN -> {
                button(cx - 154, by, 150, t("cleanfpv.gui.wizard.arm_begin.skip"), () -> goTo(Step.COMPLETE))
                        .tooltip(t("cleanfpv.gui.wizard.arm_begin.skip.tooltip"));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.next"), () -> {
                    JoystickAccess ja = JoystickAccess.get();
                    switchDetector.snapshot(ja.rawAxes(), ja.rawButtons());
                    goTo(Step.ARM_FLIP);
                });
            }
            case ARM_FLIP -> {
                // detection only
            }
            case ARM_VERIFY -> {
                button(cx - 154, by, 150, t("cleanfpv.gui.wizard.arm_verify.backwards"), () ->
                        draft.channels.setInverted(ChannelMap.Switch.ARM, !draft.channels.isInverted(ChannelMap.Switch.ARM)));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.next"), () -> goTo(Step.COMPLETE));
            }
            case KEYBOARD -> {
                int y = height / 2 + 10;
                button(cx - 154, y, 304, "", () -> awaitingKey = !awaitingKey)
                        .label(() -> awaitingKey ? t("cleanfpv.gui.wizard.keyboard.press_key")
                                : t("cleanfpv.gui.wizard.keyboard.arm_key", KeyBindings.ARM.getDisplayName()));
                addButton(new FpvSlider(cx - 154, y + ROW, 304, 0.1, 10.0, 0.1,
                        () -> draft.mouseGain, v -> draft.mouseGain = (float) v,
                        v -> t("cleanfpv.gui.controller.mouse_gain", String.format(Locale.ROOT, "%.1f", v))))
                        .tooltip(t("cleanfpv.gui.controller.mouse_gain.tooltip"));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.next"), () -> goTo(Step.COMPLETE));
            }
            case COMPLETE -> {
                button(cx - 154, by, 150, t("cleanfpv.gui.wizard.complete.more"), () -> commit(true));
                button(cx + 4, by, 150, t("cleanfpv.gui.wizard.complete.done"), () -> commit(false));
            }
        }
        if (step != Step.WELCOME) {
            button(cx - 100, height - 26, 200, t("cleanfpv.gui.wizard.back"), this::back);
        } else {
            button(cx - 100, height - 26, 200, t("gui.cancel"), this::close);
        }
    }

    private void buildChooseController(int cx, int by) {
        shownDevices = JoystickAccess.get().devices();
        if (device != null && !containsGuid(shownDevices, device.guid())) {
            device = null;
        }
        if (device == null && !shownDevices.isEmpty()) {
            device = shownDevices.getFirst();
        }
        int y = scrollTop + 40;
        for (int i = 0; i < shownDevices.size() && i < MAX_DEVICE_ROWS; i++) {
            final JoystickAccess.Device d = shownDevices.get(i);
            addButton(new FpvButton(cx - 150, y, 300, 20, "", () -> device = d).label(() -> {
                String name = d.name().isEmpty() ? d.guid() : d.name();
                String text = name + " §7(" + t("cleanfpv.gui.wizard.choose.axes_buttons", d.axisCount(), d.buttonCount()) + ")";
                boolean sel = device != null && device.guid().equals(d.guid());
                return sel ? "§e> " + text + " §e<" : text;
            }));
            y += 22;
        }
        button(cx - 154, by, 100, t("cleanfpv.gui.wizard.choose.radio"), () -> startController(ControllerScheme.RADIO))
                .enabledWhen(() -> device != null)
                .tooltip(t("cleanfpv.gui.wizard.choose.radio.tooltip"));
        button(cx - 50, by, 100, t("cleanfpv.gui.wizard.choose.gamepad"), () -> startController(ControllerScheme.GAMEPAD))
                .enabledWhen(() -> device != null)
                .tooltip(t("cleanfpv.gui.wizard.choose.gamepad.tooltip"));
        button(cx + 54, by, 100, t("cleanfpv.gui.wizard.choose.keyboard"), this::startKeyboard);
    }

    private static boolean containsGuid(List<JoystickAccess.Device> devices, String guid) {
        for (JoystickAccess.Device d : devices) {
            if (d.guid().equals(guid)) {
                return true;
            }
        }
        return false;
    }

    // =============================================================================================
    // Live detection

    @Override
    protected void tick() {
        if (step == Step.CHOOSE_CONTROLLER && !JoystickAccess.get().devices().equals(shownDevices)) {
            requestRebuild();
        }
    }

    private void poll() {
        JoystickAccess ja = JoystickAccess.get();
        float[] raw = ja.rawAxes();
        switch (step) {
            case THROTTLE, YAW, PITCH, ROLL -> pollStick(raw);
            case RANGE -> {
                long now = Minecraft.getSystemTime();
                range.update(raw, now);
                int[] required = requiredAxes();
                if (required.length > 0 && range.isComplete(required, RangeCapture.DEFAULT_REQUIRED_SPAN,
                        RangeCapture.DEFAULT_QUIET_MS, now)) {
                    range.applyTo(draft.channels);
                    click();
                    goTo(Step.VERIFY);
                }
            }
            case ARM_FLIP -> {
                boolean[] buttons = ja.rawButtons();
                int idx = switchDetector.detect(raw, buttons);
                if (idx != SwitchDetector.NONE) {
                    draft.channels.setSwitchIndex(ChannelMap.Switch.ARM, idx);
                    draft.channels.setInverted(ChannelMap.Switch.ARM,
                            SwitchDetector.invertForOnNow(draft.channels, idx, raw, buttons));
                    click();
                    goTo(Step.ARM_VERIFY);
                }
            }
            default -> {
            }
        }
    }

    private void pollStick(float[] raw) {
        ChannelMap.Axis channel = axisFor(step);
        Integer axis = detected.get(channel);
        if (!waitingForCenter) {
            java.util.Set<Integer> exclude = new java.util.HashSet<>();
            for (Map.Entry<ChannelMap.Axis, Integer> e : detected.entrySet()) {
                if (e.getKey() != channel) {
                    exclude.add(e.getValue());
                }
            }
            int found = axisDetector.detect(raw, AxisDetector.DEFAULT_THRESHOLD, exclude);
            if (found >= 0) {
                detected.put(channel, found);
                draft.channels.setAxisIndex(channel, found);
                draft.channels.setInverted(channel, axisDetector.delta(found, raw) < 0f);
                waitingForCenter = true;
                click();
            }
        } else if (axis != null && axisDetector.isReturned(axis, raw, AxisDetector.DEFAULT_RETURN_TOLERANCE)) {
            goTo(nextStickStep(step));
        }
    }

    private int[] requiredAxes() {
        ChannelMap c = draft.channels;
        int n = JoystickAccess.get().rawAxes().length;
        return java.util.stream.IntStream.of(c.throttleAxis, c.yawAxis, c.pitchAxis, c.rollAxis)
                .filter(i -> i >= 0 && i < n).distinct().toArray();
    }

    private void click() {
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (awaitingKey) {
            awaitingKey = false;
            if (keyCode != Keyboard.KEY_ESCAPE && keyCode != Keyboard.KEY_NONE) {
                KeyBindings.ARM.setKeyModifierAndCode(KeyModifier.NONE, keyCode);
                KeyBinding.resetKeyBindingArrayAndHash();
                mc.gameSettings.saveOptions();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    // =============================================================================================
    // Drawing

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        poll();
        int cx = width / 2;
        int textWidth = Math.min(320, width - 40);
        int y = centeredParagraph(t(stepKey() + ".text"), scrollTop + 4, textWidth, 0xE0E0E0) + 6;
        JoystickAccess ja = JoystickAccess.get();
        float[] raw = ja.rawAxes();
        switch (step) {
            case CHOOSE_CONTROLLER -> {
                if (shownDevices.isEmpty()) {
                    centeredParagraph(t("cleanfpv.gui.wizard.choose.none"), y + 20, textWidth, 0xFF8080);
                }
            }
            case CENTER, THROTTLE, YAW, PITCH, ROLL -> {
                if (!ja.isConnected()) {
                    centeredParagraph(t("cleanfpv.gui.controller.none"), y, textWidth, 0xFF8080);
                    return;
                }
                if (step != Step.CENTER) {
                    String status = !waitingForCenter ? t("cleanfpv.gui.wizard.stick.waiting")
                            : t("cleanfpv.gui.wizard.stick.recenter", detected.get(axisFor(step)));
                    drawCenteredString(fontRenderer, status, cx, y, waitingForCenter ? 0x55FF55 : 0xFFFF55);
                    y += 14;
                }
                drawRawAxes(raw, cx - 100, y, 200);
            }
            case RANGE -> drawRange(raw, cx - 100, y, 200);
            case VERIFY -> {
                ChannelMap c = draft.channels;
                int size = 64;
                GuiDraw.gimbal(cx - size - 10, y + 4, size,
                        c.readAxis(ChannelMap.Axis.YAW, raw), c.readAxis(ChannelMap.Axis.THROTTLE, raw));
                GuiDraw.gimbal(cx + 10, y + 4, size,
                        c.readAxis(ChannelMap.Axis.ROLL, raw), c.readAxis(ChannelMap.Axis.PITCH, raw));
            }
            case ARM_VERIFY -> {
                boolean on = draft.channels.readSwitch(ChannelMap.Switch.ARM, raw, ja.rawButtons());
                String s = on ? t("cleanfpv.gui.wizard.arm_verify.armed") : t("cleanfpv.gui.wizard.arm_verify.disarmed");
                drawCenteredString(fontRenderer, s, cx, y + 8, on ? 0x55FF55 : 0xFF5555);
            }
            case COMPLETE -> {
                String how = draft != null && draft.scheme.usesJoystick()
                        ? t("cleanfpv.gui.wizard.complete.arm_switch", KeyBindings.ARM.getDisplayName())
                        : t("cleanfpv.gui.wizard.complete.arm_key", KeyBindings.ARM.getDisplayName());
                centeredParagraph(how, y + 4, textWidth, 0x55FF55);
            }
            default -> {
            }
        }
    }

    private void drawRawAxes(float[] raw, int x, int y, int w) {
        for (int i = 0; i < raw.length && y + 10 < height - 56; i++) {
            drawString(fontRenderer, "A" + i, x - 20, y, 0xA0A0A0);
            GuiDraw.valueBar(x, y, w, 8, raw[i], Float.NaN, Float.NaN, 0xFF40A0FF);
            y += 11;
        }
    }

    private void drawRange(float[] raw, int x, int y, int w) {
        int[] required = requiredAxes();
        for (int i = 0; i < raw.length && y + 10 < height - 56; i++) {
            boolean req = false;
            for (int r : required) {
                req |= r == i;
            }
            boolean ok = range.span(i) >= RangeCapture.DEFAULT_REQUIRED_SPAN;
            int color = !req ? 0xA0A0A0 : ok ? 0x55FF55 : 0xFFFF55;
            drawString(fontRenderer, "A" + i, x - 20, y, color);
            GuiDraw.valueBar(x, y, w, 8, raw[i], range.isStarted() ? range.min(i) : Float.NaN,
                    range.isStarted() ? range.max(i) : Float.NaN, 0xFF40A0FF);
            y += 11;
        }
    }
}
