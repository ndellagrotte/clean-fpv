package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientRacePacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.Wire;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #8: a player joined or left a track.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class JoinRaceS2C implements IMessage {

    public static final int DISCRIMINATOR = 8;

    private boolean joined;
    private UUID trackId;
    private UUID playerId;

    /** For Forge's reflective instantiation only. */
    public JoinRaceS2C() {}

    public JoinRaceS2C(boolean joined, UUID trackId, UUID playerId) {
        this.joined = joined;
        this.trackId = trackId;
        this.playerId = playerId;
    }

    /** True = joined, false = left. */
    public boolean joined() {
        return joined;
    }

    /** Track. */
    public UUID trackId() {
        return trackId;
    }

    /** Player. */
    public UUID playerId() {
        return playerId;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(joined);
        Wire.writeUuid(buf, trackId);
        Wire.writeUuid(buf, playerId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        joined = buf.readBoolean();
        trackId = Wire.readUuid(buf);
        playerId = Wire.readUuid(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<JoinRaceS2C, IMessage> {
        @Override
        public IMessage onMessage(JoinRaceS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onJoinRace(message));
            return null;
        }
    }
}
