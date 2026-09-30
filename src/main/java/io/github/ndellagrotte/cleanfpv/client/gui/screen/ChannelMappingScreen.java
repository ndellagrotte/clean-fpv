package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.AxisDetector;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.SwitchDetector;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvButton;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.GuiDraw;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.NumberField;
import io.github.ndellagrotte.cleanfpv.client.input.JoystickAccess;
import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.init.SoundEvents;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/**
 * Channel mapping (spec §11.2): five axis rows (typed index, "assign by moving", Normal/Inverted,
 * live calibrated value) and three switch rows (button or virtual axis switch, "assign by flipping",
 * Normal/Inverted, live state). All eight invert flags persist (PLAN §6.1).
 */
public class ChannelMappingScreen extends FpvScreen {

    private static final long ASSIGN_TIMEOUT_MS = 10_000L;
    private static final int MAX_AXIS_INDEX = 31;

    private final AxisDetector axisDetector = new AxisDetector();
    private final SwitchDetector switchDetector = new SwitchDetector();
    private ChannelMap.Axis assigningAxis;
    private ChannelMap.Switch assigningSwitch;
    private long assignStartMs;
    private int firstRowY;

    public ChannelMappingScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.channels.title");
    }

    private static ChannelMap channels() {
        return ClientSettings.current().channels;
    }

    @Override
    protected void build() {
        scrollBottom = height - 44;
        int cx = width / 2;
        int y = scrollTop + 16;
        firstRowY = y;
        scrolledLabel(t("cleanfpv.gui.channels.axes"), cx - 154, y - 12, 0xFFFF55);
        for (ChannelMap.Axis a : ChannelMap.Axis.values()) {
            final ChannelMap.Axis axis = a;
            scrolledLabel(axisName(a), cx - 154, y + 6, 0xFFFFFF);
            scrolled(new NumberField(fontRenderer, cx - 20, y + 2, 34,
                    () -> channels().axisIndex(axis),
                    v -> {
                        channels().setAxisIndex(axis, (int) v);
                        ClientSettings.markDirty();
                    }, 0, MAX_AXIS_INDEX, true).tooltip(t("cleanfpv.gui.channels.index.tooltip")));
            scrolled(new FpvButton(cx + 18, y, 66, 20, "", () -> startAssign(axis))
                    .label(() -> assigningAxis == axis ? t("cleanfpv.gui.channels.moving") : t("cleanfpv.gui.channels.assign"))
                    .enabledWhen(() -> JoystickAccess.get().isConnected())
                    .tooltip(t("cleanfpv.gui.channels.assign_axis.tooltip")));
            scrolled(new FpvButton(cx + 88, y, 66, 20, "", () -> {
                channels().setInverted(axis, !channels().isInverted(axis));
                ClientSettings.markDirty();
            }).label(() -> channels().isInverted(axis) ? t("cleanfpv.gui.channels.inverted") : t("cleanfpv.gui.channels.normal")));
            y += ROW;
        }
        y += 14;
        scrolledLabel(t("cleanfpv.gui.channels.switches"), cx - 154, y - 12, 0xFFFF55);
        for (ChannelMap.Switch s : ChannelMap.Switch.values()) {
            final ChannelMap.Switch sw = s;
            scrolledLabel(switchName(s), cx - 154, y + 6, 0xFFFFFF);
            scrolledLabel(() -> switchSource(sw), cx - 84, y + 6, 0xA0A0A0);
            scrolled(new FpvButton(cx + 18, y, 66, 20, "", () -> startAssign(sw))
                    .label(() -> assigningSwitch == sw ? t("cleanfpv.gui.channels.flipping") : t("cleanfpv.gui.channels.assign"))
                    .enabledWhen(() -> JoystickAccess.get().isConnected())
                    .tooltip(t("cleanfpv.gui.channels.assign_switch.tooltip")));
            scrolled(new FpvButton(cx + 88, y, 66, 20, "", () -> {
                channels().setInverted(sw, !channels().isInverted(sw));
                ClientSettings.markDirty();
            }).label(() -> channels().isInverted(sw) ? t("cleanfpv.gui.channels.inverted") : t("cleanfpv.gui.channels.normal")));
            y += ROW;
        }
        doneButton();
    }

    static String axisName(ChannelMap.Axis a) {
        return t("cleanfpv.gui.axis." + a.name().toLowerCase(Locale.ROOT));
    }

    static String switchName(ChannelMap.Switch s) {
        return t("cleanfpv.gui.switch." + s.name().toLowerCase(Locale.ROOT));
    }

    private String switchSource(ChannelMap.Switch s) {
        int idx = channels().switchIndex(s);
        if (idx >= 0) {
            return t("cleanfpv.gui.channels.button_n", idx);
        }
        int axes = JoystickAccess.get().rawAxes().length;
        if (axes == 0) {
            return t("cleanfpv.gui.channels.virtual_rel", idx);
        }
        return t("cleanfpv.gui.channels.virtual_n", axes + idx);
    }

    private void startAssign(ChannelMap.Axis a) {
        cancelAssign();
        assigningAxis = a;
        axisDetector.snapshot(JoystickAccess.get().rawAxes());
        assignStartMs = Minecraft.getSystemTime();
    }

    private void startAssign(ChannelMap.Switch s) {
        cancelAssign();
        assigningSwitch = s;
        JoystickAccess ja = JoystickAccess.get();
        switchDetector.snapshot(ja.rawAxes(), ja.rawButtons());
        assignStartMs = Minecraft.getSystemTime();
    }

    private void cancelAssign() {
        assigningAxis = null;
        assigningSwitch = null;
    }

    private void pollAssign() {
        if (assigningAxis == null && assigningSwitch == null) {
            return;
        }
        if (Minecraft.getSystemTime() - assignStartMs > ASSIGN_TIMEOUT_MS) {
            cancelAssign();
            return;
        }
        JoystickAccess ja = JoystickAccess.get();
        float[] raw = ja.rawAxes();
        if (assigningAxis != null) {
            int axis = axisDetector.detect(raw);
            if (axis >= 0) {
                ChannelMap c = channels();
                c.setAxisIndex(assigningAxis, axis);
                c.setInverted(assigningAxis, axisDetector.delta(axis, raw) < 0f);
                c.ensureAxisCount(raw.length);
                done();
            }
        } else {
            boolean[] buttons = ja.rawButtons();
            int idx = switchDetector.detect(raw, buttons);
            if (idx != SwitchDetector.NONE) {
                ChannelMap c = channels();
                c.setSwitchIndex(assigningSwitch, idx);
                c.setInverted(assigningSwitch, SwitchDetector.invertForOnNow(c, idx, raw, buttons));
                done();
            }
        }
    }

    private void done() {
        cancelAssign();
        ClientSettings.markDirty();
        mc.getSoundHandler().playSound(PositionedSoundRecord.getMasterRecord(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE && (assigningAxis != null || assigningSwitch != null)) {
            cancelAssign();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        pollAssign();
        int cx = width / 2;
        JoystickAccess ja = JoystickAccess.get();
        float[] raw = ja.rawAxes();
        boolean[] buttons = ja.rawButtons();
        ChannelMap c = channels();
        int y = firstRowY;
        for (ChannelMap.Axis a : ChannelMap.Axis.values()) {
            int sy = toScreenY(y);
            if (sy >= scrollTop && sy + 20 <= scrollBottom) {
                GuiDraw.valueBar(cx - 84, sy + 6, 60, 8, c.readAxis(a, raw), Float.NaN, Float.NaN, 0xFF40A0FF);
            }
            y += ROW;
        }
        y += 14;
        for (ChannelMap.Switch s : ChannelMap.Switch.values()) {
            int sy = toScreenY(y);
            if (sy >= scrollTop && sy + 20 <= scrollBottom) {
                boolean on = c.readSwitch(s, raw, buttons);
                GuiDraw.rect(cx + 4, sy + 5, cx + 14, sy + 15, on ? 0xFF40FF40 : 0xFF404040);
            }
            y += ROW;
        }
        if (assigningAxis != null) {
            drawCenteredString(fontRenderer, t("cleanfpv.gui.channels.hint_axis", axisName(assigningAxis)),
                    width / 2, scrollBottom + 2, 0xFFFF55);
        } else if (assigningSwitch != null) {
            drawCenteredString(fontRenderer, t("cleanfpv.gui.channels.hint_switch", switchName(assigningSwitch)),
                    width / 2, scrollBottom + 2, 0xFFFF55);
        } else if (!ja.isConnected()) {
            drawCenteredString(fontRenderer, t("cleanfpv.gui.controller.none"), width / 2, scrollBottom + 2, 0xFF8080);
        }
    }

    @Override
    public void onGuiClosed() {
        cancelAssign();
        super.onGuiClosed();
    }
}
