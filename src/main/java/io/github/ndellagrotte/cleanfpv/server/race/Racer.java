package io.github.ndellagrotte.cleanfpv.server.race;

import io.github.ndellagrotte.cleanfpv.common.race.LapSequencer;

import java.util.UUID;

/**
 * A pilot who joined a track: the lap sequence plus the last position sample the next tick's
 * movement segment starts from. Server thread only.
 */
final class Racer {

    final UUID playerId;
    final UUID trackId;
    final LapSequencer laps;

    private boolean hasSample;
    private final double[] sample = new double[3];
    private int sampleDim;
    private long sampleMs;

    Racer(UUID playerId, UUID trackId, int gateCount) {
        this.playerId = playerId;
        this.trackId = trackId;
        this.laps = new LapSequencer(gateCount);
    }

    /** Aborts the running lap and forgets the position sample (track edit, respawn, dimension change). */
    void reset(int gateCount) {
        laps.reset(gateCount);
        dropSample();
    }

    /** Forgets the position sample so the next sample starts a fresh segment (disarmed, teleported). */
    void dropSample() {
        hasSample = false;
    }

    boolean hasSample() {
        return hasSample;
    }

    /** The previous sample {x, y, z}; valid only while {@link #hasSample()}. */
    double[] sample() {
        return sample;
    }

    int sampleDim() {
        return sampleDim;
    }

    long sampleMs() {
        return sampleMs;
    }

    void setSample(double x, double y, double z, int dim, long ms) {
        sample[0] = x;
        sample[1] = y;
        sample[2] = z;
        sampleDim = dim;
        sampleMs = ms;
        hasSample = true;
    }
}
