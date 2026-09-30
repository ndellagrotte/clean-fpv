package io.github.ndellagrotte.cleanfpv.client.flight;

/**
 * The pure decisions of the arm state machine (spec §4.1, PLAN §6.2, §6.9), kept free of Minecraft
 * types so they are unit-tested: the arming guards, the forced-disarm trigger, the flying state
 * after a disarm, the momentum hand-over and the disarmed velocity-tracking jump filter.
 * {@link ArmController} gathers the inputs from the game and applies the results.
 */
public final class ArmRules {

    /** Seconds per client tick: velocity (m/s) × this = vanilla motion (blocks/tick). */
    public static final double SECONDS_PER_TICK = 0.05;

    private ArmRules() {}

    /** Why an arm request was refused ({@link #NONE} = arm). Each refusal names its HUD message. */
    public enum Refusal {
        /** Arming allowed. */
        NONE(null),
        /** No Hello from the server: it does not run the mod (PLAN §6.6, §8). */
        NO_SERVER("cleanfpv.message.arm_refused.no_server"),
        /** The server runs an incompatible protocol version. */
        PROTOCOL("cleanfpv.message.arm_refused.protocol"),
        /** The player rides an entity; the mount would fight the drone's position every tick. */
        RIDING("cleanfpv.message.arm_refused.riding"),
        /** Throttle-low guard (spec §3.2, §13.1: enforced here, unlike the original). */
        THROTTLE("cleanfpv.message.arm_refused.throttle");

        private final String langKey;

        Refusal(String langKey) {
            this.langKey = langKey;
        }

        /** Translation key of the HUD message, {@code null} for {@link #NONE}. */
        public String langKey() {
            return langKey;
        }
    }

    /**
     * Arming guards in priority order: server handshake, protocol, mount, throttle.
     *
     * @param helloReceived        the server's Hello arrived on this connection
     * @param serverProtocol       protocol version from the Hello
     * @param clientProtocol       this build's protocol version
     * @param riding               the player rides an entity
     * @param throttleGuardApplies the sticks come from a joystick (keyboard flight idles at 50 %
     *                             by design and is exempt, see {@code InputManager.throttleGuardApplies})
     * @param throttleLow          {@code StickState.throttleLow()}
     */
    public static Refusal check(boolean helloReceived, int serverProtocol, int clientProtocol, boolean riding,
                                boolean throttleGuardApplies, boolean throttleLow) {
        if (!helloReceived) {
            return Refusal.NO_SERVER;
        }
        if (serverProtocol != clientProtocol) {
            return Refusal.PROTOCOL;
        }
        if (riding) {
            return Refusal.RIDING;
        }
        if (throttleGuardApplies && !throttleLow) {
            return Refusal.THROTTLE;
        }
        return Refusal.NONE;
    }

    /**
     * Whether an armed pilot must be disarmed with a full restore (PLAN §6.9, api-notes D3):
     * a pending request (server {@code Arm(false)}, disconnect, world unload), the player entity
     * gone or replaced (respawn, dimension change), or the player dead ({@code health <= 0} /
     * {@code isDead}; the client never sees its own {@code LivingDeathEvent}).
     */
    public static boolean mustForceDisarm(boolean armed, boolean requested, boolean playerPresent,
                                          boolean samePlayer, boolean alive) {
        return armed && (requested || !playerPresent || !samePlayer || !alive);
    }

    /**
     * {@code capabilities.isFlying} after a disarm: spectators always fly; creative players get back
     * the state they had when arming; everyone else falls (the drone's momentum carries on as
     * vanilla motion). The server's ability packet follows shortly and agrees: {@code DroneServer}
     * records {@code isFlying} at arm and restores it for creative ({@code flyingAfterRestore}).
     */
    public static boolean flyingAfterDisarm(boolean creative, boolean spectator, boolean flyingAtArm) {
        if (spectator) {
            return true;
        }
        return creative && flyingAtArm;
    }

    /** Spec §4.1/§5.1: vanilla motion (blocks/tick) from a drone velocity (m/s); non-finite → 0. */
    public static double handOverMotion(double velocity) {
        return Double.isFinite(velocity) ? velocity * SECONDS_PER_TICK : 0.0;
    }

    /**
     * Whether a disarmed position change within one tick is a teleport rather than motion: longer
     * than the absolute speed cap covers in a tick. Such a jump re-seeds the velocity tracker at
     * zero instead of feeding a huge velocity into the next arm.
     */
    public static boolean isTeleport(double dx, double dy, double dz, double maxSpeed) {
        double d2 = dx * dx + dy * dy + dz * dz;
        if (!Double.isFinite(d2)) {
            return true;
        }
        double max = maxSpeed * SECONDS_PER_TICK;
        return d2 > max * max;
    }
}
