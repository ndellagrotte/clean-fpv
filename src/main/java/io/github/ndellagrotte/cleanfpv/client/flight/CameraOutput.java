package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.input.StickState;
import io.github.ndellagrotte.cleanfpv.client.render.RemoteDroneView;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.joml.Quaternionf;

/**
 * Turns the attitude into this frame's camera (spec §4.3, §6.1; PLAN §5 row 1, §6.2) and publishes
 * it: {@link ClientDroneContext#setCameraPose}/{@link ClientDroneContext#setCameraOverride} for the
 * {@code CameraSetup}/{@code FOVModifier} override (render, C), and the player's rotation outputs.
 * Client thread only.
 *
 * <h2>Player outputs (written current and previous, so partial-tick interpolation is a no-op)</h2>
 * <ul>
 *   <li>{@code rotationYaw} (and head yaw) = <b>body</b> yaw, unwrapped next to the previous value;</li>
 *   <li>{@code rotationPitch} = <b>camera</b> pitch;</li>
 *   <li>{@code roll} = camera roll (loader roll axis; the server derives its own synced roll from the
 *       Transform packet).</li>
 * </ul>
 * These are outputs only: the attitude quaternion is the state (PLAN §7 ordering rule).
 */
final class CameraOutput {

    private static final Quaternionf SCRATCH = new Quaternionf();

    private CameraOutput() {}

    /**
     * Publishes the local pilot's camera for the current attitude and writes the player outputs.
     *
     * @return the effective camera tilt used (degrees)
     */
    static float applyLocal(EntityPlayerSP player) {
        Minecraft mc = Minecraft.getMinecraft();
        ClientDroneContext ctx = ClientDroneContext.get();
        DroneModelConfig model = ctx.activeModel();
        StickState sticks = ctx.sticks();

        float tilt = CameraRig.tiltDeg(sticks.angleSw(), sticks.angle(), model.switchlessAngle);
        ctx.setCameraTiltDeg(tilt);
        CameraPose pose = CameraRig.poseFor(ctx.attitude(), tilt, model.fov, aspect(mc));
        publish(ctx, pose);

        float yaw = CameraRig.unwrapNear(CameraRig.bodyYawDeg(ctx.attitude()), player.rotationYaw);
        if (Float.isFinite(yaw)) {
            player.rotationYaw = yaw;
            player.prevRotationYaw = yaw;
            player.rotationYawHead = yaw;
            player.prevRotationYawHead = yaw;
        }
        if (Float.isFinite(pose.pitchDeg())) {
            player.rotationPitch = pose.pitchDeg();
            player.prevRotationPitch = pose.pitchDeg();
        }
        if (Float.isFinite(pose.rollDeg())) {
            player.roll = pose.rollDeg();
            player.prevRoll = pose.rollDeg();
        }
        return tilt;
    }

    /**
     * While disarmed: when the render-view entity is another player with a visible
     * {@link RemoteDroneView} (spectating an armed pilot), publishes that pilot's camera through the
     * same override path (no separate roll add, so no double count); otherwise clears the override.
     * The tilt is the pilot's live camera angle from its Build ({@link RemoteDroneView#cameraTiltDeg()},
     * the value the pilot's client sends; 30° until a Build arrives), and the FOV is the viewer's own
     * setting.
     */
    static void applySpectator(Minecraft mc) {
        ClientDroneContext ctx = ClientDroneContext.get();
        Entity view = mc.getRenderViewEntity();
        RemoteDroneView remote = view instanceof EntityPlayer && view != mc.player
                ? ctx.remoteDrones().get(view.getUniqueID()) : null;
        if (remote == null) {
            clearOverride(ctx);
            return;
        }
        float tilt = remote.cameraTiltDeg();
        if (!Float.isFinite(tilt)) {
            tilt = CameraRig.REMOTE_TILT_DEG;
        }
        CameraPose pose = CameraRig.poseFor(remote.attitude(SCRATCH), tilt, ctx.activeModel().fov, aspect(mc));
        publish(ctx, pose);
    }

    /**
     * Publishes the pose and turns the override on. {@code RenderGlobal} rebuilds its frustum-culled
     * chunk list only when the view entity's position, {@code rotationYaw} or {@code rotationPitch}
     * changed; the override's roll, the camera yaw (≠ body yaw) and the spectator's per-frame pose
     * are invisible to that check, so any pose change marks the list dirty. Both callers run before
     * {@code setupTerrain} in the same frame. No cost while the camera is still.
     */
    static void publish(ClientDroneContext ctx, CameraPose pose) {
        boolean changed = !ctx.isCameraOverride() || !pose.equals(ctx.cameraPose());
        ctx.setCameraPose(pose);
        ctx.setCameraOverride(true);
        if (changed) {
            markTerrainDirty();
        }
    }

    /** Turns the override off; the vanilla camera must not reuse a chunk list culled for the drone. */
    static void clearOverride(ClientDroneContext ctx) {
        if (ctx.isCameraOverride()) {
            ctx.setCameraOverride(false);
            markTerrainDirty();
        }
    }

    private static void markTerrainDirty() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.renderGlobal != null) {
            mc.renderGlobal.setDisplayListEntitiesDirty();
        }
    }

    /** Viewport aspect ratio (width / height); 16:9 before the display exists. */
    static float aspect(Minecraft mc) {
        return mc.displayWidth > 0 && mc.displayHeight > 0
                ? (float) mc.displayWidth / (float) mc.displayHeight : 16f / 9f;
    }
}
