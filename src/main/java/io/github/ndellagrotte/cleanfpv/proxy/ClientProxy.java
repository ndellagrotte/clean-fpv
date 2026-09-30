package io.github.ndellagrotte.cleanfpv.proxy;

import io.github.ndellagrotte.cleanfpv.client.audio.AudioEvents;
import io.github.ndellagrotte.cleanfpv.client.flight.FlightEvents;
import io.github.ndellagrotte.cleanfpv.client.gui.GuiEvents;
import io.github.ndellagrotte.cleanfpv.client.hud.HudEvents;
import io.github.ndellagrotte.cleanfpv.client.input.InputEvents;
import io.github.ndellagrotte.cleanfpv.client.input.KeyBindings;
import io.github.ndellagrotte.cleanfpv.client.net.ClientNetHandler;
import io.github.ndellagrotte.cleanfpv.client.race.RaceClientEvents;
import io.github.ndellagrotte.cleanfpv.client.race.RaceClientNetHandler;
import io.github.ndellagrotte.cleanfpv.client.render.RenderEvents;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/**
 * Physical-client proxy (loaded only on the client via {@code @SidedProxy}). Adds key bindings,
 * the client packet handlers and the client event subscribers on top of {@link CommonProxy}.
 *
 * <p><b>Registration point for client subsystems:</b> {@link #registerClientHandlers()}. Within one
 * event and priority Forge calls subscribers in registration order, so the order below is part
 * of the frame sequencing (PLAN §7): input polls before flight consumes the sticks, flight
 * updates the camera before render/HUD/audio read it. Prefer explicit {@code priority} where the
 * order matters across subsystems.
 */
public class ClientProxy extends CommonProxy {

    private final ClientNetHandler clientNetHandler = new ClientNetHandler();
    private final RaceClientNetHandler raceClientNetHandler = new RaceClientNetHandler();

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        KeyBindings.register();
        Channel.setClientHandler(clientNetHandler);
        Channel.setClientRaceHandler(raceClientNetHandler);
        registerClientHandlers();
    }

    @Override
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    /** One line per client subscriber object (instance {@code @SubscribeEvent} methods). */
    protected void registerClientHandlers() {
        register(new InputEvents());       // (B) input
        register(new FlightEvents());      // (F) orchestration: arm FSM, frame/tick sequencing, lifecycle
        register(new RenderEvents());      // (C) camera override, FOV, fisheye, drone model
        register(new HudEvents());         // (C) OSD
        register(new AudioEvents());       // (C) motor sound
        register(new GuiEvents());         // (E) controls button, settings screens
        register(clientNetHandler);        // (D) client packet handler (also an event subscriber)
        register(new RaceClientEvents());  // (G) race HUD/render
    }
}
