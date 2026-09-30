package io.github.ndellagrotte.cleanfpv.client.gui;

import io.github.ndellagrotte.cleanfpv.client.gui.screen.SettingsHomeScreen;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvButton;
import io.github.ndellagrotte.cleanfpv.client.gui.wizard.WizardScreen;
import io.github.ndellagrotte.cleanfpv.client.input.KeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * GUI integration (PLAN §5 "Controls button", §6.7; spec §3.3):
 * <ul>
 *   <li>a 20×20 button at {@code (w/2+160, h−29)} on the vanilla Controls screen, right of the
 *       Reset/Done row (added in {@code InitGuiEvent.Post}, handled in {@code ActionPerformedEvent.Pre});</li>
 *   <li>the Settings key ({@link KeyBindings#SETTINGS}, default O) always opens the settings home
 *       when no GUI is open (checked at {@code ClientTickEvent} END; spec §3.3, PLAN §5);</li>
 *   <li>first run: while {@code firstTimeSetup} is set, the Controls-screen button opens the setup
 *       wizard instead (its Welcome step offers "Returning pilot" to skip it).</li>
 * </ul>
 * The constructor loads the settings ({@link ClientSettings#init()}), so the active model is known
 * before the first world loads.
 */
public final class GuiEvents {

    /** Button id on {@link GuiControls}: must avoid 200/201 and {@code < 100} (option buttons). */
    public static final int CONTROLS_BUTTON_ID = 0xCF50;

    /** The button currently on the open Controls screen (for its tooltip), or null. */
    private FpvButton controlsButton;
    private GuiScreen controlsScreen;

    public GuiEvents() {
        ClientSettings.init();
    }

    /** Opens the settings home over {@code parent} (the O key: never the wizard, spec §3.3). */
    public static void openSettingsHome(GuiScreen parent) {
        Minecraft.getMinecraft().displayGuiScreen(new SettingsHomeScreen(parent));
    }

    /** Controls button: the wizard on first run, else the settings home, over {@code parent}. */
    public static void openSettings(GuiScreen parent) {
        Minecraft mc = Minecraft.getMinecraft();
        if (ClientSettings.store().isFirstTimeSetup()) {
            mc.displayGuiScreen(new WizardScreen(parent));
        } else {
            mc.displayGuiScreen(new SettingsHomeScreen(parent));
        }
    }

    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        GuiScreen gui = event.getGui();
        if (!(gui instanceof GuiControls)) {
            return;
        }
        for (GuiButton b : event.getButtonList()) {
            if (b.id == CONTROLS_BUTTON_ID) {
                return;
            }
        }
        FpvButton button = new FpvButton(gui.width / 2 + 160, gui.height - 29, 20, 20,
                I18n.format("cleanfpv.gui.controls_button"), () -> openSettings(gui));
        button.id = CONTROLS_BUTTON_ID;
        button.tooltip(I18n.format("cleanfpv.gui.controls_button.tooltip"));
        event.getButtonList().add(button);
        controlsButton = button;
        controlsScreen = gui;
    }

    @SubscribeEvent
    public void onAction(GuiScreenEvent.ActionPerformedEvent.Pre event) {
        if (event.getGui() instanceof GuiControls && event.getButton() instanceof FpvButton b
                && b.id == CONTROLS_BUTTON_ID) {
            event.setCanceled(true);
            b.playPressSound(Minecraft.getMinecraft().getSoundHandler());
            b.onPress();
        }
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        FpvButton b = controlsButton;
        if (b == null || event.getGui() != controlsScreen) {
            return;
        }
        if (b.visible && b.isMouseOver() && b.tooltip() != null) {
            event.getGui().drawHoveringText(b.tooltip(), event.getMouseX(), event.getMouseY());
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean pressed = false;
        while (KeyBindings.SETTINGS.isPressed()) {
            pressed = true;
        }
        if (pressed && mc.currentScreen == null && mc.world != null) {
            openSettingsHome(null);
        }
    }
}
