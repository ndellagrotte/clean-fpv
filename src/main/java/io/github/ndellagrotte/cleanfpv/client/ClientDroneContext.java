package io.github.ndellagrotte.cleanfpv.client;

import io.github.ndellagrotte.cleanfpv.client.flight.CameraPose;
import io.github.ndellagrotte.cleanfpv.client.input.StickState;
import io.github.ndellagrotte.cleanfpv.client.physics.DroneState;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsConfig;
import io.github.ndellagrotte.cleanfpv.client.render.RemoteDroneSource;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import org.joml.Quaternionf;

/**
 * The client's shared flight state: the single place client subsystems exchange data (PLAN §4,
 * §7). A plain holder with getters/setters; it contains no behaviour beyond bookkeeping. Only
 * accessed on the client (render) thread; not thread-safe.
 *
 * <h2>Writers (by convention)</h2>
 * <ul>
 *   <li>armed / skipFirstStep / physicsConfig — {@code ArmController} (orchestration, F)</li>
 *   <li>sticks — input (B), once per frame ({@code InputManager.pollFrame()}, called by
 *       orchestration at {@code RenderTickEvent} START; api-notes D2)</li>
 *   <li>attitude / cameraPose / cameraTiltDeg — the per-frame attitude step (F calling A's math);
 *       the attitude quaternion is mutated <em>only</em> there (and at arm time)</li>
 *   <li>droneState / overheating — the physics step (F calling A's stepper)</li>
 *   <li>activeModel — settings (E) on load/save/select</li>
 *   <li>server info (Hello) — client net (D); cleared on disconnect</li>
 *   <li>remoteDrones — client net (D) at init</li>
 *   <li>frame counter — F, once per frame at {@code RenderTickEvent} START</li>
 * </ul>
 *
 * <h2>Attitude convention</h2>
 * {@link #attitude()} maps body-local axes to world: body forward = {@code q·(0,0,1)}, body up =
 * {@code q·(0,1,0)}, and the spec's "right" = {@code up × forward}.
 */
public final class ClientDroneContext {

    private static final ClientDroneContext INSTANCE = new ClientDroneContext();

    public static ClientDroneContext get() {
        return INSTANCE;
    }

    // --- Arm state ----------------------------------------------------------------------------
    private boolean armed;
    private boolean skipFirstStep;

    // --- Simulation -----------------------------------------------------------------------------
    private final DroneState droneState = new DroneState();
    private final Quaternionf attitude = new Quaternionf();
    private PhysicsConfig physicsConfig;
    private boolean overheating;

    // --- Camera ---------------------------------------------------------------------------------
    private CameraPose cameraPose = CameraPose.IDENTITY;
    private boolean cameraOverride;
    private float cameraTiltDeg = 30f;

    // --- Stick overlay: mouse attitude input as stick deflection (spec §6.4) --------------------
    private float overlayMouseRoll;
    private float overlayMousePitchUp;

    // --- Settings / input -------------------------------------------------------------------
    private DroneModelConfig activeModel = DroneModelConfig.radio("default");
    private StickState sticks = StickState.NEUTRAL;

    // --- Server handshake (Hello) -----------------------------------------------------------
    private boolean helloReceived;
    private int serverProtocol;
    private float serverMaxSpeed = Channel.DEFAULT_MAX_SPEED;

    // --- Frame sequencing (PLAN §7) ---------------------------------------------------------
    private long frameSeq;
    private long lastAttitudeFrame = -1L;

    // --- Remote pilots --------------------------------------------------------------------------
    private RemoteDroneSource remoteDrones = RemoteDroneSource.EMPTY;

    private boolean forcedDisarmRequested;

    private ClientDroneContext() {}

    // =============================================================================================
    // Resets

    /**
     * Disconnect only: flight state and Hello cleared. Keeps activeModel and remoteDrones. Not for
     * client world unload, which also fires on every dimension change (api-notes D5).
     */
    public void reset() {
        resetFlight();
        clearServerInfo();
        sticks = StickState.NEUTRAL;
    }

    /** Disarmed defaults for the flight state (does not touch Hello, model, frame counter). */
    public void resetFlight() {
        armed = false;
        skipFirstStep = false;
        droneState.reset(0, 0, 0);
        attitude.identity();
        physicsConfig = null;
        overheating = false;
        cameraPose = CameraPose.IDENTITY;
        cameraOverride = false;
        forcedDisarmRequested = false;
        cameraTiltDeg = activeModel != null ? activeModel.switchlessAngle : 30f;
        overlayMouseRoll = 0f;
        overlayMousePitchUp = 0f;
    }

    public void clearServerInfo() {
        helloReceived = false;
        serverProtocol = 0;
        serverMaxSpeed = Channel.DEFAULT_MAX_SPEED;
    }

    // =============================================================================================
    // Arm state

    public boolean isArmed() {
        return armed;
    }

    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    /** True for the first physics step after arming: that step only clears the flag (spec §5.1). */
    public boolean isSkipFirstStep() {
        return skipFirstStep;
    }

    public void setSkipFirstStep(boolean skipFirstStep) {
        this.skipFirstStep = skipFirstStep;
    }

    // =============================================================================================
    // Simulation

    /** The local drone's physics state (mutable, owned here). */
    public DroneState droneState() {
        return droneState;
    }

    /** The body attitude quaternion (mutable, owned here; see class doc for who may write it). */
    public Quaternionf attitude() {
        return attitude;
    }

    /** Physics settings captured at arm / settings save, or {@code null} while disarmed. */
    public PhysicsConfig physicsConfig() {
        return physicsConfig;
    }

    public void setPhysicsConfig(PhysicsConfig physicsConfig) {
        this.physicsConfig = physicsConfig;
    }

    /** Live overheat flag from the thrust model, for the HUD. */
    public boolean isOverheating() {
        return overheating;
    }

    public void setOverheating(boolean overheating) {
        this.overheating = overheating;
    }

    // =============================================================================================
    // Camera

    /** This frame's camera (Minecraft Euler triple + vertical FOV). */
    public CameraPose cameraPose() {
        return cameraPose;
    }

    public void setCameraPose(CameraPose cameraPose) {
        this.cameraPose = cameraPose;
    }

    /** Effective camera tilt (degrees) this frame: switch/knob value or {@code switchlessAngle}. */
    public float cameraTiltDeg() {
        return cameraTiltDeg;
    }

    public void setCameraTiltDeg(float cameraTiltDeg) {
        this.cameraTiltDeg = cameraTiltDeg;
    }

    /** Smoothed mouse roll as stick deflection [−1, 1] (positive = right), for the stick overlay. */
    public float overlayMouseRoll() {
        return overlayMouseRoll;
    }

    /** Smoothed mouse pitch as stick deflection [−1, 1] (positive = nose up), for the stick overlay. */
    public float overlayMousePitchUp() {
        return overlayMousePitchUp;
    }

    public void setOverlayMouse(float roll, float pitchUp) {
        this.overlayMouseRoll = Float.isFinite(roll) ? roll : 0f;
        this.overlayMousePitchUp = Float.isFinite(pitchUp) ? pitchUp : 0f;
    }

    // =============================================================================================
    // Settings / input

    /** The active model (never {@code null}). Treat as read-only outside the settings code. */
    public DroneModelConfig activeModel() {
        return activeModel;
    }

    public void setActiveModel(DroneModelConfig activeModel) {
        this.activeModel = activeModel != null ? activeModel : DroneModelConfig.radio("default");
    }

    /** Latest stick state (never {@code null}). */
    public StickState sticks() {
        return sticks;
    }

    public void setSticks(StickState sticks) {
        this.sticks = sticks != null ? sticks : StickState.NEUTRAL;
    }

    // =============================================================================================
    // Server handshake

    /** Arming is refused until the server's Hello arrives (PLAN §6.6). */
    public boolean isHelloReceived() {
        return helloReceived;
    }

    public int serverProtocol() {
        return serverProtocol;
    }

    /** Server speed cap (m/s); {@link Channel#DEFAULT_MAX_SPEED} until a Hello arrives. */
    public float serverMaxSpeed() {
        return serverMaxSpeed;
    }

    public void setServerInfo(int protocol, float maxSpeed) {
        this.helloReceived = true;
        this.serverProtocol = protocol;
        this.serverMaxSpeed = Float.isFinite(maxSpeed) && maxSpeed > 0f ? maxSpeed : Channel.DEFAULT_MAX_SPEED;
    }

    // =============================================================================================
    // Frame sequencing

    /** Monotonic rendered-frame counter. */
    public long frameSeq() {
        return frameSeq;
    }

    /** Advances the frame counter (once per frame, {@code RenderTickEvent} START); returns the new value. */
    public long nextFrame() {
        return ++frameSeq;
    }

    /** Frame number of the last attitude step, −1 if none. */
    public long lastAttitudeFrame() {
        return lastAttitudeFrame;
    }

    public void setLastAttitudeFrame(long frame) {
        this.lastAttitudeFrame = frame;
    }

    /**
     * Claims this frame's attitude step: returns {@code true} (and records the frame) if no
     * attitude step ran yet in the current frame, else {@code false}. Guarantees at most one step
     * per frame between {@code MouseTurnEvent} and the {@code RenderTickEvent} END fallback.
     */
    public boolean tryBeginAttitudeFrame() {
        if (lastAttitudeFrame == frameSeq) {
            return false;
        }
        lastAttitudeFrame = frameSeq;
        return true;
    }

    // =============================================================================================
    // Remote pilots

    public RemoteDroneSource remoteDrones() {
        return remoteDrones;
    }

    public void setRemoteDrones(RemoteDroneSource remoteDrones) {
        this.remoteDrones = remoteDrones != null ? remoteDrones : RemoteDroneSource.EMPTY;
    }

    // =============================================================================================
    // Camera override and forced disarm

    /**
     * True while {@link #cameraPose()} should replace the vanilla camera for the current render-view
     * entity: the local pilot is armed, or the player spectates an armed remote pilot (then the pose
     * is that pilot's). Written once per frame by orchestration (F); read by render (C).
     */
    public boolean isCameraOverride() {
        return cameraOverride;
    }

    public void setCameraOverride(boolean cameraOverride) {
        this.cameraOverride = cameraOverride;
    }

    /**
     * Asks orchestration (F) to disarm the local pilot with a full restore on its next tick, e.g.
     * when the server relays {@code Arm(false)} for this player (death, dimension change). Set by
     * client net (D); consumed by {@code ArmController}.
     */
    public void requestForcedDisarm() {
        forcedDisarmRequested = true;
    }

    /** Returns and clears the pending forced-disarm request. */
    public boolean consumeForcedDisarm() {
        boolean requested = forcedDisarmRequested;
        forcedDisarmRequested = false;
        return requested;
    }
}
