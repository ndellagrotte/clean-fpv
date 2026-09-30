package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientRacePacketHandler;
import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #5: add a gate to the live list (applied only while race mode is on).
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class GateS2C implements IMessage {

    public static final int DISCRIMINATOR = 5;

    private GateDef gate;

    /** For Forge's reflective instantiation only. */
    public GateS2C() {}

    public GateS2C(GateDef gate) {
        this.gate = gate;
    }

    /** The gate. */
    public GateDef gate() {
        return gate;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        gate.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        gate = GateDef.read(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<GateS2C, IMessage> {
        @Override
        public IMessage onMessage(GateS2C message, MessageContext ctx) {
            Channel.dispatchClientRace(ctx, handler -> handler.onGate(message));
            return null;
        }
    }
}
