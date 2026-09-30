package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.RangeCapture;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvSlider;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.GuiDraw;
import io.github.ndellagrotte.cleanfpv.client.input.InputManager;
import io.github.ndellagrotte.cleanfpv.client.input.JoystickAccess;
import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import io.github.ndellagrotte.cleanfpv.common.config.ControllerScheme;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import java.util.List;
import java.util.Locale;

/**
 * Controller and mouse setup: control scheme, device (selected by SDL GUID), channel defaults,
 * calibration capture of every axis, live axis/button monitor, and the mouse gain (PLAN §6.1).
 */
public class ControllerScreen extends FpvScreen {

    private final RangeCapture capture = new RangeCapture();
    private boolean calibrating;

    public ControllerScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.controller.title");
    }

    private static DroneModelConfig model() {
        return ClientSettings.current();
    }

    @Override
    protected void build() {
        int cx = width / 2;
        int left = cx - 154;
        int right = cx + 4;
        int y = scrollTop + 4;
        button(left, y, 150, "", this::cycleScheme)
                .label(() -> t("cleanfpv.gui.controller.scheme",
                        t("cleanfpv.gui.scheme." + model().scheme.name().toLowerCase(Locale.ROOT))))
                .tooltip(t("cleanfpv.gui.controller.scheme.tooltip"));
        button(right, y, 150, "", this::cycleDevice)
                .label(this::deviceLabel)
                .enabledWhen(() -> model().scheme.usesJoystick() && !JoystickAccess.get().devices().isEmpty());
        y += ROW;
        button(left, y, 150, t("cleanfpv.gui.controller.defaults"), this::applyDefaults)
                .enabledWhen(() -> model().scheme.usesJoystick())
                .tooltip(t("cleanfpv.gui.controller.defaults.tooltip"));
        button(right, y, 150, t("cleanfpv.gui.home.channels"), () -> mc.displayGuiScreen(new ChannelMappingScreen(this)))
                .enabledWhen(() -> model().scheme.usesJoystick());
        y += ROW;
        button(left, y, 150, "", this::toggleCalibration)
                .label(() -> calibrating ? t("cleanfpv.gui.controller.calibrate.finish")
                        : t("cleanfpv.gui.controller.calibrate.start"))
                .enabledWhen(() -> model().scheme.usesJoystick() && JoystickAccess.get().isConnected())
                .tooltip(t("cleanfpv.gui.controller.calibrate.tooltip"));
        button(right, y, 150, t("cleanfpv.gui.controller.calibrate.reset"), this::resetCalibration)
                .enabledWhen(() -> model().scheme.usesJoystick() && !calibrating);
        y += ROW;
        addButton(new FpvSlider(left, y, 304, 0.1, 10.0, 0.1,
                () -> model().mouseGain,
                v -> {
                    model().mouseGain = (float) v;
                    ClientSettings.markDirty();
                },
                v -> t("cleanfpv.gui.controller.mouse_gain", String.format(Locale.ROOT, "%.1f", v))))
                .tooltip(t("cleanfpv.gui.controller.mouse_gain.tooltip"));
        doneButton();
    }

    private int monitorTop() {
        return scrollTop + 4 + 4 * ROW + 6;
    }

    private void cycleScheme() {
        DroneModelConfig m = model();
        ControllerScheme[] all = ControllerScheme.values();
        m.scheme = all[(m.scheme.ordinal() + 1) % all.length];
        ClientSettings.markDirty();
    }

    private String deviceLabel() {
        DroneModelConfig m = model();
        if (!m.scheme.usesJoystick()) {
            return t("cleanfpv.gui.controller.device_unused");
        }
        JoystickAccess.Device d = JoystickAccess.get().selected();
        if (d == null) {
            return t("cleanfpv.gui.controller.none");
        }
        String name = d.name().isEmpty() ? d.guid() : d.name();
        return fontRenderer.trimStringToWidth(name, 140);
    }

    private void cycleDevice() {
        List<JoystickAccess.Device> devices = JoystickAccess.get().devices();
        if (devices.isEmpty()) {
            return;
        }
        JoystickAccess.Device cur = JoystickAccess.get().selected();
        int idx = cur == null ? -1 : devices.indexOf(cur);
        if (idx < 0 && cur != null) {
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).guid().equals(cur.guid())) {
                    idx = i;
                }
            }
        }
        JoystickAccess.Device next = devices.get((idx + 1) % devices.size());
        selectDevice(model(), next);
        ClientSettings.markDirty();
    }

    /** Persists the device in the model and selects it for polling. */
    public static void selectDevice(DroneModelConfig m, JoystickAccess.Device d) {
        m.controllerGuid = d.guid();
        m.controllerName = d.name();
        m.channels.ensureAxisCount(d.axisCount());
        JoystickAccess.get().select(d.guid());
    }

    private void applyDefaults() {
        DroneModelConfig m = model();
        if (m.scheme == ControllerScheme.GAMEPAD) {
            // SDL gamepad mapping of the selected device when known (PLAN §6.1), else the fixed table.
            m.channels = InputManager.get().gamepadDefaults();
        } else {
            m.channels = ChannelMap.radioDefaults();
        }
        m.channels.ensureAxisCount(ChannelMap.DEFAULT_AXIS_SLOTS);
        ClientSettings.markDirty();
    }

    private void toggleCalibration() {
        if (!calibrating) {
            capture.reset();
            calibrating = true;
            return;
        }
        calibrating = false;
        if (capture.isStarted()) {
            capture.applyTo(model().channels);
            ClientSettings.markDirty();
        }
    }

    private void resetCalibration() {
        ChannelMap c = model().channels;
        c.resetCalibration(Math.max(ChannelMap.DEFAULT_AXIS_SLOTS, c.calMin == null ? 0 : c.calMin.length));
        ClientSettings.markDirty();
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        DroneModelConfig m = model();
        int top = monitorTop();
        int cx = width / 2;
        int left = cx - 154;
        if (!m.scheme.usesJoystick()) {
            centeredParagraph(t("cleanfpv.gui.controller.keyboard_help"), top + 4, 300, 0xC0C0C0);
            return;
        }
        JoystickAccess ja = JoystickAccess.get();
        float[] raw = ja.rawAxes();
        boolean[] buttons = ja.rawButtons();
        if (calibrating) {
            capture.update(raw, Minecraft.getSystemTime());
            drawCenteredString(fontRenderer, t("cleanfpv.gui.controller.calibrate.hint"), cx, top, 0xFFFF55);
        } else if (!ja.isConnected()) {
            centeredParagraph(t("cleanfpv.gui.controller.none_help"), top + 4, 300, 0xFF8080);
            return;
        } else {
            drawCenteredString(fontRenderer, t("cleanfpv.gui.controller.monitor"), cx, top, 0xA0A0A0);
        }
        int y = top + 12;
        int available = scrollBottom - y - 14;
        int perAxis = 11;
        int columns = raw.length * perAxis > available ? 2 : 1;
        int colWidth = columns == 2 ? 150 : 304;
        int rows = (raw.length + columns - 1) / columns;
        for (int i = 0; i < raw.length; i++) {
            int col = i / Math.max(rows, 1);
            int row = i % Math.max(rows, 1);
            int ax = left + col * 154;
            int ay = y + row * perAxis;
            if (ay + perAxis > scrollBottom - 12) {
                continue;
            }
            String roles = roles(m.channels, i, raw.length);
            String lbl = "A" + i + (roles.isEmpty() ? "" : " " + roles);
            drawString(fontRenderer, fontRenderer.trimStringToWidth(lbl, 60), ax, ay, 0xE0E0E0);
            float markMin;
            float markMax;
            if (calibrating && capture.isStarted()) {
                markMin = capture.min(i);
                markMax = capture.max(i);
            } else {
                markMin = i < m.channels.calMin.length ? m.channels.calMin[i] : Float.NaN;
                markMax = i < m.channels.calMax.length ? m.channels.calMax[i] : Float.NaN;
            }
            GuiDraw.valueBar(ax + 62, ay, colWidth - 64, 8, raw[i], markMin, markMax, 0xFF40A0FF);
        }
        int by = y + Math.min(rows * perAxis, available) + 2;
        if (by + 8 <= scrollBottom) {
            int bx = left;
            drawString(fontRenderer, t("cleanfpv.gui.controller.buttons"), bx, by, 0xA0A0A0);
            bx += fontRenderer.getStringWidth(t("cleanfpv.gui.controller.buttons")) + 4;
            for (int i = 0; i < buttons.length && bx + 8 <= left + 304; i++) {
                GuiDraw.rect(bx, by, bx + 7, by + 7, buttons[i] ? 0xFF40FF40 : 0xFF404040);
                bx += 9;
            }
        }
    }

    /** Short role tags ("T", "R", "P", "Y", "C", switches "Arm"...) of axis {@code i}. */
    static String roles(ChannelMap c, int axis, int axisCount) {
        StringBuilder sb = new StringBuilder();
        for (ChannelMap.Axis a : ChannelMap.Axis.values()) {
            if (c.axisIndex(a) == axis) {
                sb.append(t("cleanfpv.gui.axis.short." + a.name().toLowerCase(Locale.ROOT)));
            }
        }
        for (ChannelMap.Switch s : ChannelMap.Switch.values()) {
            int idx = c.switchIndex(s);
            if (ChannelMap.isVirtual(idx) && axisCount + idx == axis) {
                sb.append(t("cleanfpv.gui.switch.short." + s.name().toLowerCase(Locale.ROOT)));
            }
        }
        return sb.toString();
    }

    @Override
    public void onGuiClosed() {
        calibrating = false;
        super.onGuiClosed();
    }
}
