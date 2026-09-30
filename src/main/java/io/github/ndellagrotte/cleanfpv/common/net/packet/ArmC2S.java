package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ServerPacketHandler;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #7 (owner → server): arm state change.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, client → server; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class ArmC2S implements IMessage {

    public static final int DISCRIMINATOR = 7;

    private boolean armed;

    /** For Forge's reflective instantiation only. */
    public ArmC2S() {}

    public ArmC2S(boolean armed) {
        this.armed = armed;
    }

    /** New armed state. */
    public boolean armed() {
        return armed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(armed);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        armed = buf.readBoolean();
    }

    /** Receives on the server side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<ArmC2S, IMessage> {
        @Override
        public IMessage onMessage(ArmC2S message, MessageContext ctx) {
            Channel.dispatchServer(ctx, (handler, sender) -> handler.onArm(sender, message));
            return null;
        }
    }
}
