package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.RenderBlockOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;

/**
 * Vanilla elements hidden while the local pilot is armed (spec §4.1, §6.4; PLAN §5 row
 * "Hand/hotbar/crosshair/overlays"). Decisions:
 * <ul>
 *   <li>Hand: always hidden ({@code RenderHandEvent}).</li>
 *   <li>Hotbar area: the hotbar and everything that sits on it (experience/jump bar, health,
 *       mount health, food, armour, air) — a lone half-hotbar makes no sense on an FPV feed, and
 *       the spec's "hotbar always hidden" is meant as a clean view. Hurt shake still signals damage.</li>
 *   <li>Crosshair: hidden unless {@link DroneModelConfig#showCrosshairs}.</li>
 *   <li>"Inside a block" overlay: hidden ({@code OverlayType.BLOCK} only; fire and water overlays
 *       stay, as in the spec).</li>
 *   <li>Block outline: hidden unless {@link DroneModelConfig#showBlockOutline}.</li>
 * </ul>
 * Spectating an armed pilot while disarmed hides nothing (vanilla spectator already has no hand
 * or hotbar).
 */
public final class HideVanilla {

    private HideVanilla() {}

    private static boolean armed() {
        return ClientDroneContext.get().isArmed();
    }

    static void onRenderHand(RenderHandEvent event) {
        if (armed()) {
            event.setCanceled(true);
        }
    }

    static void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (!armed()) {
            return;
        }
        switch (event.getType()) {
            case HOTBAR, EXPERIENCE, JUMPBAR, HEALTH, HEALTHMOUNT, FOOD, ARMOR, AIR -> event.setCanceled(true);
            case CROSSHAIRS -> {
                if (!ClientDroneContext.get().activeModel().showCrosshairs) {
                    event.setCanceled(true);
                }
            }
            default -> {
            }
        }
    }

    static void onBlockOverlay(RenderBlockOverlayEvent event) {
        if (armed() && event.getOverlayType() == RenderBlockOverlayEvent.OverlayType.BLOCK) {
            event.setCanceled(true);
        }
    }

    static void onBlockHighlight(DrawBlockHighlightEvent event) {
        if (armed() && !ClientDroneContext.get().activeModel().showBlockOutline) {
            event.setCanceled(true);
        }
    }
}
