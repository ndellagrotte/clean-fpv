package io.github.ndellagrotte.cleanfpv.client.input;

/**
 * Level-triggered switch with a 200 ms debounce (spec §3.2, PLAN §6.1), reporting accepted edges.
 * Pure: time is passed in, so it is unit-tested directly.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>Baseline.</b> The first reading after construction, {@link #reset()} or the source
 *       becoming available is taken as the current state <em>without</em> an edge, so a switch that
 *       is already ON when a radio is plugged in (or after a reconnect) does not arm by itself.</li>
 *   <li><b>Lockout debounce.</b> A level change is accepted immediately if at least
 *       {@link #DEBOUNCE_MS} passed since the last accepted change (or baseline); otherwise it is
 *       <em>deferred</em>, not dropped: every later poll compares the level again, so the change is
 *       accepted once the lockout expires if the switch still differs. (The original swallowed such
 *       changes for good, which could leave the drone armed with the switch OFF.)</li>
 *   <li><b>Source lost.</b> When the source becomes unavailable (joystick unplugged, scheme switched
 *       to keyboard) while the accepted state is ON, a {@link Edge#FALLING} edge is reported once
 *       (failsafe disarm); the next available reading is a fresh baseline.</li>
 * </ul>
 */
public final class SwitchDebouncer {

    /** Minimum time between two accepted changes (spec: 200 ms arm debounce). */
    public static final long DEBOUNCE_MS = 200L;

    /** An accepted change. */
    public enum Edge { NONE, RISING, FALLING }

    private final long debounceMs;
    private boolean known;
    private boolean state;
    private long lastChangeMs;

    public SwitchDebouncer() {
        this(DEBOUNCE_MS);
    }

    public SwitchDebouncer(long debounceMs) {
        this.debounceMs = Math.max(0L, debounceMs);
    }

    /**
     * Feeds one reading.
     *
     * @param available whether the source can be read at all this poll
     * @param level     the raw switch level (ignored when {@code !available})
     * @param nowMs     monotonic milliseconds
     * @return the accepted edge, or {@link Edge#NONE}
     */
    public Edge update(boolean available, boolean level, long nowMs) {
        if (!available) {
            boolean wasOn = known && state;
            known = false;
            state = false;
            return wasOn ? Edge.FALLING : Edge.NONE;
        }
        if (!known) {
            known = true;
            state = level;
            lastChangeMs = nowMs;
            return Edge.NONE;
        }
        if (level == state || nowMs - lastChangeMs < debounceMs) {
            return Edge.NONE;
        }
        state = level;
        lastChangeMs = nowMs;
        return level ? Edge.RISING : Edge.FALLING;
    }

    /** The accepted (debounced) level; {@code false} while unknown. */
    public boolean state() {
        return known && state;
    }

    /** Forgets the state; the next available reading is a baseline (no edge). */
    public void reset() {
        known = false;
        state = false;
    }
}
