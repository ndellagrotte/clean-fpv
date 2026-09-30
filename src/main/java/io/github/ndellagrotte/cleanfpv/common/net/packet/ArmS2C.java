package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientPacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.Wire;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Relay of {@link ArmC2S} (also sent by the server on forced disarm, e.g. death).
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class ArmS2C implements IMessage {

    public static final int DISCRIMINATOR = 12;

    private UUID playerId;
    private boolean armed;

    /** For Forge's reflective instantiation only. */
    public ArmS2C() {}

    public ArmS2C(UUID playerId, boolean armed) {
        this.playerId = playerId;
        this.armed = armed;
    }

    /** Pilot. */
    public UUID playerId() {
        return playerId;
    }

    /** New armed state. */
    public boolean armed() {
        return armed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        Wire.writeUuid(buf, playerId);
        buf.writeBoolean(armed);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerId = Wire.readUuid(buf);
        armed = buf.readBoolean();
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<ArmS2C, IMessage> {
        @Override
        public IMessage onMessage(ArmS2C message, MessageContext ctx) {
            Channel.dispatchClient(ctx, handler -> handler.onArm(message));
            return null;
        }
    }
}
