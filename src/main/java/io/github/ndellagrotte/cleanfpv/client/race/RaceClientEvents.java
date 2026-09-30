package io.github.ndellagrotte.cleanfpv.client.race;

import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Forge event subscriber for client race UX (spec §9): gate wireframes and the camera capture in
 * {@code RenderWorldLastEvent} ({@link GateRenderer}), the next-gate ring in
 * {@code RenderGameOverlayEvent.Post} {@code ALL} and the leaderboard text in
 * {@code RenderGameOverlayEvent.Text} ({@link RaceHud}); clears {@link RaceClientState} on
 * disconnect and on client world unload (which includes every dimension change: the server
 * re-sends the race state after respawn / dimension change).
 *
 * <p>Owner: (G) race. Registered on {@code MinecraftForge.EVENT_BUS} by {@code ClientProxy}; add {@code @SubscribeEvent}
 * <em>instance</em> methods here (static subscribers are not picked up by instance registration).
 */
public final class RaceClientEvents {

    private final RaceHud hud = new RaceHud();
    private final GateRenderer gates = new GateRenderer();

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        gates.render(hud);
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.getType() == RenderGameOverlayEvent.ElementType.ALL) {
            hud.drawRing(event.getResolution());
        }
    }

    @SubscribeEvent
    public void onOverlayText(RenderGameOverlayEvent.Text event) {
        hud.addText(event.getRight());
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            clear();
        }
    }

    /** Fires on the network thread (possibly twice): hop to the client thread. */
    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(this::clear);
    }

    private void clear() {
        RaceClientState.get().clear();
        hud.clearTarget();
    }
}
