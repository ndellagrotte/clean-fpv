package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.audio.MotorSounds;
import io.github.ndellagrotte.cleanfpv.client.input.ArmIntent;
import io.github.ndellagrotte.cleanfpv.client.input.InputManager;
import io.github.ndellagrotte.cleanfpv.client.physics.DroneState;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsConfig;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsEngine;
import io.github.ndellagrotte.cleanfpv.client.physics.Stepper;
import io.github.ndellagrotte.cleanfpv.client.render.Fisheye;
import io.github.ndellagrotte.cleanfpv.common.PlayerSizing;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.GameType;

/**
 * The local pilot's arm state machine (spec §4.1, PLAN §6.2, §6.9): {@code DISARMED → ARMED} plus
 * the skip-first-step flag ({@link ClientDroneContext#isSkipFirstStep()}). The arm flag itself lives
 * in {@link ClientDroneContext}; this class performs the transitions and everything that must
 * happen with them. Client thread only; one instance, owned by {@link FlightEvents}.
 *
 * <h2>Arm ({@link #tryArm})</h2>
 * Re-arming while armed is a no-op. Guards ({@link ArmRules#check}): Hello received with a
 * matching protocol, not riding, throttle low (joystick sticks only); a refusal shows its HUD
 * message. Then: camera tilt from the sticks, body attitude from the look direction
 * ({@link Attitude#initFromLook}, so the <em>camera</em> keeps looking where the player looked),
 * {@link PhysicsConfig} from the active model and the server's speed cap, drone state at the
 * player's feet with the velocity tracked while disarmed, skip-first-step set, motor sound, drone
 * size, flying, roll 0, the first camera pose, and {@code Build} + {@code Arm(true)} to the server
 * (the build first: the server drops Transforms that arrive before a build).
 *
 * <h2>Disarm ({@link #disarm}) and forced disarm ({@link #forceDisarm})</h2>
 * Both restore everything the arm changed: player size/eye/step ({@link PlayerSizing#restore}),
 * flying per game mode, roll 0, fisheye off, motor sound off, camera override off, then
 * {@link ClientDroneContext#resetFlight()}. The mod never touches the FOV setting (the FOV is a
 * per-frame {@code FOVModifier} override) nor replaces key bindings or {@code movementInput} (the
 * input subsystem zeroes vanilla movement only while armed), so neither needs restoring. A pilot's
 * disarm hands the drone's momentum to vanilla ({@code motion = v × 0.05}) and sends
 * {@code Arm(false)}; a forced disarm (server {@code Arm(false)}, death, disconnect, world unload,
 * new player entity) skips the hand-over and sends only while the connection is still open.
 *
 * <h2>Build updates while armed</h2>
 * A new {@code Build} is sent (at most once per tick, from {@link #flushBuild}) when the effective
 * camera tilt changes (5° steps of the angle knob, or the Default Angle setting) or when the active
 * model's physics settings change (settings saved / another model selected); the physics config is
 * refreshed at the same time.
 */
public final class ArmController {

    private final PhysicsEngine engine;

    /** The player entity that armed; a different {@code mc.player} means respawn / dimension change. */
    private EntityPlayerSP armedPlayer;
    /** {@code capabilities.isFlying} at arm time (restored for creative players). */
    private boolean flyingAtArm;
    /** Camera tilt carried by the last Build sent. */
    private float sentTilt = Float.NaN;
    private boolean buildDirty;

    public ArmController(PhysicsEngine engine) {
        this.engine = engine;
    }

    // =============================================================================================
    // Queries

    public boolean isArmed() {
        return ClientDroneContext.get().isArmed();
    }

    /** The player entity that is armed, or {@code null} while disarmed. */
    public EntityPlayerSP armedPlayer() {
        return armedPlayer;
    }

    // =============================================================================================
    // Transitions

    /** Applies the pilot's arm-control intent ({@code InputManager.arm().pollArmIntent}). */
    public void handleIntent(ArmIntent intent) {
        if (intent == ArmIntent.ARM) {
            EntityPlayerSP player = Minecraft.getMinecraft().player;
            if (player != null) {
                tryArm(player);
            }
        } else if (intent == ArmIntent.DISARM) {
            disarm();
        }
    }

    /**
     * Arms the local pilot if the guards pass; a no-op while armed.
     *
     * @return whether the pilot is armed afterwards
     */
    public boolean tryArm(EntityPlayerSP player) {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (ctx.isArmed()) {
            return true;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || player != mc.player || mc.world == null || player.isDead || player.getHealth() <= 0f) {
            return false;
        }
        ArmRules.Refusal refusal = ArmRules.check(ctx.isHelloReceived(), ctx.serverProtocol(),
                Channel.PROTOCOL_VERSION, player.isRiding(), InputManager.get().throttleGuardApplies(),
                ctx.sticks().throttleLow());
        if (refusal != ArmRules.Refusal.NONE) {
            player.sendStatusMessage(new TextComponentTranslation(refusal.langKey()), true);
            return false;
        }

        DroneModelConfig model = ctx.activeModel();
        float tilt = CameraRig.tiltDeg(ctx.sticks().angleSw(), ctx.sticks().angle(), model.switchlessAngle);
        ctx.setCameraTiltDeg(tilt);
        Attitude.initFromLook(player.rotationYaw, player.rotationPitch, tilt, ctx.attitude());

        PhysicsConfig config = configFor(model, ctx.serverMaxSpeed());
        ctx.setPhysicsConfig(config);
        DroneState state = ctx.droneState();
        engine.onArm(state, player.posX, player.posY, player.posZ);
        Stepper.clampSpeed(state.velocity, config.maxSpeed());
        ctx.setOverheating(false);

        ctx.setArmed(true);
        ctx.setSkipFirstStep(true);
        armedPlayer = player;
        flyingAtArm = player.capabilities.isFlying;

        PlayerSizing.shrink(player);
        player.capabilities.isFlying = true;
        player.motionX = 0.0;
        player.motionY = 0.0;
        player.motionZ = 0.0;
        player.fallDistance = 0f;
        player.setRoll(0f);
        player.prevRoll = 0f;
        MotorSounds.startLocal();
        CameraOutput.applyLocal(player);

        sendBuild(config, tilt);
        FlightNet.sendArm(true);
        return true;
    }

    /** The pilot disarms: full restore, momentum handed to vanilla, {@code Arm(false)} sent. */
    public void disarm() {
        if (ClientDroneContext.get().isArmed()) {
            restore(true);
            FlightNet.sendArm(false);
        }
    }

    /**
     * Forced disarm with full restore and no momentum hand-over; {@code Arm(false)} is sent only
     * while the connection is open (after a disconnect there is nobody to tell). No-op while disarmed
     * (a stale request is dropped).
     */
    public void forceDisarm(String reason) {
        ClientDroneContext ctx = ClientDroneContext.get();
        ctx.consumeForcedDisarm();
        if (!ctx.isArmed()) {
            return;
        }
        CleanFpv.LOGGER.debug("Forced disarm: {}", reason);
        restore(false);
        FlightNet.sendArm(false);
    }

    /**
     * Evaluates the forced-disarm triggers (PLAN §6.9 client column, api-notes D3): the pending
     * request from client net, a missing or replaced player entity, a dead player. Call at every
     * client tick and every frame; cheap.
     */
    public void checkForcedDisarm() {
        ClientDroneContext ctx = ClientDroneContext.get();
        boolean requested = ctx.consumeForcedDisarm();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        boolean present = player != null && mc.world != null;
        boolean alive = present && !player.isDead && player.getHealth() > 0f;
        if (ArmRules.mustForceDisarm(ctx.isArmed(), requested, present, player == armedPlayer, alive)) {
            forceDisarm(requested ? "requested" : !present ? "no player" : player != armedPlayer
                    ? "new player entity" : "player dead");
        }
    }

    private void restore(boolean handOverMomentum) {
        ClientDroneContext ctx = ClientDroneContext.get();
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = armedPlayer;
        DroneState state = ctx.droneState();

        ctx.setArmed(false);
        CameraOutput.clearOverride(ctx);
        if (player != null) {
            PlayerSizing.restore(player);
            GameType mode = mc.playerController != null && player == mc.player
                    ? mc.playerController.getCurrentGameType() : GameType.NOT_SET;
            player.capabilities.isFlying = ArmRules.flyingAfterDisarm(mode == GameType.CREATIVE,
                    mode == GameType.SPECTATOR || player.isSpectator(), flyingAtArm);
            if (handOverMomentum) {
                player.motionX = ArmRules.handOverMotion(state.velocity.x);
                player.motionY = ArmRules.handOverMotion(state.velocity.y);
                player.motionZ = ArmRules.handOverMotion(state.velocity.z);
            }
            player.setRoll(0f);
            player.prevRoll = 0f;
            player.fallDistance = 0f; // matches DroneServer.restore
        }
        Fisheye.disable();
        MotorSounds.stopLocal();
        ctx.resetFlight();

        armedPlayer = null;
        flyingAtArm = false;
        buildDirty = false;
        sentTilt = Float.NaN;
    }

    // =============================================================================================
    // Settings and Build updates while armed

    /**
     * The physics config for the active model, refreshed when the model's physics settings changed
     * (the new config then also marks the Build dirty). Call once per armed tick while no GUI is
     * open, so edits made in the settings screens apply when the screen closes (i.e. on save).
     */
    public PhysicsConfig refreshConfig() {
        ClientDroneContext ctx = ClientDroneContext.get();
        PhysicsConfig fresh = configFor(ctx.activeModel(), ctx.serverMaxSpeed());
        if (!fresh.equals(ctx.physicsConfig())) {
            ctx.setPhysicsConfig(fresh);
            Stepper.clampSpeed(ctx.droneState().velocity, fresh.maxSpeed());
            buildDirty = true;
        }
        return fresh;
    }

    /** Called with this frame's effective camera tilt: a changed tilt marks the Build dirty. */
    public void onTilt(float tiltDeg) {
        if (ClientDroneContext.get().isArmed() && Float.compare(tiltDeg, sentTilt) != 0) {
            buildDirty = true;
        }
    }

    /** Sends the pending Build update, if any (once per tick, before the Transform). */
    public void flushBuild() {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (buildDirty && ctx.isArmed() && ctx.physicsConfig() != null) {
            sendBuild(ctx.physicsConfig(), ctx.cameraTiltDeg());
        }
    }

    private void sendBuild(PhysicsConfig config, float tilt) {
        DroneBuild build = config.build().copy();
        build.cameraAngle = tilt;
        build.sanitize();
        FlightNet.sendBuild(build);
        sentTilt = tilt;
        buildDirty = false;
    }

    /**
     * The physics config for {@code model}: its build copied and sanitized (the server validates
     * motor speeds against the sanitized build, so the simulation must use the same numbers), the
     * speed cap {@code min(500, serverMaxSpeed)}.
     */
    static PhysicsConfig configFor(DroneModelConfig model, double serverMaxSpeed) {
        PhysicsConfig config = PhysicsConfig.from(model, serverMaxSpeed);
        config.build().sanitize();
        return config;
    }
}
