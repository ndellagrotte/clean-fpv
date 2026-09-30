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
 * Spec §8.1 #2: a player finished a lap.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class LapFinishedS2C implements IMessage {

    public static final int DISCRIMINATOR = 2;

    private int lapMs;
    private UUID trackId;
    private UUID playerId;

    /** For Forge's reflective instantiation only. */
    public LapFinishedS2C() {}

    public LapFinishedS2C(int lapMs, UUID trackId, UUID playerId) {
        this.lapMs = lapMs;
        this.trackId = trackId;
        this.playerId = playerId;
    }

    /** Lap time in ms. */
    public int lapMs() {
        return lapMs;
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
        buf.writeInt(lapMs);
        Wire.writeUuid(buf, trackId);
        Wire.writeUuid(buf, playerId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        lapMs = buf.readInt();
        trackId = Wire.readUuid(buf);
        playerId = Wire.readUuid(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<LapFinishedS2C, IMessage> {
        @Override
        public IMessage onMessage(LapFinishedS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onLapFinished(message));
            return null;
        }
    }
}
