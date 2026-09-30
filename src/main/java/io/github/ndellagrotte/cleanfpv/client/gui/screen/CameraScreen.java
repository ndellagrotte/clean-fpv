package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvSlider;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.ToggleButton;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.gui.GuiScreen;

import java.util.Locale;

/**
 * Camera and display (spec §11.2 "Other", §6.1/§6.4): default (switchless) camera angle, diagonal
 * FOV 30–150°, fisheye, crosshair, block outline and stick overlay.
 */
public class CameraScreen extends FpvScreen {

    /** Default camera angle slider range (degrees). The angle knob itself covers 10–80°. */
    public static final float ANGLE_MIN = 0f;
    public static final float ANGLE_MAX = 90f;

    public CameraScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.camera.title");
    }

    private static DroneModelConfig model() {
        return ClientSettings.current();
    }

    @Override
    protected void build() {
        int cx = width / 2;
        int left = cx - 154;
        int right = cx + 4;
        int y = scrollTop + 8;
        addButton(new FpvSlider(left, y, 304, ANGLE_MIN, ANGLE_MAX, 1.0,
                () -> model().switchlessAngle,
                v -> {
                    model().switchlessAngle = (float) v;
                    ClientSettings.markDirty();
                },
                v -> t("cleanfpv.gui.camera.angle", String.format(Locale.ROOT, "%.0f", v))))
                .tooltip(t("cleanfpv.gui.camera.angle.tooltip"));
        y += ROW;
        addButton(new FpvSlider(left, y, 304, DroneModelConfig.FOV_MIN, DroneModelConfig.FOV_MAX, 1.0,
                () -> model().fov,
                v -> {
                    model().fov = (float) v;
                    ClientSettings.markDirty();
                },
                v -> t("cleanfpv.gui.camera.fov", String.format(Locale.ROOT, "%.0f", v))))
                .tooltip(t("cleanfpv.gui.camera.fov.tooltip"));
        y += ROW;
        addButton(new ToggleButton(left, y, 150, t("cleanfpv.gui.camera.fisheye"),
                () -> model().useFisheye, v -> set(() -> model().useFisheye = v)))
                .tooltip(t("cleanfpv.gui.camera.fisheye.tooltip"));
        addButton(new ToggleButton(right, y, 150, t("cleanfpv.gui.camera.crosshair"),
                () -> model().showCrosshairs, v -> set(() -> model().showCrosshairs = v)));
        y += ROW;
        addButton(new ToggleButton(left, y, 150, t("cleanfpv.gui.camera.outline"),
                () -> model().showBlockOutline, v -> set(() -> model().showBlockOutline = v)));
        addButton(new ToggleButton(right, y, 150, t("cleanfpv.gui.camera.overlay"),
                () -> model().showStickOverlay, v -> set(() -> model().showStickOverlay = v)));
        doneButton();
    }

    private static void set(Runnable r) {
        r.run();
        ClientSettings.markDirty();
    }
}
