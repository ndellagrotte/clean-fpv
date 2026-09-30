package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientPacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.Wire;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Relay of {@link TransformC2S} to players tracking the sender.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class TransformS2C implements IMessage {

    public static final int DISCRIMINATOR = 13;

    private UUID playerId;
    private TransformSnapshot transform;

    /** For Forge's reflective instantiation only. */
    public TransformS2C() {}

    public TransformS2C(UUID playerId, TransformSnapshot transform) {
        this.playerId = playerId;
        this.transform = transform;
    }

    /** Pilot. */
    public UUID playerId() {
        return playerId;
    }

    /** Attitude, velocity, motor speeds, sender epoch ms. */
    public TransformSnapshot transform() {
        return transform;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        Wire.writeUuid(buf, playerId);
        transform.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerId = Wire.readUuid(buf);
        transform = TransformSnapshot.read(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<TransformS2C, IMessage> {
        @Override
        public IMessage onMessage(TransformS2C message, MessageContext ctx) {
            Channel.dispatchClient(ctx, handler -> handler.onTransform(message));
            return null;
        }
    }
}
