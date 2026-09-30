package io.github.ndellagrotte.cleanfpv.common.net;

import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateProgressS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.HelloS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.JoinRaceS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapFinishedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapStartedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.RaceModeS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TrackS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformS2C;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.IThreadListener;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The mod's single network channel (PLAN §6.6) and the seam between packet decoding (common) and
 * packet behaviour (server/net, client/net, client/race implementers).
 *
 * <h2>Discriminators</h2>
 * 0 Hello (S2C); 1..10 = spec §8.1 order shifted by one (LapStarted, LapFinished, GateProgress,
 * Track, Gate, BuildC2S, ArmC2S, JoinRace, RaceMode, TransformC2S); then the relayed S2C forms of
 * the owner packets: 11 BuildS2C, 12 ArmS2C, 13 TransformS2C. Each class's
 * {@code DISCRIMINATOR} constant is the source of truth.
 *
 * <h2>Handlers</h2>
 * Every message handler hops to the receiving side's main thread ({@code addScheduledTask}) and
 * then calls the installed {@link ServerPacketHandler} / {@link ClientPacketHandler} /
 * {@link ClientRacePacketHandler}. Proxies install them at init: the common proxy installs the
 * server handler (both physical sides: integrated servers need it), the client proxy installs the
 * client ones. The handler interfaces only mention common types, so decoding is side-safe on a
 * dedicated server; with no handler installed a message is dropped silently.
 */
public final class Channel {

    /** Channel name; custom-payload channel names are limited to 20 characters. */
    public static final String NAME = "cleanfpv";
    /** Wire protocol version, sent in {@link HelloS2C}. Bump on any wire-format change. */
    public static final int PROTOCOL_VERSION = 1;
    /** Default server speed cap (m/s), sent in {@link HelloS2C}. */
    public static final float DEFAULT_MAX_SPEED = 500f;

    private static SimpleNetworkWrapper wrapper;

    private static volatile ServerPacketHandler serverHandler;
    private static volatile ClientPacketHandler clientHandler;
    private static volatile ClientRacePacketHandler clientRaceHandler;

    private Channel() {}

    /** Creates the channel and registers every message. Called once from {@code CommonProxy.preInit}. */
    public static void register() {
        if (wrapper != null) {
            throw new IllegalStateException("Channel already registered");
        }
        SimpleNetworkWrapper w = NetworkRegistry.INSTANCE.newSimpleChannel(NAME);
        w.registerMessage(new HelloS2C.Handler(), HelloS2C.class, HelloS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new LapStartedS2C.Handler(), LapStartedS2C.class, LapStartedS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new LapFinishedS2C.Handler(), LapFinishedS2C.class, LapFinishedS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new GateProgressS2C.Handler(), GateProgressS2C.class, GateProgressS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new TrackS2C.Handler(), TrackS2C.class, TrackS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new GateS2C.Handler(), GateS2C.class, GateS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new BuildC2S.Handler(), BuildC2S.class, BuildC2S.DISCRIMINATOR, Side.SERVER);
        w.registerMessage(new ArmC2S.Handler(), ArmC2S.class, ArmC2S.DISCRIMINATOR, Side.SERVER);
        w.registerMessage(new JoinRaceS2C.Handler(), JoinRaceS2C.class, JoinRaceS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new RaceModeS2C.Handler(), RaceModeS2C.class, RaceModeS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new TransformC2S.Handler(), TransformC2S.class, TransformC2S.DISCRIMINATOR, Side.SERVER);
        w.registerMessage(new BuildS2C.Handler(), BuildS2C.class, BuildS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new ArmS2C.Handler(), ArmS2C.class, ArmS2C.DISCRIMINATOR, Side.CLIENT);
        w.registerMessage(new TransformS2C.Handler(), TransformS2C.class, TransformS2C.DISCRIMINATOR, Side.CLIENT);
        wrapper = w;
    }

    public static SimpleNetworkWrapper get() {
        if (wrapper == null) {
            throw new IllegalStateException("Channel not registered yet");
        }
        return wrapper;
    }

    // ---------------------------------------------------------------------------------------------
    // Sending

    /** Client → server. */
    public static void sendToServer(IMessage message) {
        get().sendToServer(message);
    }

    /** Server → one player. */
    public static void sendTo(IMessage message, EntityPlayerMP player) {
        get().sendTo(message, player);
    }

    /** Server → every player tracking {@code entity} (not the entity itself): the relay path. */
    public static void sendToAllTracking(IMessage message, Entity entity) {
        get().sendToAllTracking(message, entity);
    }

    /** Server → everyone. */
    public static void sendToAll(IMessage message) {
        get().sendToAll(message);
    }

    // ---------------------------------------------------------------------------------------------
    // Handler installation

    public static void setServerHandler(ServerPacketHandler handler) {
        serverHandler = handler;
    }

    public static void setClientHandler(ClientPacketHandler handler) {
        clientHandler = handler;
    }

    public static void setClientRaceHandler(ClientRacePacketHandler handler) {
        clientRaceHandler = handler;
    }

    // ---------------------------------------------------------------------------------------------
    // Dispatch (called from the message handlers on the network thread)

    /** Hops to the server thread, then calls the server handler with the sending player. */
    public static void dispatchServer(MessageContext ctx, BiConsumer<ServerPacketHandler, EntityPlayerMP> action) {
        EntityPlayerMP sender = ctx.getServerHandler().player;
        if (sender == null) {
            return;
        }
        sender.getServerWorld().addScheduledTask(() -> {
            ServerPacketHandler h = serverHandler;
            if (h != null) {
                run(() -> action.accept(h, sender));
            }
        });
    }

    /** Hops to the client thread, then calls the client handler. */
    public static void dispatchClient(MessageContext ctx, Consumer<ClientPacketHandler> action) {
        IThreadListener thread = FMLCommonHandler.instance().getWorldThread(ctx.netHandler);
        thread.addScheduledTask(() -> {
            ClientPacketHandler h = clientHandler;
            if (h != null) {
                run(() -> action.accept(h));
            }
        });
    }

    /** Hops to the client thread, then calls the client race handler. */
    public static void dispatchClientRace(MessageContext ctx, Consumer<ClientRacePacketHandler> action) {
        IThreadListener thread = FMLCommonHandler.instance().getWorldThread(ctx.netHandler);
        thread.addScheduledTask(() -> {
            ClientRacePacketHandler h = clientRaceHandler;
            if (h != null) {
                run(() -> action.accept(h));
            }
        });
    }

    /** One bad packet must not take the game down: log and continue. */
    private static void run(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            CleanFpv.LOGGER.error("Error handling {} packet", NAME, e);
        }
    }
}
