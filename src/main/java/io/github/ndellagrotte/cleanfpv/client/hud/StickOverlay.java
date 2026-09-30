package io.github.ndellagrotte.cleanfpv.client.hud;

import io.github.ndellagrotte.cleanfpv.client.input.StickState;
import net.minecraft.client.gui.Gui;

/**
 * The two stick gimbals (spec §6.4): plus-shaped crosses with a small square marker each. Left
 * gimbal = (yaw, throttle), right gimbal = (roll, pitch); positive values move the marker right and
 * up (throttle high = top, pitch forward = top, as seen on a Mode 2 radio). While armed the right
 * gimbal also shows the mouse attitude input as stick deflection ({@code roll + mouse-x},
 * {@code pitch + mouse-y}), see {@link #draw(int, int, StickState, float, float)}.
 *
 * <p>Usable from any GUI/HUD draw call in scaled GUI pixels (settings screens and the wizard may
 * call {@link #draw} directly to preview live sticks).
 */
public final class StickOverlay {

    /** Half the length of each gimbal cross, px. */
    public static final int REACH = 10;
    /** Gap between the given centre x and the inner end of each gimbal cross, px. */
    public static final int OFFSET = 15;
    /** Marker edge, px. */
    public static final int MARKER = 2;

    private static final int CROSS_COLOUR = 0x90FFFFFF;
    private static final int MARKER_COLOUR = 0xFFFFD040;
    private static final int SHADOW_COLOUR = 0x60000000;

    private StickOverlay() {}

    /** Draws both gimbals centred on {@code (centreX, centreY)} (sticks only: previews, disarmed). */
    public static void draw(int centreX, int centreY, StickState sticks) {
        draw(centreX, centreY, sticks, 0f, 0f);
    }

    /**
     * Draws both gimbals with the mouse attitude input added to the right gimbal.
     *
     * @param mouseRoll    mouse roll as stick deflection (positive = right)
     * @param mousePitchUp mouse pitch as stick deflection (positive = nose up, i.e. stick back)
     */
    public static void draw(int centreX, int centreY, StickState sticks, float mouseRoll, float mousePitchUp) {
        gimbal(centreX - OFFSET - REACH, centreY, sticks.yaw(), sticks.thr());
        gimbal(centreX + OFFSET + REACH, centreY, rightX(sticks, mouseRoll), rightY(sticks, mousePitchUp));
    }

    /** Right gimbal x: roll stick plus mouse roll (same sign as {@code Attitude.frameStep}). */
    public static float rightX(StickState sticks, float mouseRoll) {
        return sticks.roll() + (Float.isFinite(mouseRoll) ? mouseRoll : 0f);
    }

    /** Right gimbal y: pitch stick minus mouse nose-up (frameStep: {@code rate(pitch)·dt − mousePitchUp}). */
    public static float rightY(StickState sticks, float mousePitchUp) {
        return sticks.pitch() - (Float.isFinite(mousePitchUp) ? mousePitchUp : 0f);
    }

    /** One gimbal centred on {@code (cx, cy)} with the marker at stick {@code (x, y)} ∈ [−1, 1]². */
    public static void gimbal(int cx, int cy, float x, float y) {
        Gui.drawRect(cx - REACH, cy, cx + REACH + 1, cy + 1, CROSS_COLOUR);
        Gui.drawRect(cx, cy - REACH, cx + 1, cy + REACH + 1, CROSS_COLOUR);
        int mx = cx + markerOffset(x);
        int my = cy - markerOffset(y);
        Gui.drawRect(mx - MARKER / 2 - 1, my - MARKER / 2 - 1, mx + MARKER / 2 + 2, my + MARKER / 2 + 2, SHADOW_COLOUR);
        Gui.drawRect(mx - MARKER / 2, my - MARKER / 2, mx + MARKER / 2 + 1, my + MARKER / 2 + 1, MARKER_COLOUR);
    }

    /** Pixel offset of the marker for an axis value (clamped, NaN → centre). */
    public static int markerOffset(float axis) {
        if (!Float.isFinite(axis)) {
            return 0;
        }
        return Math.round(Math.clamp(axis, -1f, 1f) * REACH);
    }
}
