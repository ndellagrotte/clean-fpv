package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

/**
 * Per-step cache in front of a {@link CollisionWorld}: the world query ({@code
 * World.getCollisionBoxes}, which is not cheap) runs once for a region grown by {@link #MARGIN}
 * and later requests that fit inside it reuse that list. Returning a superset of the boxes is safe
 * for the sweep: {@code calculate*Offset} ignores boxes that are not in the path. Create a fresh
 * cache for every physics step (the world may change between ticks).
 */
public final class CollisionCache implements CollisionWorld {

    /** Growth of the cached region beyond the first request (blocks). */
    public static final double MARGIN = 1.0;

    private final CollisionWorld world;
    private AxisAlignedBB region;
    private List<AxisAlignedBB> boxes = List.of();

    public CollisionCache(CollisionWorld world) {
        this.world = world;
    }

    @Override
    public List<AxisAlignedBB> getCollisionBoxes(AxisAlignedBB request) {
        if (region == null || !contains(region, request)) {
            region = request.grow(MARGIN);
            boxes = world.getCollisionBoxes(region);
            if (boxes == null) {
                boxes = List.of();
            }
        }
        return boxes;
    }

    private static boolean contains(AxisAlignedBB outer, AxisAlignedBB inner) {
        return inner.minX >= outer.minX && inner.minY >= outer.minY && inner.minZ >= outer.minZ
                && inner.maxX <= outer.maxX && inner.maxY <= outer.maxY && inner.maxZ <= outer.maxZ;
    }
}
