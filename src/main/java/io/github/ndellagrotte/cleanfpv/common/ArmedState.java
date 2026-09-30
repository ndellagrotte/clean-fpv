package io.github.ndellagrotte.cleanfpv.common;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;

import java.util.UUID;

/**
 * What one side knows about one player's drone: armed flag, latest build and transform, and the
 * local receive times. Mutable; owned by an {@link ArmRegistry} and touched only on that side's
 * main thread (packet handlers hop there first), so fields are plain.
 */
public final class ArmedState {

    private final UUID playerId;
    private boolean armed;
    /** Latest build received (sanitized), or {@code null} if none yet. */
    private DroneBuild build;
    /** Latest transform received, or {@code null} if none since arming. */
    private TransformSnapshot transform;
    /** Local {@code System.currentTimeMillis()} of the last arm-state change. */
    private long armChangedAtMs;
    /** Local receive time of {@link #build}. */
    private long buildReceivedAtMs;
    /** Local receive time of {@link #transform}. */
    private long transformReceivedAtMs;

    public ArmedState(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean armed() {
        return armed;
    }

    /** Sets the armed flag; on a change records the time, and on disarm drops the transform. */
    public void setArmed(boolean armed, long nowMs) {
        if (this.armed != armed) {
            this.armed = armed;
            this.armChangedAtMs = nowMs;
            if (!armed) {
                this.transform = null;
            }
        }
    }

    public DroneBuild build() {
        return build;
    }

    public void setBuild(DroneBuild build, long nowMs) {
        this.build = build;
        this.buildReceivedAtMs = nowMs;
    }

    public TransformSnapshot transform() {
        return transform;
    }

    public void setTransform(TransformSnapshot transform, long nowMs) {
        this.transform = transform;
        this.transformReceivedAtMs = nowMs;
    }

    public long armChangedAtMs() {
        return armChangedAtMs;
    }

    public long buildReceivedAtMs() {
        return buildReceivedAtMs;
    }

    public long transformReceivedAtMs() {
        return transformReceivedAtMs;
    }

    @Override
    public String toString() {
        return "ArmedState{" + playerId + ", armed=" + armed + ", build=" + (build != null)
                + ", transform=" + (transform != null) + '}';
    }
}
