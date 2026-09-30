package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;

/**
 * Converts {@code InputEvent.MouseTurnEvent} values into this frame's roll/pitch displacement
 * (spec §3.2/§4.3, PLAN §6.1). Pure; the event subscription belongs to orchestration (F), which
 * calls these from its {@code MouseTurnEvent} handler, adds the result <em>directly</em> to that
 * frame's roll/pitch rotation angles (a displacement, not a rate: not multiplied by dt, unbounded)
 * and cancels the event while armed.
 *
 * <h2>Units</h2>
 * The event carries exactly what vanilla would pass to {@code Entity.turn}: mouse counts ×
 * {@code (sensitivity·0.6+0.2)³·8}, vanilla "invert mouse" already applied to pitch, smooth camera
 * already filtered. {@code Entity.turn} multiplies by 0.15 to get degrees, so one event unit is
 * {@value #DEGREES_PER_EVENT_UNIT}°. The mod's own per-model {@code mouseGain} (default 1.0) scales on
 * top; at vanilla sensitivity 0.5 the event equals raw counts, so the original's 0.007 rad/count feel
 * is about {@code mouseGain 2.7}.
 *
 * <h2>Signs (verified against {@code Entity.turn}: {@code rotationYaw += yaw·0.15},
 * {@code rotationPitch −= pitch·0.15}, Minecraft pitch positive = looking down)</h2>
 * <ul>
 *   <li>Event yaw &gt; 0 = mouse moved right = vanilla turns right. {@link #rollRad} keeps the sign:
 *       <b>positive = roll right</b> (right side down, clockwise as seen by the pilot).</li>
 *   <li>Event pitch &gt; 0 = mouse moved up (or down with invert mouse) = vanilla looks <em>up</em>
 *       ({@code rotationPitch} decreases). {@link #pitchRad} keeps the sign: <b>positive = nose
 *       up</b>. F must apply it to the body pitch so that a positive value raises body forward
 *       toward body up. With the contract's body axes (forward = q·+Z, up = q·+Y, right = up ×
 *       forward) a positive right-handed rotation about {@code right} turns forward toward
 *       <em>−up</em> (nose down), so F rotates about {@code right} by {@code −pitchRad} (or about
 *       {@code forward × up}, the pilot's actual right-hand side, by {@code +pitchRad}) — whichever
 *       matches A's {@code Attitude} convention for the stick pitch (stick back = nose up).</li>
 *   <li>Keyboard/mouse never yaw (spec §3.2).</li>
 * </ul>
 */
public final class MouseInput {

    /** Degrees per {@code MouseTurnEvent} unit ({@code Entity.turn} scale). */
    public static final float DEGREES_PER_EVENT_UNIT = 0.15f;
    /** Radians per {@code MouseTurnEvent} unit at {@code mouseGain} 1. */
    public static final double RADIANS_PER_EVENT_UNIT = Math.toRadians(DEGREES_PER_EVENT_UNIT);

    private MouseInput() {}

    /** Roll displacement (radians, positive = roll right) for an event yaw value. */
    public static float rollRad(float eventYaw, float mouseGain) {
        return convert(eventYaw, mouseGain);
    }

    /** Pitch displacement (radians, positive = nose up) for an event pitch value. */
    public static float pitchRad(float eventPitch, float mouseGain) {
        return convert(eventPitch, mouseGain);
    }

    /**
     * The mouse displacement of one frame expressed as stick deflection for the stick overlay
     * (spec §6.4 "roll + mouse-x, pitch + mouse-y"): {@code mouseRad / (fullRate · dt)}, clamped to
     * [−1, 1]; 0 when {@code dt} or the full-deflection rate is not positive or anything is not finite.
     *
     * @param mouseRad        this frame's mouse displacement (radians)
     * @param fullRateRadPerS the axis' commanded rate at full stick (rad/s)
     * @param dt              frame time (s)
     */
    public static float overlayStick(double mouseRad, double fullRateRadPerS, double dt) {
        double denom = fullRateRadPerS * dt;
        if (!Double.isFinite(mouseRad) || !Double.isFinite(denom) || !(denom > 0.0)) {
            return 0f;
        }
        return (float) Math.clamp(mouseRad / denom, -1.0, 1.0);
    }

    /** Non-finite → 1, otherwise clamped to the model's allowed range. */
    public static float sanitizeGain(float mouseGain) {
        if (!Float.isFinite(mouseGain)) {
            return 1f;
        }
        return Math.clamp(mouseGain, DroneModelConfig.MOUSE_GAIN_MIN, DroneModelConfig.MOUSE_GAIN_MAX);
    }

    private static float convert(float eventValue, float mouseGain) {
        if (!Float.isFinite(eventValue)) {
            return 0f;
        }
        return (float) (eventValue * RADIANS_PER_EVENT_UNIT * sanitizeGain(mouseGain));
    }
}
