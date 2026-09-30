package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.flight.CameraPose;
import io.github.ndellagrotte.cleanfpv.client.flight.CameraRig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.EntityViewRenderEvent;

/**
 * Camera override (spec §6.1, PLAN §5 rows "Camera orientation" and "Per-frame FOV").
 *
 * <h2>When</h2>
 * {@link ClientDroneContext#isCameraOverride()} is set (orchestration, once per frame, before
 * {@code updateCameraAndRender}) <em>and</em> the render-view entity is the one the pose belongs
 * to: the local player while armed, or, while disarmed, a remote player that has a
 * {@link RemoteDroneView} (spectating an armed pilot). A stale flag therefore never rotates the
 * wrong entity's camera.
 *
 * <h2>What</h2>
 * {@code CameraSetup}: <b>replaces</b> yaw/pitch/roll with {@link ClientDroneContext#cameraPose()}.
 * {@link CameraPose#yawDeg()} is in entity convention, so the event yaw is {@code yawDeg + 180}
 * (the event's own default is interpolated entity yaw + 180); pitch and roll are written as is.
 * Nothing special happens in front view ({@code thirdPersonView == 2}): vanilla's pre-event
 * {@code Ry(180)} already mirrors the view (PLAN §5). Vanilla's hurt shake still composes on top.
 *
 * <p>{@code FOVModifier}: sets {@link CameraPose#vFovDeg()}. The event fires several times per frame
 * (projection setup, hand, fog/cloud passes) and carries nothing that separates the world call from
 * the hand call (both may see the same base FOV), so every call is overridden. That is harmless:
 * the hand is hidden while armed and the fog/cloud passes should match the world projection.
 * Vanilla's water (×60/70), sprint and death FOV factors are applied before the event, so they
 * are replaced as well while the override is on.
 *
 * <p>Third person: vanilla {@code orientCamera} clips the 4-block boom with rays along the entity's
 * {@code rotationYaw} (the drone's <em>body</em> yaw) while the boom itself follows the event yaw
 * (the <em>camera</em> yaw; they differ by {@code atan(tan(tilt)·sin(roll))} when banked). The
 * handler re-runs vanilla's probe along both directions and adds the difference as an eye-space
 * translate ({@link CameraRig#boomCorrectionZ}), so the boom is clipped along the direction it
 * actually extends.
 *
 * <p>Client render thread only.
 */
public final class CameraHooks {

    private CameraHooks() {}

    /** Whether the camera of {@code viewEntity} is currently overridden with the drone pose. */
    public static boolean overrides(Entity viewEntity) {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (!ctx.isCameraOverride() || viewEntity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (ctx.isArmed()) {
            return viewEntity == mc.player;
        }
        return viewEntity instanceof EntityPlayer && viewEntity != mc.player
                && ctx.remoteDrones().get(viewEntity.getUniqueID()) != null;
    }

    static void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        if (!overrides(event.getEntity())) {
            return;
        }
        CameraPose pose = ClientDroneContext.get().cameraPose();
        if (!Float.isFinite(pose.yawDeg()) || !Float.isFinite(pose.pitchDeg()) || !Float.isFinite(pose.rollDeg())) {
            return;
        }
        event.setYaw(pose.yawDeg() + 180f);
        event.setPitch(pose.pitchDeg());
        event.setRoll(pose.rollDeg());
        correctThirdPersonBoom(event.getEntity(), (float) event.getRenderPartialTicks(), pose);
    }

    /** Re-clips the third-person boom along the camera yaw instead of the body yaw (see class doc). */
    private static void correctThirdPersonBoom(Entity entity, float partialTicks, CameraPose pose) {
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc.gameSettings.thirdPersonView;
        if (view <= 0 || mc.gameSettings.debugCamEnable || mc.world == null
                || (entity instanceof EntityLivingBase living && living.isPlayerSleeping())) {
            return;
        }
        boolean front = view == 2;
        // Eye exactly as orientCamera computes it.
        float eyeH = entity.getEyeHeight();
        double x = entity.prevPosX + (entity.posX - entity.prevPosX) * partialTicks;
        double y = entity.prevPosY + (entity.posY - entity.prevPosY) * partialTicks + eyeH;
        double z = entity.prevPosZ + (entity.posZ - entity.prevPosZ) * partialTicks;
        double dVanilla = clampedBoom(mc.world, x, y, z, entity.rotationYaw, entity.rotationPitch, front);
        double dCam = clampedBoom(mc.world, x, y, z, pose.yawDeg(), pose.pitchDeg(), front);
        double dz = CameraRig.boomCorrectionZ(front, dVanilla, dCam);
        if (dz != 0.0 && Double.isFinite(dz)) {
            GlStateManager.translate(0.0F, 0.0F, (float) dz);
        }
    }

    /** Vanilla's third-person obstruction probe ({@code EntityRenderer.orientCamera}), verbatim. */
    private static double clampedBoom(World world, double d0, double d1, double d2, float f1, float f2, boolean front) {
        double d3 = CameraRig.THIRD_PERSON_BOOM;
        if (front) {
            f2 += 180.0F;
        }
        double d4 = -MathHelper.sin(f1 * (float) (Math.PI / 180.0)) * MathHelper.cos(f2 * (float) (Math.PI / 180.0)) * d3;
        double d5 = MathHelper.cos(f1 * (float) (Math.PI / 180.0)) * MathHelper.cos(f2 * (float) (Math.PI / 180.0)) * d3;
        double d6 = -MathHelper.sin(f2 * (float) (Math.PI / 180.0)) * d3;
        Vec3d eye = new Vec3d(d0, d1, d2);
        for (int i = 0; i < 8; i++) {
            float f3 = ((i & 1) * 2 - 1) * 0.1F;
            float f4 = ((i >> 1 & 1) * 2 - 1) * 0.1F;
            float f5 = ((i >> 2 & 1) * 2 - 1) * 0.1F;
            RayTraceResult hit = world.rayTraceBlocks(new Vec3d(d0 + f3, d1 + f4, d2 + f5),
                    new Vec3d(d0 - d4 + f3 + f5, d1 - d6 + f4, d2 - d5 + f5));
            if (hit != null) {
                double d7 = hit.hitVec.distanceTo(eye);
                if (d7 < d3) {
                    d3 = d7;
                }
            }
        }
        return d3;
    }

    static void onFovModifier(EntityViewRenderEvent.FOVModifier event) {
        if (!overrides(event.getEntity())) {
            return;
        }
        float fov = ClientDroneContext.get().cameraPose().vFovDeg();
        if (Float.isFinite(fov) && fov > 1f && fov < 179f) {
            event.setFOV(fov);
        }
    }
}
