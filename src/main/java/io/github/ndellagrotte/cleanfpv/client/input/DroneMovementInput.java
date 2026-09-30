package io.github.ndellagrotte.cleanfpv.client.input;

import net.minecraft.util.MovementInput;

/**
 * Keeps vanilla from walking the drone (PLAN §5 "Movement keys", api-notes D4). Instead of replacing
 * {@code EntityPlayerSP.movementInput} (which vanilla re-assigns after the join event), the input
 * subscriber calls {@link #onInputUpdate} from Forge's {@code InputUpdateEvent}, which fires right
 * after {@code updatePlayerMoveState()} every client tick. While armed it records the raw key
 * states and then zeroes {@code moveForward/moveStrafe}, the four key-down flags, {@code jump} and
 * {@code sneak} (sneak must stay false or the eye height of the shrunken player goes negative;
 * a zero {@code jump} also stops the creative double-tap flight toggle).
 *
 * <p>The recorded keys are per-tick snapshots for tick-rate consumers (F). The stick builder reads
 * the key bindings directly each frame instead ({@link InputManager#pollFrame()}), so it does not
 * depend on this tick-rate copy.
 *
 * <p>Known leftovers vanilla applies after the event: auto-jump may set {@code jump} again for a
 * few ticks and item use scales the (already zero) move values; neither moves an armed drone
 * because orchestration zeroes the player's motion after its physics step.
 */
public final class DroneMovementInput {

    private boolean forward;
    private boolean back;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean sneak;

    DroneMovementInput() {}

    /** Records raw keys and neutralises {@code in} while armed; clears the record otherwise. */
    public void onInputUpdate(MovementInput in, boolean armed) {
        if (!armed) {
            clear();
            return;
        }
        forward = in.forwardKeyDown;
        back = in.backKeyDown;
        left = in.leftKeyDown;
        right = in.rightKeyDown;
        jump = in.jump;
        sneak = in.sneak;
        neutralize(in);
    }

    /** Zeroes every movement field vanilla reads from {@code in}. */
    public static void neutralize(MovementInput in) {
        in.moveForward = 0f;
        in.moveStrafe = 0f;
        in.forwardKeyDown = false;
        in.backKeyDown = false;
        in.leftKeyDown = false;
        in.rightKeyDown = false;
        in.jump = false;
        in.sneak = false;
    }

    public void clear() {
        forward = back = left = right = jump = sneak = false;
    }

    /** Raw forward key (W) at the last armed tick. */
    public boolean forward() {
        return forward;
    }

    /** Raw back key (S) at the last armed tick. */
    public boolean back() {
        return back;
    }

    /** Raw strafe-left key (A) at the last armed tick. */
    public boolean left() {
        return left;
    }

    /** Raw strafe-right key (D) at the last armed tick. */
    public boolean right() {
        return right;
    }

    /** Raw jump key (Space) at the last armed tick. */
    public boolean jump() {
        return jump;
    }

    /** Raw sneak key at the last armed tick. */
    public boolean sneak() {
        return sneak;
    }
}
