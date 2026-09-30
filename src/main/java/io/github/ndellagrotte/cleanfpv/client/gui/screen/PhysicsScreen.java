package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvSlider;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.NumberField;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.ToggleButton;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.gui.GuiScreen;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Flight and build (spec §11.2 "Drone build" + physics options): physics timing (per tick or per
 * frame), 3D mode, high fidelity, and every {@link DroneBuild} field with its spec range, grouped
 * Mass / Battery / Motors / Props / Frame / Accessories / Colours. Built-in presets are read-only
 * here (their build is fixed, PLAN §6.7); the flight options stay editable.
 */
public class PhysicsScreen extends FpvScreen {

    private int column;
    private int rowY;

    public PhysicsScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.physics.title");
    }

    private static DroneModelConfig model() {
        return ClientSettings.current();
    }

    private static DroneBuild b() {
        return model().build;
    }

    private static boolean editable() {
        return !model().preset;
    }

    @Override
    protected void build() {
        int cx = width / 2;
        int left = cx - 154;
        int right = cx + 4;
        int y = scrollTop + 4;
        scrolled(new ToggleButton(left, y, 150, t("cleanfpv.gui.physics.timing"),
                () -> model().useRealtimePhysics, v -> set(() -> model().useRealtimePhysics = v),
                t("cleanfpv.gui.physics.timing.frame"), t("cleanfpv.gui.physics.timing.tick")))
                .tooltip(t("cleanfpv.gui.physics.timing.tooltip"));
        scrolled(new ToggleButton(right, y, 150, t("cleanfpv.gui.physics.mode3d"),
                () -> model().flightMode3d, v -> set(() -> model().flightMode3d = v)))
                .tooltip(t("cleanfpv.gui.physics.mode3d.tooltip"));
        y += ROW;
        scrolled(new ToggleButton(left, y, 150, t("cleanfpv.gui.physics.fidelity"),
                () -> model().highFidelity, v -> set(() -> model().highFidelity = v)))
                .tooltip(t("cleanfpv.gui.physics.fidelity.tooltip"));
        y += ROW + 4;
        if (!editable()) {
            scrolledLabel(t("cleanfpv.gui.physics.preset_locked"), left, y, 0xFFAA55);
            y += 12;
        }

        rowY = y;
        column = 0;
        group(t("cleanfpv.gui.build.mass"));
        number(t("cleanfpv.gui.build.mass_g"), () -> b().mass, v -> b().mass = (float) v, () -> 0, () -> 100000, true,
                t("cleanfpv.gui.build.mass.tooltip"));
        group(t("cleanfpv.gui.build.battery"));
        number(t("cleanfpv.gui.build.cells"), () -> b().batteryCells, v -> b().batteryCells = (int) v, () -> 1, () -> 12, true, null);
        number(t("cleanfpv.gui.build.mah"), () -> b().batteryMah, v -> b().batteryMah = (int) v, () -> 300, () -> 100000, true, null);
        group(t("cleanfpv.gui.build.motors"));
        number(t("cleanfpv.gui.build.kv"), () -> b().motorKv, v -> b().motorKv = (float) v, () -> DroneBuild.MIN_KV, () -> 20000, false, null);
        number(t("cleanfpv.gui.build.motor_width"), () -> b().motorWidth, v -> b().motorWidth = (float) v, () -> 5, () -> 100, false, null);
        number(t("cleanfpv.gui.build.motor_height"), () -> b().motorHeight, v -> b().motorHeight = (float) v, () -> 2, () -> 100, false, null);
        group(t("cleanfpv.gui.build.props"));
        number(t("cleanfpv.gui.build.prop_diameter"), () -> b().propDiameter, v -> b().propDiameter = (float) v, () -> 1.5, () -> 13, false, null);
        number(t("cleanfpv.gui.build.prop_pitch"), () -> b().propPitch, v -> b().propPitch = (float) v, () -> 0, () -> 20, false, null);
        number(t("cleanfpv.gui.build.blades"), () -> b().blades, v -> b().blades = (int) v, () -> 2, () -> 16, true, null);
        number(t("cleanfpv.gui.build.blade_width"), () -> b().bladeWidth, v -> b().bladeWidth = (float) v,
                () -> 6, () -> Math.max(6, b().motorWidth), false, t("cleanfpv.gui.build.blade_width.tooltip"));
        group(t("cleanfpv.gui.build.frame"));
        number(t("cleanfpv.gui.build.frame_width"), () -> b().frameWidth, v -> b().frameWidth = (float) v, () -> 25, () -> 5000, false, null);
        number(t("cleanfpv.gui.build.frame_height"), () -> b().frameHeight, v -> b().frameHeight = (float) v, () -> 25, () -> 5000, false, null);
        number(t("cleanfpv.gui.build.frame_length"), () -> b().frameLength, v -> b().frameLength = (float) v, () -> 25, () -> 5000, false, null);
        number(t("cleanfpv.gui.build.arm_width"), () -> b().armWidth, v -> b().armWidth = (float) v, () -> 5, () -> 500, false, null);
        number(t("cleanfpv.gui.build.arm_thickness"), () -> b().armThickness, v -> b().armThickness = (float) v, () -> 5, () -> 500, false, null);
        group(t("cleanfpv.gui.build.accessories"));
        number(t("cleanfpv.gui.build.antenna"), () -> b().antennaLength, v -> b().antennaLength = (float) v, () -> 5, () -> 200, false, null);
        nextRow();
        scrolled(new ToggleButton(left, rowY, 150, t("cleanfpv.gui.build.pro_cam"),
                () -> b().showProCam, v -> set(() -> b().showProCam = v))).enabledWhen(PhysicsScreen::editable);
        scrolled(new ToggleButton(right, rowY, 150, t("cleanfpv.gui.build.hero_cam"),
                () -> b().heroCam, v -> set(() -> b().heroCam = v)))
                .enabledWhen(PhysicsScreen::editable)
                .tooltip(t("cleanfpv.gui.build.hero_cam.tooltip"));
        rowY += ROW;
        group(t("cleanfpv.gui.build.colours"));
        colour(left, t("cleanfpv.gui.build.red"), () -> b().red, v -> b().red = (float) v);
        colour(right, t("cleanfpv.gui.build.green"), () -> b().green, v -> b().green = (float) v);
        rowY += ROW;
        colour(left, t("cleanfpv.gui.build.blue"), () -> b().blue, v -> b().blue = (float) v);
        rowY += ROW;
        doneButton();
    }

    private void group(String title) {
        nextRow();
        rowY += 4;
        scrolledLabel(title, width / 2 - 154, rowY, 0xFFFF55);
        rowY += 12;
    }

    private void nextRow() {
        if (column != 0) {
            column = 0;
            rowY += 20;
        }
    }

    private void number(String label, DoubleSupplier get, DoubleConsumer setValue, DoubleSupplier min, DoubleSupplier max,
                        boolean integer, String tooltip) {
        int x = column == 0 ? width / 2 - 154 : width / 2 + 4;
        scrolledLabel(label, x, rowY + 4, 0xE0E0E0);
        NumberField f = scrolled(new NumberField(fontRenderer, x + 100, rowY, 48, get, v -> {
            if (editable()) {
                setValue.accept(v);
                ClientSettings.markDirty();
            }
        }, min, max, integer));
        f.setEnabled(editable());
        if (tooltip != null) {
            f.tooltip(tooltip);
        }
        column++;
        if (column == 2) {
            column = 0;
            rowY += 20;
        }
    }

    private void colour(int x, String name, DoubleSupplier get, DoubleConsumer setValue) {
        scrolled(new FpvSlider(x, rowY, 150, 0.0, 1.0, 0.01, get, v -> {
            if (editable()) {
                setValue.accept(v);
                ClientSettings.markDirty();
            }
        }, v -> name + ": " + String.format(Locale.ROOT, "%.2f", v))).enabledWhen(PhysicsScreen::editable);
    }

    private static void set(Runnable r) {
        r.run();
        ClientSettings.markDirty();
    }
}
