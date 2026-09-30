package io.github.ndellagrotte.cleanfpv.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

/**
 * Forge event subscriber for the motor sound lifecycle (spec §7, PLAN §6.5).
 *
 * <p>Owner: (C) render+hud+audio. Registered on {@code MinecraftForge.EVENT_BUS} by {@code ClientProxy}; add {@code @SubscribeEvent}
 * <em>instance</em> methods here (static subscribers are not picked up by instance registration).
 *
 * <p>Client tick END → {@link MotorSounds#tick()} (arm/disarm edges, remote pilots appearing and
 * leaving); client world unload and disconnect → {@link MotorSounds#stopAll()}.
 */
public final class AudioEvents {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            MotorSounds.tick();
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            MotorSounds.stopAll();
        }
    }

    /** Netty thread, possibly twice: hop to the client thread; {@code stopAll} is idempotent. */
    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(MotorSounds::stopAll);
    }
}
