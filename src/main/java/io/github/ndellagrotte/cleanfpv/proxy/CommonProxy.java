package io.github.ndellagrotte.cleanfpv.proxy;

import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.server.ServerEvents;
import io.github.ndellagrotte.cleanfpv.server.net.ServerNetHandler;
import io.github.ndellagrotte.cleanfpv.server.race.RaceServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;

/**
 * Proxy for both physical sides (dedicated server uses it directly; {@link ClientProxy} extends
 * it). Everything registered here must be side-safe: no client classes.
 *
 * <p><b>Registration point for common/server subsystems:</b> {@link #registerCommonHandlers()}.
 * The server packet handler is installed in {@link #init}.
 */
public class CommonProxy {

    private final ServerNetHandler serverNetHandler = new ServerNetHandler();
    private final RaceServer raceServer = new RaceServer();

    public void preInit(FMLPreInitializationEvent event) {
        Channel.register();
    }

    public void init(FMLInitializationEvent event) {
        Channel.setServerHandler(serverNetHandler);
        registerCommonHandlers();
    }

    public void postInit(FMLPostInitializationEvent event) {
    }

    public void serverStarting(FMLServerStartingEvent event) {
        raceServer.onServerStarting(event);
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        ArmRegistry.SERVER.clear();
    }

    /** One line per common/server subscriber object (instance {@code @SubscribeEvent} methods). */
    protected void registerCommonHandlers() {
        register(new ServerEvents());      // (D) server tick hooks, Hello, lifecycle
        register(serverNetHandler);        // (D) server packet handler (also an event subscriber)
        register(raceServer);              // (G) race authority
    }

    protected static void register(Object handler) {
        MinecraftForge.EVENT_BUS.register(handler);
    }
}
