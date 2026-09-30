package io.github.ndellagrotte.cleanfpv.client.render;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.RenderBlockOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Forge event subscriber for rendering: {@code CameraSetup} override, {@code FOVModifier}, fisheye post chain, drone model, prop discs, vanilla hides (PLAN §5, §6.4).
 *
 * <p>Owner: (C) render+hud+audio. Registered on {@code MinecraftForge.EVENT_BUS} by {@code ClientProxy}; add {@code @SubscribeEvent}
 * <em>instance</em> methods here (static subscribers are not picked up by instance registration).
 *
 * <p>Per frame (D2 order): {@code RenderTickEvent} START → {@link Fisheye#update()};
 * {@code FOVModifier}/{@code CameraSetup} → {@link CameraHooks}; entity pass →
 * {@link DroneRenderer} (records discs); {@code RenderWorldLastEvent} → fisheye uniforms, then
 * {@link PropDiscs}; post chain; HUD. Per client tick: cache eviction. The logic lives in the
 * package's helper classes; this class only routes events.
 */
public final class RenderEvents {

    // --- Camera -------------------------------------------------------------------------------

    /** Low priority: the drone pose replaces whatever other handlers wrote. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        CameraHooks.onCameraSetup(event);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onFovModifier(EntityViewRenderEvent.FOVModifier event) {
        CameraHooks.onFovModifier(event);
    }

    // --- Fisheye / prop discs ---------------------------------------------------------------------

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            Fisheye.update();
        }
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Fisheye.applyUniforms();
        PropDiscs.renderAll();
    }

    // --- Drone model --------------------------------------------------------------------------

    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        DroneRenderer.onRenderPlayerPre(event);
    }

    // --- Vanilla hides -----------------------------------------------------------------------

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        HideVanilla.onRenderHand(event);
    }

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        HideVanilla.onOverlayPre(event);
    }

    @SubscribeEvent
    public void onBlockOverlay(RenderBlockOverlayEvent event) {
        HideVanilla.onBlockOverlay(event);
    }

    @SubscribeEvent
    public void onBlockHighlight(DrawBlockHighlightEvent event) {
        HideVanilla.onBlockHighlight(event);
    }

    // --- Housekeeping ----------------------------------------------------------------------------

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            DroneRenderer.prune(Minecraft.getSystemTime());
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            DroneRenderer.invalidateAll();
        }
    }

    /** Fires on the netty thread (possibly twice): hop to the client thread; idempotent. */
    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            DroneRenderer.invalidateAll();
            Fisheye.disable();
        });
    }
}
