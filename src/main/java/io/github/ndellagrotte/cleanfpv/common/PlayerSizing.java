package io.github.ndellagrotte.cleanfpv.common;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Drone hitbox sizing shared by both sides (PLAN §5 "Entity size"): shrink while armed, restore
 * on disarm. Relies on the access transformer making {@code Entity.setSize(FF)V} public.
 *
 * <p>Call {@link #shrink} in {@code PlayerTickEvent} END on both sides (vanilla's
 * {@code updateSize()} runs before END and would undo it) and before the client physics step.
 */
public final class PlayerSizing {

    public static final float DRONE_WIDTH = 0.2f;
    public static final float DRONE_HEIGHT = 0.1f;
    public static final float DRONE_EYE_HEIGHT = 0.05f;

    public static final float PLAYER_WIDTH = 0.6f;
    public static final float PLAYER_HEIGHT = 1.8f;
    public static final float PLAYER_STEP_HEIGHT = 0.6f;

    private PlayerSizing() {}

    /** Drone size: 0.2 × 0.1 box, eye 0.05, no step-up. Idempotent. */
    public static void shrink(EntityPlayer player) {
        if (player.width != DRONE_WIDTH || player.height != DRONE_HEIGHT) {
            player.setSize(DRONE_WIDTH, DRONE_HEIGHT);
        }
        player.eyeHeight = DRONE_EYE_HEIGHT;
        player.stepHeight = 0f;
    }

    /**
     * Vanilla player size: 0.6 × 1.8, default eye height, step 0.6. Idempotent.
     *
     * <p>Growing through {@code Entity.setSize} anchors the new box at the old min corner (centre
     * +0.2/+0.2) and, on the server only, then runs {@code move(SELF, −0.4, 0, −0.4)}, so client and
     * server would end up 0.4 apart per axis ("moved wrongly", clipping into a +X/+Z wall). The feet
     * position is therefore put back afterwards on both sides, re-centring the grown box.
     */
    public static void restore(EntityPlayer player) {
        if (player.width != PLAYER_WIDTH || player.height != PLAYER_HEIGHT) {
            double x = player.posX;
            double y = player.posY;
            double z = player.posZ;
            player.setSize(PLAYER_WIDTH, PLAYER_HEIGHT);
            player.setPosition(x, y, z);
        }
        player.eyeHeight = player.getDefaultEyeHeight();
        player.stepHeight = PLAYER_STEP_HEIGHT;
    }

    public static boolean isShrunk(EntityPlayer player) {
        return player.width == DRONE_WIDTH && player.height == DRONE_HEIGHT;
    }
}
