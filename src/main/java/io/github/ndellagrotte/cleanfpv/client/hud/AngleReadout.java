package io.github.ndellagrotte.cleanfpv.client.hud;

/**
 * Visibility of the OSD line {@code angle: N} (spec §6.4): shown for {@link #SHOW_MS} after the
 * camera-angle knob moved by more than {@link #MOVE_THRESHOLD} while the activate-angle switch is
 * on, and also right after the switch is turned on (so the pilot sees the tilt that now applies).
 * Hidden immediately when the switch goes off. Pure state machine (no Minecraft types).
 */
public final class AngleReadout {

    public static final long SHOW_MS = 3000L;
    public static final float MOVE_THRESHOLD = 0.05f;

    private boolean switchWasOn;
    private float anchor;
    private long visibleUntilMs = Long.MIN_VALUE;

    /** Feeds this frame's switch/knob state. */
    public void update(boolean switchOn, float knob, long nowMs) {
        if (!Float.isFinite(knob)) {
            knob = 0f;
        }
        if (!switchOn) {
            switchWasOn = false;
            anchor = knob;
            visibleUntilMs = Long.MIN_VALUE;
            return;
        }
        if (!switchWasOn || Math.abs(knob - anchor) > MOVE_THRESHOLD) {
            anchor = knob;
            visibleUntilMs = nowMs + SHOW_MS;
        }
        switchWasOn = true;
    }

    public boolean visible(long nowMs) {
        return nowMs < visibleUntilMs;
    }

    public void reset() {
        switchWasOn = false;
        anchor = 0f;
        visibleUntilMs = Long.MIN_VALUE;
    }
}
