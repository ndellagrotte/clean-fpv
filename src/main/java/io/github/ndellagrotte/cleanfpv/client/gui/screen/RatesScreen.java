package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.flight.Rates;
import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.NumberField;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.RateChart;
import io.github.ndellagrotte.cleanfpv.client.input.JoystickAccess;
import io.github.ndellagrotte.cleanfpv.client.input.StickState;
import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import io.github.ndellagrotte.cleanfpv.common.config.RateTriple;
import net.minecraft.client.gui.GuiScreen;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Rates (spec §11.2): typed Rate (0–3) / Super (0–1) / Expo (0–1) per axis with a live
 * "max deg/s" header, the rate chart with live stick markers, and the two resets ("Gamepad rates" =
 * the spec's Slow Reset, "Radio rates" = Fast Reset).
 */
public class RatesScreen extends FpvScreen {

    private static final String[] AXES = {"roll", "pitch", "yaw"};

    public RatesScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.rates.title");
    }

    private static DroneModelConfig model() {
        return ClientSettings.current();
    }

    private static RateTriple triple(int axis) {
        DroneModelConfig m = model();
        return switch (axis) {
            case 0 -> m.rollRates;
            case 1 -> m.pitchRates;
            default -> m.yawRates;
        };
    }

    private int tableLeft() {
        return width / 2 - 154;
    }

    @Override
    protected void build() {
        int left = tableLeft();
        int y = scrollTop + 16;
        label(t("cleanfpv.gui.rates.rate"), left + 90, y - 12, 0xA0A0A0);
        label(t("cleanfpv.gui.rates.super"), left + 136, y - 12, 0xA0A0A0);
        label(t("cleanfpv.gui.rates.expo"), left + 182, y - 12, 0xA0A0A0);
        for (int a = 0; a < 3; a++) {
            final int axis = a;
            label(() -> t("cleanfpv.gui.axis." + AXES[axis]), left, y + 4, RateChart.COLORS[a] & 0xFFFFFF, false);
            label(maxLabel(axis), left + 38, y + 4, 0xC0C0C0, false);
            field(new NumberField(fontRenderer, left + 90, y, 42, () -> triple(axis).rate,
                    v -> setTriple(axis, (float) v, triple(axis).superRate, triple(axis).expo),
                    RateTriple.RATE_MIN, RateTriple.RATE_MAX, false).tooltip(t("cleanfpv.gui.rates.rate.tooltip")));
            field(new NumberField(fontRenderer, left + 136, y, 42, () -> triple(axis).superRate,
                    v -> setTriple(axis, triple(axis).rate, (float) v, triple(axis).expo),
                    RateTriple.SUPER_MIN, RateTriple.SUPER_MAX, false).tooltip(t("cleanfpv.gui.rates.super.tooltip")));
            field(new NumberField(fontRenderer, left + 182, y, 42, () -> triple(axis).expo,
                    v -> setTriple(axis, triple(axis).rate, triple(axis).superRate, (float) v),
                    RateTriple.EXPO_MIN, RateTriple.EXPO_MAX, false).tooltip(t("cleanfpv.gui.rates.expo.tooltip")));
            y += 20;
        }
        y += 6;
        button(left, y, 110, t("cleanfpv.gui.rates.gamepad_reset"), () -> {
            model().applyGamepadRates();
            ClientSettings.markDirty();
        }).tooltip(t("cleanfpv.gui.rates.gamepad_reset.tooltip"));
        button(left + 114, y, 110, t("cleanfpv.gui.rates.radio_reset"), () -> {
            model().applyRadioRates();
            ClientSettings.markDirty();
        }).tooltip(t("cleanfpv.gui.rates.radio_reset.tooltip"));
        doneButton();
    }

    private Supplier<String> maxLabel(int axis) {
        return () -> String.format(Locale.ROOT, "%d°/s", Math.round(Math.abs(Rates.rateDegPerSec(1.0, triple(axis)))));
    }

    private static void setTriple(int axis, float rate, float superRate, float expo) {
        if (triple(axis).set(rate, superRate, expo)) {
            ClientSettings.markDirty();
        }
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        int left = tableLeft();
        int top = scrollTop + 16 + 3 * 20 + 6 + 24;
        int h = Math.max(40, scrollBottom - top - 12);
        int w = 304;
        RateTriple[] rates = {triple(0), triple(1), triple(2)};
        RateChart.draw(fontRenderer, left, top, w, h, rates, liveSticks());
        drawCenteredString(fontRenderer, t("cleanfpv.gui.rates.move_sticks"), width / 2, top + h + 2, 0x808080);
    }

    /** |roll|, |pitch|, |yaw| from the selected joystick through this model's mapping, else the context. */
    private static float[] liveSticks() {
        DroneModelConfig m = model();
        JoystickAccess ja = JoystickAccess.get();
        if (m.scheme.usesJoystick() && ja.isConnected()) {
            float[] raw = ja.rawAxes();
            ChannelMap c = m.channels;
            return new float[] {
                    Math.abs(c.readAxis(ChannelMap.Axis.ROLL, raw)),
                    Math.abs(c.readAxis(ChannelMap.Axis.PITCH, raw)),
                    Math.abs(c.readAxis(ChannelMap.Axis.YAW, raw))};
        }
        StickState s = ClientDroneContext.get().sticks();
        return new float[] {Math.abs(s.roll()), Math.abs(s.pitch()), Math.abs(s.yaw())};
    }
}
