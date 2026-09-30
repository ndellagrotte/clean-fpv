package io.github.ndellagrotte.cleanfpv.client.flight;

/**
 * The camera for one frame, in Minecraft's convention (PLAN §6.2): {@code orientCamera} applies
 * {@code Rz(roll)·Rx(pitch)·Ry(yaw + 180)}, so these are exactly the values the
 * {@code CameraSetup} handler writes with {@code setYaw/setPitch/setRoll} (override, not add).
 * Degrees throughout; {@code vFovDeg} is the vertical FOV for {@code FOVModifier.setFOV}.
 * Immutable.
 *
 * @param yawDeg   Minecraft yaw (entity convention, before vanilla's +180)
 * @param pitchDeg Minecraft pitch (positive = looking down)
 * @param rollDeg  roll
 * @param vFovDeg  vertical field of view
 */
public record CameraPose(float yawDeg, float pitchDeg, float rollDeg, float vFovDeg) {

    /** Level camera, vanilla's default 70° FOV. */
    public static final CameraPose IDENTITY = new CameraPose(0f, 0f, 0f, 70f);

    public CameraPose withFov(float vFov) {
        return new CameraPose(yawDeg, pitchDeg, rollDeg, vFov);
    }
}
