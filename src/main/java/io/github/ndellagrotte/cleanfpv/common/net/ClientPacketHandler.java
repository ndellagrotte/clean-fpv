package io.github.ndellagrotte.cleanfpv.common.net;

import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.HelloS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformS2C;

/**
 * Client-side behaviour for the drone packets. Lives in common/net because it mentions only
 * common types (so the S2C message classes can reference it on a dedicated server); the
 * implementation is {@code client.net.ClientNetHandler}, installed by {@code ClientProxy.init}.
 * Always called on the client thread. Implementations ignore relays whose UUID is the local
 * player's own.
 */
public interface ClientPacketHandler {

    void onHello(HelloS2C message);

    void onArm(ArmS2C message);

    void onBuild(BuildS2C message);

    void onTransform(TransformS2C message);
}
