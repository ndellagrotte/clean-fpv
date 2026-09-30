package io.github.ndellagrotte.cleanfpv.client.audio;

/**
 * Motor-sound mapping (spec §7, PLAN §6.5). Pure math, no Minecraft types.
 * {@code r = |ω₀| / ωmax} (motor 0 only, clamped to [0, 1]); pitch {@code 0.5 + 1.5·r} (exactly
 * the sound engine's [0.5, 2] range); volume {@code 0.25 + 0.75·r} (never 0, so the sound engine
 * never skips the sound at start).
 */
public final class MotorTone {

    public static final float MIN_PITCH = 0.5f;
    public static final float MAX_PITCH = 2.0f;
    public static final float MIN_VOLUME = 0.25f;
    public static final float MAX_VOLUME = 1.0f;

    private MotorTone() {}

    /** Normalised motor speed in [0, 1]; 0 for a non-positive/non-finite {@code omegaMax} or non-finite ω. */
    public static float ratio(double omega0, double omegaMax) {
        if (!(omegaMax > 0.0) || !Double.isFinite(omegaMax) || !Double.isFinite(omega0)) {
            return 0f;
        }
        return (float) Math.clamp(Math.abs(omega0) / omegaMax, 0.0, 1.0);
    }

    public static float pitch(float ratio) {
        return Math.clamp(MIN_PITCH + (MAX_PITCH - MIN_PITCH) * ratio, MIN_PITCH, MAX_PITCH);
    }

    public static float volume(float ratio) {
        return Math.clamp(MIN_VOLUME + (MAX_VOLUME - MIN_VOLUME) * ratio, MIN_VOLUME, MAX_VOLUME);
    }
}
