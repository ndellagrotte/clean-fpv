package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientRacePacketHandler;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #4: full track definition.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class TrackS2C implements IMessage {

    public static final int DISCRIMINATOR = 4;

    private TrackDef track;

    /** For Forge's reflective instantiation only. */
    public TrackS2C() {}

    public TrackS2C(TrackDef track) {
        this.track = track;
    }

    /** The track. */
    public TrackDef track() {
        return track;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        track.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        track = TrackDef.read(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<TrackS2C, IMessage> {
        @Override
        public IMessage onMessage(TrackS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onTrack(message));
            return null;
        }
    }
}
