package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

/**
 * Collision query used by the sweep (PLAN §6.3: Y → X → Z like {@code Entity.move}).
 *
 * <p>Design choice: this uses Minecraft's {@link AxisAlignedBB} rather than an own box type. It is
 * plain immutable data (constructible in unit tests without bootstrapping the game) and its
 * {@code calculateX/Y/ZOffset} are exactly what the server's replay uses, which the sweep must
 * match for "moved wrongly" parity. This is the only Minecraft type the physics package uses.
 *
 * <p>The in-game implementation wraps {@code world.getCollisionBoxes(player, region)}; tests use a
 * fixed list.
 */
@FunctionalInterface
public interface CollisionWorld {

    /** Every solid box intersecting {@code region} (blocks, world border, collidable entities). */
    List<AxisAlignedBB> getCollisionBoxes(AxisAlignedBB region);

    /** A world with nothing in it. */
    CollisionWorld EMPTY = region -> List.of();
}
