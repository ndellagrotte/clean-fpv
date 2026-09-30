package io.github.ndellagrotte.cleanfpv.common.net;

import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformC2S;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * Server-side behaviour for the owner packets (PLAN §6.6: validate, apply abilities/size/roll,
 * store in {@code ArmRegistry.SERVER}, relay with {@link Channel#sendToAllTracking}). Implemented
 * by {@code server.net.ServerNetHandler}; installed by {@code CommonProxy.init}. Always called on
 * the server thread.
 */
public interface ServerPacketHandler {

    void onArm(EntityPlayerMP sender, ArmC2S message);

    void onBuild(EntityPlayerMP sender, BuildC2S message);

    void onTransform(EntityPlayerMP sender, TransformC2S message);
}
