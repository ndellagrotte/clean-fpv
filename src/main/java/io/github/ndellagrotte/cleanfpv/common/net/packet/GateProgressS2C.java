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
 * Spec §8.1 #3: a player passed a gate.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class GateProgressS2C implements IMessage {

    public static final int DISCRIMINATOR = 3;

    private int gateIndex;
    private UUID trackId;
    private UUID playerId;

    /** For Forge's reflective instantiation only. */
    public GateProgressS2C() {}

    public GateProgressS2C(int gateIndex, UUID trackId, UUID playerId) {
        this.gateIndex = gateIndex;
        this.trackId = trackId;
        this.playerId = playerId;
    }

    /** Index of the next gate to pass. */
    public int gateIndex() {
        return gateIndex;
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
        buf.writeInt(gateIndex);
        Wire.writeUuid(buf, trackId);
        Wire.writeUuid(buf, playerId);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        gateIndex = buf.readInt();
        trackId = Wire.readUuid(buf);
        playerId = Wire.readUuid(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<GateProgressS2C, IMessage> {
        @Override
        public IMessage onMessage(GateProgressS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onGateProgress(message));
            return null;
        }
    }
}
