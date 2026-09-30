package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientPacketHandler;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server handshake (PLAN §6.6), sent on login. Arming is refused until one arrives.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class HelloS2C implements IMessage {

    public static final int DISCRIMINATOR = 0;

    private int protocol;
    private float maxSpeed;

    /** For Forge's reflective instantiation only. */
    public HelloS2C() {}

    public HelloS2C(int protocol, float maxSpeed) {
        this.protocol = protocol;
        this.maxSpeed = maxSpeed;
    }

    /** Server protocol version ({@link Channel#PROTOCOL_VERSION}). */
    public int protocol() {
        return protocol;
    }

    /** Server speed cap in m/s (default {@link Channel#DEFAULT_MAX_SPEED}). */
    public float maxSpeed() {
        return maxSpeed;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(protocol);
        buf.writeFloat(maxSpeed);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        protocol = buf.readInt();
        maxSpeed = buf.readFloat();
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<HelloS2C, IMessage> {
        @Override
        public IMessage onMessage(HelloS2C message, MessageContext ctx) {
            Channel.dispatchClient(ctx, handler -> handler.onHello(message));
            return null;
        }
    }
}
