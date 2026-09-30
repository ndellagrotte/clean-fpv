package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ServerPacketHandler;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #10 + velocity (owner → server), every armed tick.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, client → server; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class TransformC2S implements IMessage {

    public static final int DISCRIMINATOR = 10;

    private TransformSnapshot transform;

    /** For Forge's reflective instantiation only. */
    public TransformC2S() {}

    public TransformC2S(TransformSnapshot transform) {
        this.transform = transform;
    }

    /** Attitude, velocity, motor speeds, sender epoch ms. */
    public TransformSnapshot transform() {
        return transform;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        transform.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transform = TransformSnapshot.read(buf);
    }

    /** Receives on the server side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<TransformC2S, IMessage> {
        @Override
        public IMessage onMessage(TransformC2S message, MessageContext ctx) {
            Channel.dispatchServer(ctx, (handler, sender) -> handler.onTransform(sender, message));
            return null;
        }
    }
}
