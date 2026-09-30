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
 * Spec §8.1 #1: a player started a lap. The client stamps its own clock; {@code serverTimeMs} is informational.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class LapStartedS2C implements IMessage {

    public static final int DISCRIMINATOR = 1;

    private long serverTimeMs;
    private UUID playerId;

    /** For Forge's reflective instantiation only. */
    public LapStartedS2C() {}

    public LapStartedS2C(long serverTimeMs, UUID playerId) {
        this.serverTimeMs = serverTimeMs;
        this.playerId = playerId;
    }

    /** Server wall clock at lap start (ms). */
    public long serverTimeMs() {
        return serverTimeMs;
    }

    /** Player who started the lap. */
    public UUID playerId() {
        return playerId;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(serverTimeMs);
        Wire.writeUuid(buf, playerId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        serverTimeMs = buf.readLong();
        playerId = Wire.readUuid(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<LapStartedS2C, IMessage> {
        @Override
        public IMessage onMessage(LapStartedS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onLapStarted(message));
            return null;
        }
    }
}
