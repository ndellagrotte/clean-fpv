package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ClientPacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.Wire;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Relay of {@link BuildC2S} to players tracking the sender.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, server → client; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class BuildS2C implements IMessage {

    public static final int DISCRIMINATOR = 11;

    private UUID playerId;
    private DroneBuild build;

    /** For Forge's reflective instantiation only. */
    public BuildS2C() {}

    public BuildS2C(UUID playerId, DroneBuild build) {
        this.playerId = playerId;
        this.build = build;
    }

    /** Owner of the build. */
    public UUID playerId() {
        return playerId;
    }

    /** Sanitized build. */
    public DroneBuild build() {
        return build;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        Wire.writeUuid(buf, playerId);
        build.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerId = Wire.readUuid(buf);
        build = DroneBuild.read(buf);
    }

    /** Receives on the client side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<BuildS2C, IMessage> {
        @Override
        public IMessage onMessage(BuildS2C message, MessageContext ctx) {
            Channel.dispatchClient(ctx, handler -> handler.onBuild(message));
            return null;
        }
    }
}
