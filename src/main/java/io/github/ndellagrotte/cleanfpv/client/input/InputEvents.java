package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.event.PlayerSPPushOutOfBlocksEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Forge event subscriber for the input subsystem (PLAN §6.1). Registered as an instance on
 * {@code MinecraftForge.EVENT_BUS} by {@code ClientProxy}.
 *
 * <ul>
 *   <li>Construction (FML init): registers the SDL hotplug listener (FML's event bus needs an active
 *       mod container at registration time).</li>
 *   <li>First client tick: lazily initialises {@link InputManager} (SDL background hint, joystick
 *       subsystem, device selection). Nothing SDL-related runs in a static initializer.</li>
 *   <li>{@code InputUpdateEvent} (every client tick, right after vanilla filled
 *       {@code movementInput}): while armed, records the raw movement keys and zeroes vanilla
 *       movement ({@link DroneMovementInput}, api-notes D4).</li>
 *   <li>{@code PlayerSPPushOutOfBlocksEvent}: cancelled while armed. Vanilla probes at
 *       {@code minY + 0.5}, far above a 0.1-block drone, and would shove it sideways under
 *       overhangs.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> here: the per-frame poll ({@link InputManager#pollFrame()}, called by
 * orchestration at {@code RenderTickEvent} START) and {@code MouseTurnEvent} (F's handler uses
 * {@link MouseInput}).
 */
public final class InputEvents {

    /** Constructed by {@code ClientProxy.init} (FML init phase): registers the SDL hotplug listener. */
    public InputEvents() {
        InputManager.get().registerListeners();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            InputManager.get().init();
        }
    }

    @SubscribeEvent
    public void onInputUpdate(InputUpdateEvent event) {
        if (event.getEntityPlayer() != Minecraft.getMinecraft().player) {
            return;
        }
        InputManager.get().movement().onInputUpdate(event.getMovementInput(), ClientDroneContext.get().isArmed());
    }

    @SubscribeEvent
    public void onPushOutOfBlocks(PlayerSPPushOutOfBlocksEvent event) {
        if (ClientDroneContext.get().isArmed() && event.getEntityPlayer() == Minecraft.getMinecraft().player) {
            event.setCanceled(true);
        }
    }
}
