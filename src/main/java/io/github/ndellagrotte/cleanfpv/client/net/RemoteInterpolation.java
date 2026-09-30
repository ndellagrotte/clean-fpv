package io.github.ndellagrotte.cleanfpv.client.net;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/**
 * Pure orientation math for remote drones (spec §8.3): angular-velocity extrapolation from the
 * last two received attitudes, timed on the <em>sender's</em> clock, and the fractional smoothing
 * step. No Minecraft imports; unit-tested. Every call works on its own (caller-owned) objects: no
 * shared scratch vectors (the original's static-constant bug, spec §13.1).
 */
public final class RemoteInterpolation {

    /** Fraction of the remaining rotation the smoothed attitude moves per rendered frame. */
    public static final float SMOOTHING = 0.2f;
    /** Never extrapolate further than this past the newest packet (s): packets are 20 Hz. */
    public static final double MAX_EXTRAPOLATION_S = 0.25;
    /** Two packets further apart than this (sender ms) are not used to estimate a rate. */
    public static final long MAX_SAMPLE_GAP_MS = 1000L;

    private RemoteInterpolation() {}

    /**
     * Extrapolated attitude: the rotation {@code newer · older⁻¹} (short way round) taken as a
     * constant world-frame angular velocity over {@code newerMs − olderMs}, continued for
     * {@code elapsedS} (clamped to {@code [0, MAX_EXTRAPOLATION_S]}) beyond {@code newer}.
     * Falls back to {@code newer} without a usable older sample.
     *
     * @param older    previous attitude, or {@code null}
     * @param olderMs  sender epoch ms of {@code older}
     * @param newer    latest attitude
     * @param newerMs  sender epoch ms of {@code newer}
     * @param elapsedS local seconds since {@code newer} was received
     * @param dest     receives the (normalised) result
     * @return {@code dest}
     */
    public static Quaternionf extrapolate(Quaternionfc older, long olderMs, Quaternionfc newer, long newerMs,
                                          double elapsedS, Quaternionf dest) {
        dest.set(newer).normalize();
        long gapMs = newerMs - olderMs;
        if (older == null || gapMs <= 0L || gapMs > MAX_SAMPLE_GAP_MS || !(elapsedS > 0.0)) {
            return dest;
        }
        Quaternionf olderN = new Quaternionf(older).normalize();
        Quaternionf delta = new Quaternionf(dest).mul(olderN.conjugate()); // newer = delta · older
        if (delta.w < 0f) {
            delta.set(-delta.x, -delta.y, -delta.z, -delta.w);
        }
        double w = Math.clamp(delta.w, -1.0, 1.0);
        double angle = 2.0 * Math.acos(w);
        double sinHalf = Math.sqrt(Math.max(0.0, 1.0 - w * w));
        if (angle < 1e-6 || sinHalf < 1e-9) {
            return dest;
        }
        double rate = angle / (gapMs / 1000.0);
        double step = rate * Math.min(elapsedS, MAX_EXTRAPOLATION_S);
        float ax = (float) (delta.x / sinHalf);
        float ay = (float) (delta.y / sinHalf);
        float az = (float) (delta.z / sinHalf);
        Quaternionf advance = new Quaternionf().fromAxisAngleRad(ax, ay, az, (float) step);
        return dest.premul(advance).normalize();
    }

    /**
     * Moves {@code current} {@code fraction} of the way towards {@code target} along the shortest
     * arc (equivalently: rotate by {@code angle·fraction} about the axis of {@code target·current⁻¹}).
     *
     * @return {@code current}
     */
    public static Quaternionf smooth(Quaternionf current, Quaternionfc target, float fraction) {
        return current.slerp(target, Math.clamp(fraction, 0f, 1f)).normalize();
    }

    /** Rotation angle (rad, {@code [0, π]}) between two attitudes. */
    public static double angleBetween(Quaternionfc a, Quaternionfc b) {
        double dot = Math.abs((double) a.x() * b.x() + (double) a.y() * b.y() + (double) a.z() * b.z()
                + (double) a.w() * b.w());
        double na = Math.sqrt((double) a.x() * a.x() + (double) a.y() * a.y() + (double) a.z() * a.z()
                + (double) a.w() * a.w());
        double nb = Math.sqrt((double) b.x() * b.x() + (double) b.y() * b.y() + (double) b.z() * b.z()
                + (double) b.w() * b.w());
        return 2.0 * Math.acos(Math.min(1.0, dot / (na * nb)));
    }
}
