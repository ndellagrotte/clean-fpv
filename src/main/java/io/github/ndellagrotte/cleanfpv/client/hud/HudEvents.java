package io.github.ndellagrotte.cleanfpv.client.hud;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge event subscriber for the OSD: stick overlay, angle/overheat lines, crosshair/hotbar hiding (spec §6.4).
 *
 * <p>Owner: (C) render+hud+audio. Registered on {@code MinecraftForge.EVENT_BUS} by {@code ClientProxy}; add {@code @SubscribeEvent}
 * <em>instance</em> methods here (static subscribers are not picked up by instance registration).
 *
 * <p>Everything is drawn in {@code RenderGameOverlayEvent.Post} {@code ALL}: after vanilla's HUD
 * and after the fisheye post chain, so the OSD is never distorted. Hidden with F1 like the rest of
 * the HUD. The hiding of vanilla elements lives in {@code client.render.HideVanilla}. The race HUD
 * (G) draws from its own subscriber.
 *
 * <p>While armed: the stick overlay (if {@code activeModel().showStickOverlay}), {@code angle: N}
 * (see {@link AngleReadout}) and {@code Overheating...} while
 * {@link ClientDroneContext#isOverheating()}. While disarmed nothing is drawn unless
 * {@link #setStickPreview(boolean)} asks for a live stick preview (input setup / debugging).
 */
public final class HudEvents {

    /** Line height and left margin of the OSD text, px. */
    private static final int LINE = 10;
    private static final int MARGIN = 4;
    private static final int TEXT_COLOUR = 0xFFFFFFFF;
    private static final int WARN_COLOUR = 0xFFFF5A3C;

    private static boolean stickPreview;

    private final AngleReadout angleReadout = new AngleReadout();

    /** Shows the stick overlay while disarmed too (e.g. from the input setup screens). */
    public static void setStickPreview(boolean enabled) {
        stickPreview = enabled;
    }

    public static boolean isStickPreview() {
        return stickPreview;
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        ClientDroneContext ctx = ClientDroneContext.get();
        boolean armed = ctx.isArmed();
        long now = Minecraft.getSystemTime();
        angleReadout.update(armed && ctx.sticks().angleSw(), ctx.sticks().angle(), now);
        if (!armed && !stickPreview) {
            return;
        }

        ScaledResolution res = event.getResolution();
        int width = res.getScaledWidth();
        int height = res.getScaledHeight();

        if (stickPreview || ctx.activeModel().showStickOverlay) {
            // Armed: the hotbar area is hidden, so sit low; disarmed: stay clear of the hotbar/bars.
            int lift = armed ? 30 : mc.playerController.shouldDrawHUD() ? 60 : 40;
            if (armed) {
                StickOverlay.draw(width / 2, height - lift, ctx.sticks(), ctx.overlayMouseRoll(), ctx.overlayMousePitchUp());
            } else {
                StickOverlay.draw(width / 2, height - lift, ctx.sticks());
            }
        }

        if (armed) {
            FontRenderer font = mc.fontRenderer;
            int y = height / 2 - LINE;
            if (angleReadout.visible(now)) {
                font.drawStringWithShadow(I18n.format("cleanfpv.hud.angle", Math.round(ctx.cameraTiltDeg())),
                        MARGIN, y, TEXT_COLOUR);
                y += LINE;
            }
            if (ctx.isOverheating()) {
                font.drawStringWithShadow(I18n.format("cleanfpv.hud.overheating"), MARGIN, y, WARN_COLOUR);
            }
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
