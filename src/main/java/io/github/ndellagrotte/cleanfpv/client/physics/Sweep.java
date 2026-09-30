package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;

import java.util.List;

/**
 * Box sweep with vanilla's axis order <b>Y → X → Z</b> ({@code Entity.move}, the part the server
 * replays in {@code NetHandlerPlayServer.processPlayer}), using
 * {@link AxisAlignedBB#calculateYOffset}/{@code X}/{@code Z} against one box list gathered for the
 * whole expanded region. No step-up (the drone's {@code stepHeight} is 0 on both sides), no sneak
 * edge logic, no web slow-down. Pure: the only Minecraft type is the {@link AxisAlignedBB} data
 * class. Stateless.
 */
public final class Sweep {

    private Sweep() {}

    /**
     * Outcome of one sweep.
     *
     * @param box       the moved box
     * @param dx        actual X displacement
     * @param dy        actual Y displacement
     * @param dz        actual Z displacement
     * @param collidedX X was clipped
     * @param collidedY Y was clipped
     * @param collidedZ Z was clipped
     */
    public record Result(AxisAlignedBB box, double dx, double dy, double dz,
                         boolean collidedX, boolean collidedY, boolean collidedZ) {

        public boolean collided() {
            return collidedX || collidedY || collidedZ;
        }

        /** Feet-centre X of the moved box ({@code Entity.resetPositionToBB}). */
        public double posX() {
            return (box.minX + box.maxX) / 2.0;
        }

        /** Feet Y of the moved box. */
        public double posY() {
            return box.minY;
        }

        /** Feet-centre Z of the moved box. */
        public double posZ() {
            return (box.minZ + box.maxZ) / 2.0;
        }
    }

    /**
     * Moves {@code start} by (dx, dy, dz) as far as the world allows.
     *
     * @param world collision source, queried once with {@code start.expand(dx, dy, dz)}
     * @param start the box at the start position
     */
    public static Result move(CollisionWorld world, AxisAlignedBB start, double dx, double dy, double dz) {
        List<AxisAlignedBB> boxes = world.getCollisionBoxes(start.expand(dx, dy, dz));
        return move(boxes, start, dx, dy, dz);
    }

    /** As {@link #move(CollisionWorld, AxisAlignedBB, double, double, double)} with a given box list. */
    public static Result move(List<AxisAlignedBB> boxes, AxisAlignedBB start, double dx, double dy, double dz) {
        double x = dx;
        double y = dy;
        double z = dz;
        AxisAlignedBB bb = start;
        int n = boxes.size();
        if (y != 0.0) {
            for (int i = 0; i < n; i++) {
                y = boxes.get(i).calculateYOffset(bb, y);
            }
            bb = bb.offset(0.0, y, 0.0);
        }
        if (x != 0.0) {
            for (int i = 0; i < n; i++) {
                x = boxes.get(i).calculateXOffset(bb, x);
            }
            if (x != 0.0) {
                bb = bb.offset(x, 0.0, 0.0);
            }
        }
        if (z != 0.0) {
            for (int i = 0; i < n; i++) {
                z = boxes.get(i).calculateZOffset(bb, z);
            }
            if (z != 0.0) {
                bb = bb.offset(0.0, 0.0, z);
            }
        }
        return new Result(bb, x, y, z, x != dx, y != dy, z != dz);
    }
}
