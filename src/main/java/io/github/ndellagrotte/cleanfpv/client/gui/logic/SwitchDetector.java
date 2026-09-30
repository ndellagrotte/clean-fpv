package io.github.ndellagrotte.cleanfpv.client.gui.logic;

import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;

/**
 * "Flip or press" capture of a switch channel (spec §11.1 arm-switch steps, §11.2 "press to
 * assign"): after a {@link #snapshot}, {@link #detect} reports the first button whose state changed
 * (button index {@code >= 0}), or else an axis that moved more than the threshold (as a
 * {@link ChannelMap#virtualIndex virtual-button} index {@code < 0}). Buttons win over axes because
 * a press is unambiguous. Returns {@link #NONE} while nothing changed. Pure logic.
 */
public final class SwitchDetector {

    /** "Nothing detected". Distinct from every valid button or virtual index. */
    public static final int NONE = Integer.MIN_VALUE;

    private boolean[] buttons = new boolean[0];
    private final AxisDetector axes = new AxisDetector();

    public void snapshot(float[] rawAxes, boolean[] rawButtons) {
        axes.snapshot(rawAxes);
        buttons = rawButtons == null ? new boolean[0] : rawButtons.clone();
    }

    /** Switch index of the changed input, or {@link #NONE}. */
    public int detect(float[] rawAxes, boolean[] rawButtons) {
        if (rawButtons != null) {
            for (int i = 0; i < rawButtons.length; i++) {
                boolean before = i < buttons.length && buttons[i];
                if (rawButtons[i] != before) {
                    return i;
                }
            }
        }
        int axis = axes.detect(rawAxes, AxisDetector.DEFAULT_THRESHOLD, null);
        if (axis >= 0) {
            return ChannelMap.virtualIndex(axis, rawAxes.length);
        }
        return NONE;
    }

    /**
     * Invert flag that makes the switch read "on" in its current (just detected) position: a button
     * that is now held needs no invert; a toggle moved to a position that reads "off" needs one.
     */
    public static boolean invertForOnNow(ChannelMap map, int index, float[] rawAxes, boolean[] rawButtons) {
        return !map.rawSwitch(index, rawAxes, rawButtons);
    }
}
