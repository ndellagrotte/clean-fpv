package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * Read-only view of one armed remote pilot, as reconstructed by {@code client.net.RemoteDrones}
 * (spec §8.3: two-state history, sender-clock extrapolation, 20 % per-frame smoothing). Consumed
 * by the renderer, prop discs and the spectator camera. All getters are called on the client
 * thread during rendering; smoothing is advanced at most once per frame by the implementation.
 */
public interface RemoteDroneView {

    UUID playerId();

    /** Interpolated feet position at {@code partialTicks} (world coords), written into {@code dest}. */
    Vector3d position(float partialTicks, Vector3d dest);

    /** Smoothed/extrapolated body attitude for the current frame, written into {@code dest}. */
    Quaternionf attitude(Quaternionf dest);

    /** Latest received signed motor speed (rad/s), motor 0..3. */
    float omega(int motor);

    /**
     * Camera tilt (degrees) to use for this pilot's camera and model. The spectator camera uses a
     * fixed 30° (spec §8.3); the model may use {@code build().cameraAngle}.
     */
    float cameraTiltDeg();

    /** Latest build, or {@code null} if none received yet. */
    DroneBuild build();
}
