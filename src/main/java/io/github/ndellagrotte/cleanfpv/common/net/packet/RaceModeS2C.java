package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientRacePacketHandler;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #9: race mode on/off; either change clears the client's live gate list.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class RaceModeS2C implements IMessage {

    public static final int DISCRIMINATOR = 9;

    private boolean enabled;

    /** For Forge's reflective instantiation only. */
    public RaceModeS2C() {}

    public RaceModeS2C(boolean enabled) {
        this.enabled = enabled;
    }

    /** Race mode state. */
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enabled = buf.readBoolean();
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<RaceModeS2C, IMessage> {
        @Override
        public IMessage onMessage(RaceModeS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onRaceMode(message));
            return null;
        }
    }
}
