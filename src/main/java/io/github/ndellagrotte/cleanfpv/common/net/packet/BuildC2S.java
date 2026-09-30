package io.github.ndellagrotte.cleanfpv.common.net.packet;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ServerPacketHandler;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Spec §8.1 #6 (owner → server): the sender's drone build (22 wire fields). Sent on arm, on effective camera-angle change and on settings save.
 *
 * <p>Discriminator {@value #DISCRIMINATOR}, client → server; handled on the receiving side's main thread via
 * {@link Channel}.
 */
public final class BuildC2S implements IMessage {

    public static final int DISCRIMINATOR = 6;

    private DroneBuild build;

    /** For Forge's reflective instantiation only. */
    public BuildC2S() {}

    public BuildC2S(DroneBuild build) {
        this.build = build;
    }

    /** Sanitized build. */
    public DroneBuild build() {
        return build;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        build.write(buf);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        build = DroneBuild.read(buf);
    }

    /** Receives on the server side and hops to its main thread. */
    public static final class Handler implements IMessageHandler<BuildC2S, IMessage> {
        @Override
        public IMessage onMessage(BuildC2S message, MessageContext ctx) {
            Channel.dispatchServer(ctx, (handler, sender) -> handler.onBuild(sender, message));
            return null;
        }
    }
}
