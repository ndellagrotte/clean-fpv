package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.wizard.WizardScreen;
import io.github.ndellagrotte.cleanfpv.client.input.JoystickAccess;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.gui.GuiScreen;

/**
 * Settings home (spec §11.2 "Model settings"): shows the active model and controller and links to
 * every settings screen plus the setup wizard.
 */
public class SettingsHomeScreen extends FpvScreen {

    public SettingsHomeScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.home.title");
    }

    @Override
    protected void build() {
        int cx = width / 2;
        int y = firstRowY();
        int left = cx - 154;
        int right = cx + 4;
        button(left, y, 150, t("cleanfpv.gui.home.models"), () -> mc.displayGuiScreen(new ModelListScreen(this)));
        button(right, y, 150, t("cleanfpv.gui.home.controller"), () -> mc.displayGuiScreen(new ControllerScreen(this)));
        y += ROW;
        button(left, y, 150, t("cleanfpv.gui.home.channels"), () -> mc.displayGuiScreen(new ChannelMappingScreen(this)))
                .enabledWhen(() -> ClientSettings.current().scheme.usesJoystick());
        button(right, y, 150, t("cleanfpv.gui.home.rates"), () -> mc.displayGuiScreen(new RatesScreen(this)));
        y += ROW;
        button(left, y, 150, t("cleanfpv.gui.home.camera"), () -> mc.displayGuiScreen(new CameraScreen(this)));
        button(right, y, 150, t("cleanfpv.gui.home.physics"), () -> mc.displayGuiScreen(new PhysicsScreen(this)));
        y += ROW + 8;
        button(cx - 100, y, 200, t("cleanfpv.gui.home.wizard"), () -> mc.displayGuiScreen(new WizardScreen(this)));
        doneButton();
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        DroneModelConfig m = ClientSettings.current();
        int y = firstRowY() - 32;
        drawCenteredString(fontRenderer, t("cleanfpv.gui.home.active_model", m.name,
                t("cleanfpv.gui.scheme." + m.scheme.name().toLowerCase(java.util.Locale.ROOT))), width / 2, y, 0xFFFFFF);
        drawCenteredString(fontRenderer, controllerLine(m), width / 2, y + 12, 0xA0A0A0);
    }

    private int firstRowY() {
        return Math.max(scrollTop + 40, height / 2 - 60);
    }

    static String controllerLine(DroneModelConfig m) {
        if (!m.scheme.usesJoystick()) {
            return t("cleanfpv.gui.home.controller_keyboard");
        }
        JoystickAccess.Device d = JoystickAccess.get().selected();
        if (d == null) {
            return t("cleanfpv.gui.controller.none");
        }
        return t("cleanfpv.gui.home.controller_line", d.name());
    }
}
