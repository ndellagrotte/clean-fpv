package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.input.MouseInput;
import io.github.ndellagrotte.cleanfpv.client.input.StickState;
import io.github.ndellagrotte.cleanfpv.client.physics.CollisionWorld;
import io.github.ndellagrotte.cleanfpv.client.physics.DroneState;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsConfig;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsEngine;
import io.github.ndellagrotte.cleanfpv.client.physics.StepInput;
import io.github.ndellagrotte.cleanfpv.client.physics.Stepper;
import io.github.ndellagrotte.cleanfpv.common.PlayerSizing;
import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import org.joml.Vector3d;

/**
 * The client flight loop (PLAN §6.3, §7 with the real frame order of api-notes D2): the per-frame
 * attitude step and camera outputs, the physics step (per tick, or per frame in realtime mode),
 * applying the result to the player, the per-tick Transform, and velocity tracking while disarmed.
 * Driven by {@link FlightEvents}; client thread only.
 *
 * <h2>Pause rule (spec §5.1, PLAN §6.3)</h2>
 * While a GUI screen is open ({@code mc.currentScreen != null}, which includes the single-player
 * pause menu) neither physics nor the attitude move. The player is still held in place (size,
 * position, zero motion, flying), the camera pose is still published and Transforms keep flowing,
 * so remote viewers do not time the pilot out.
 *
 * <h2>Position bookkeeping</h2>
 * Tick mode steps at {@code PlayerTickEvent} START, where vanilla has already copied the old
 * position into {@code lastTickPos}; {@code setPosition} there gives the renderer a smooth
 * {@code lastTickPos → pos} interpolation (one tick behind, like every entity). Realtime mode moves
 * the player every frame, so after each frame step {@code lastTickPos} and {@code prevPos} are set to
 * the new position too and the partial-tick interpolation renders exactly the simulated point.
 */
final class FlightLoop {

    private final PhysicsEngine engine;
    private final ArmController arm;
    private final Vector3d sendVelocity = new Vector3d();

    /** The player whose position the disarmed velocity tracker follows ({@code null} = re-seed). */
    private EntityPlayerSP trackedPlayer;

    FlightLoop(PhysicsEngine engine, ArmController arm) {
        this.engine = engine;
        this.arm = arm;
    }

    // =============================================================================================
    // Per frame

    /**
     * The attitude frame step (spec §4.3): stick rates × {@code dt} plus the mouse displacement,
     * then the camera outputs. At most once per frame ({@link ClientDroneContext#tryBeginAttitudeFrame});
     * returns {@code false} when this frame's step already ran. With a GUI open the attitude does not
     * move but the camera is still published.
     *
     * @param dt         frame time (s), loader clock (already clamped to [0, 0.1])
     * @param mouseYaw   {@code MouseTurnEvent} yaw (event units), 0 without mouse
     * @param mousePitch {@code MouseTurnEvent} pitch (event units, positive = look up), 0 without mouse
     */
    boolean attitudeStep(EntityPlayerSP player, double dt, float mouseYaw, float mousePitch) {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (!ctx.isArmed() || player == null || player != arm.armedPlayer() || !ctx.tryBeginAttitudeFrame()) {
            return false;
        }
        if (Minecraft.getMinecraft().currentScreen == null) {
            DroneModelConfig model = ctx.activeModel();
            StickState s = ctx.sticks();
            float gain = model.mouseGain;
            float mousePitchUp = MouseInput.pitchRad(mousePitch, gain);
            float mouseRoll = MouseInput.rollRad(mouseYaw, gain);
            Attitude.frameStep(ctx.attitude(), s.yaw(), s.pitch(), s.roll(),
                    model.yawRates, model.pitchRates, model.rollRates, dt, mousePitchUp, mouseRoll);
            publishOverlayMouse(ctx, model, dt, mouseRoll, mousePitchUp);
        } else {
            ctx.setOverlayMouse(0f, 0f);
        }
        arm.onTilt(CameraOutput.applyLocal(player));
        return true;
    }

    /** Overlay smoothing time constant (s): per-frame mouse deltas are too jumpy to show raw. */
    private static final double OVERLAY_MOUSE_TAU_S = 0.05;

    /** Mouse roll/pitch as smoothed stick deflection for the stick overlay (spec §6.4). */
    private static void publishOverlayMouse(ClientDroneContext ctx, DroneModelConfig model, double dt,
                                            float mouseRoll, float mousePitchUp) {
        if (!(dt > 0.0)) {
            return;
        }
        float roll = MouseInput.overlayStick(mouseRoll, Rates.rateRadPerSec(1.0, model.rollRates), dt);
        float pitch = MouseInput.overlayStick(mousePitchUp, Rates.rateRadPerSec(1.0, model.pitchRates), dt);
        float a = (float) (1.0 - Math.exp(-dt / OVERLAY_MOUSE_TAU_S));
        ctx.setOverlayMouse(ctx.overlayMouseRoll() + a * (roll - ctx.overlayMouseRoll()),
                ctx.overlayMousePitchUp() + a * (pitch - ctx.overlayMousePitchUp()));
    }

    /**
     * Realtime physics (spec §5.1 "High Performance"), once per frame at {@code RenderTickEvent}
     * START with the loader's frame {@code dt}. No-op in tick mode, while disarmed or paused.
     */
    void realtimeStep(EntityPlayerSP player, double dt) {
        ClientDroneContext ctx = ClientDroneContext.get();
        PhysicsConfig config = ctx.physicsConfig();
        Minecraft mc = Minecraft.getMinecraft();
        if (!ctx.isArmed() || config == null || !config.realtime() || player == null
                || player != arm.armedPlayer() || mc.world == null || mc.currentScreen != null) {
            return;
        }
        if (ctx.isSkipFirstStep()) {
            ctx.setSkipFirstStep(false);
            return;
        }
        DroneState state = ctx.droneState();
        PlayerSizing.shrink(player);
        PhysicsEngine.syncPosition(state, player.posX, player.posY, player.posZ);
        boolean noClip = noClip(player);
        engine.stepRealtime(state, new StepInput(ctx.attitude(), ctx.sticks().thr(), dt, noClip), config,
                collisionWorld(player));
        player.setPosition(state.position.x, state.position.y, state.position.z);
        player.lastTickPosX = player.prevPosX = player.posX;
        player.lastTickPosY = player.prevPosY = player.posY;
        player.lastTickPosZ = player.prevPosZ = player.posZ;
        holdStill(player);
    }

    // =============================================================================================
    // Per tick

    /** {@code PlayerTickEvent} START for the local player. */
    void tickStart(EntityPlayerSP player) {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (!ctx.isArmed()) {
            trackDisarmed(player);
            return;
        }
        if (player != arm.armedPlayer()) {
            return; // the forced-disarm check handles a replaced player entity
        }
        trackedPlayer = null;
        boolean paused = Minecraft.getMinecraft().currentScreen != null;
        DroneState state = ctx.droneState();

        PlayerSizing.shrink(player);
        PhysicsConfig config = paused && ctx.physicsConfig() != null ? ctx.physicsConfig() : arm.refreshConfig();
        PhysicsEngine.syncPosition(state, player.posX, player.posY, player.posZ);
        boolean noClip = noClip(player);
        CollisionWorld world = collisionWorld(player);
        if (config.realtime()) {
            engine.markTickBoundary(state, world, noClip);
        } else if (!paused) {
            if (ctx.isSkipFirstStep()) {
                ctx.setSkipFirstStep(false);
            } else {
                engine.stepTick(state, new StepInput(ctx.attitude(), ctx.sticks().thr(), PhysicsEngine.TICK_DT,
                        noClip), config, world);
            }
        }
        player.setPosition(state.position.x, state.position.y, state.position.z);
        holdStill(player);
        ctx.setOverheating(engine.overheating(state, config));

        if (!paused) {
            arm.flushBuild();
        }
        sendVelocity.set(state.velocity);
        Stepper.clampSpeed(sendVelocity, config.maxSpeed());
        FlightNet.sendTransform(TransformSnapshot.of(ctx.attitude(), sendVelocity, state.omega,
                System.currentTimeMillis()));
    }

    /**
     * {@code PlayerTickEvent} END for the local armed player: re-assert the drone size (vanilla's
     * {@code updateSize()} just regrew it), eye height, step height and flying.
     *
     * <p>Known limitation (api-notes D10, accepted): vanilla regrows the box to 0.6 × 1.8 inside
     * {@code updateSize()} whenever it fits, and only this END handler shrinks it back. On the client
     * the regrow is harmless (a growing {@code setSize} moves the entity only server-side), but on
     * the server {@code setSize} runs {@code move(SELF, −0.4, 0, −0.4)} with the inflated box every
     * armed tick, so block collision side effects (pressure plates, cactus, portals) can fire there.
     * There is no mixin-free way to suppress it; the server handler documents the same.
     */
    void tickEnd(EntityPlayerSP player) {
        if (ClientDroneContext.get().isArmed() && player == arm.armedPlayer()) {
            PlayerSizing.shrink(player);
            player.capabilities.isFlying = true;
        }
    }

    /** Forget the tracked player (world change, disconnect, disarm): the next tick re-seeds. */
    void resetTracking() {
        trackedPlayer = null;
    }

    /**
     * While disarmed (spec §5.1): {@code v = (pos − lastPos) / dt} each tick so arming inherits the
     * player's motion. A new player entity, the first tick after a disarm, or a teleport-sized jump
     * re-seeds the tracker at rest.
     */
    private void trackDisarmed(EntityPlayerSP player) {
        DroneState state = ClientDroneContext.get().droneState();
        if (player != trackedPlayer || ArmRules.isTeleport(player.posX - state.lastPosition.x,
                player.posY - state.lastPosition.y, player.posZ - state.lastPosition.z,
                PhysicsConfig.ABSOLUTE_MAX_SPEED)) {
            state.reset(player.posX, player.posY, player.posZ);
            trackedPlayer = player;
            return;
        }
        PhysicsEngine.trackDisarmed(state, player.posX, player.posY, player.posZ, PhysicsEngine.TICK_DT);
    }

    // =============================================================================================

    /** Zero vanilla motion (the physics owns translation), no fall damage build-up, flying. */
    private static void holdStill(EntityPlayerSP player) {
        player.motionX = 0.0;
        player.motionY = 0.0;
        player.motionZ = 0.0;
        player.fallDistance = 0f;
        player.capabilities.isFlying = true;
    }

    private static boolean noClip(EntityPlayerSP player) {
        return player.noClip || player.isSpectator();
    }

    /**
     * {@code world.getCollisionBoxes(player, region)}: blocks, world border and collidable entities,
     * exactly what the server's {@code move} replay sees (PLAN §6.3).
     */
    private static CollisionWorld collisionWorld(EntityPlayerSP player) {
        return region -> player.world.getCollisionBoxes(player, region);
    }
}
