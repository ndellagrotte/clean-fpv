package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformC2S;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/**
 * The local pilot's client → server packets (PLAN §6.6): Arm, Build, Transform. Every send checks
 * that a play connection with an open channel exists, so a forced disarm after the connection is
 * gone restores locally without sending, and a failing send never breaks the flight loop.
 * Client thread only.
 */
final class FlightNet {

    private static boolean loggedFailure;

    private FlightNet() {}

    /** A play connection whose channel is still open. */
    static boolean connected() {
        NetHandlerPlayClient connection = Minecraft.getMinecraft().getConnection();
        return connection != null && connection.getNetworkManager() != null
                && connection.getNetworkManager().isChannelOpen();
    }

    static void sendArm(boolean armed) {
        send(new ArmC2S(armed));
    }

    /** Sends {@code build} as is (callers pass a sanitized copy carrying the live camera tilt). */
    static void sendBuild(DroneBuild build) {
        send(new BuildC2S(build));
    }

    static void sendTransform(TransformSnapshot transform) {
        send(new TransformC2S(transform));
    }

    private static void send(IMessage message) {
        if (!connected()) {
            return;
        }
        try {
            Channel.sendToServer(message);
        } catch (RuntimeException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                CleanFpv.LOGGER.warn("Could not send {} to the server", message.getClass().getSimpleName(), e);
            }
        }
    }
}
