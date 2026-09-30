package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepTest {

    private static final double EPS = 1e-9;
    private static final double HALF = 0.2f / 2.0f;
    private static final double HEIGHT = 0.1f;

    /** Floor (top at y=0), a wall at x ∈ [1,2] from the floor up to y=3, and a 1-block ledge at z ∈ [2,3]. */
    private static final List<AxisAlignedBB> WORLD = List.of(
            new AxisAlignedBB(-10, -1, -10, 10, 0, 10),
            new AxisAlignedBB(1, 0, -10, 2, 3, 10),
            new AxisAlignedBB(-10, 0, 2, 1, 1, 3));

    private static AxisAlignedBB boxAt(double x, double y, double z) {
        return PhysicsEngine.hitbox(x, y, z);
    }

    @Test
    void clipsEachAxisAgainstHandBuiltBoxes() {
        Sweep.Result r = Sweep.move(region -> WORLD, boxAt(0, 0.5, 0), 2.0, -1.0, 0.5);
        // Y first: lands on the floor (dy = −0.5)
        assertEquals(-0.5, r.dy(), EPS);
        assertTrue(r.collidedY());
        // X second: stops against the wall face x = 1
        assertEquals(1.0 - HALF, r.posX(), EPS);
        assertTrue(r.collidedX());
        // Z last: free
        assertEquals(0.5, r.dz(), EPS);
        assertFalse(r.collidedZ());
        assertEquals(0.0, r.posY(), EPS);
    }

    @Test
    void axisOrderIsYThenXThenZ() {
        // Moving up and toward the ledge: Y is resolved first (under nothing: free), then Z hits the ledge.
        Sweep.Result r = Sweep.move(region -> WORLD, boxAt(0, 0.5, 1.5), 0.0, 1.0, 1.0);
        // Y first to 1.5 (above the ledge top 1.0), so Z is free.
        assertEquals(1.5, r.posY(), EPS);
        assertEquals(2.5, r.posZ(), EPS);
        assertFalse(r.collided());

        // Moving down and toward the ledge from above: Y first lands... nowhere (no box below at z=1.5
        // except the floor), then Z is blocked by the ledge side because the box is now below y=1.
        Sweep.Result d = Sweep.move(region -> WORLD, boxAt(0, 1.2, 1.5), 0.0, -0.9, 1.0);
        assertEquals(0.3, d.posY(), 1e-9);
        assertTrue(d.collidedZ());
        assertEquals(2.0 - HALF, d.posZ(), EPS);
        // An X-before-Y order would have been different: Z first would clear the ledge (y still 1.2),
        // then Y would land on the ledge top.
    }

    @Test
    void landsOnTopAndDoesNotTunnelAtHighSpeed() {
        Sweep.Result r = Sweep.move(region -> WORLD, boxAt(0, 5, 0), 0, -500, 0);
        assertEquals(0.0, r.posY(), EPS);
        Sweep.Result x = Sweep.move(region -> WORLD, boxAt(0, 0.5, 0), 500, 0, 0);
        assertEquals(1.0 - HALF, x.posX(), EPS);
    }

    @Test
    void ignoresBoxesAlreadyOverlapping() {
        // Start inside the wall: vanilla lets you move out (and further through it).
        Sweep.Result r = Sweep.move(region -> WORLD, boxAt(1.5, 0.5, 0), 1.0, 0, 0);
        assertEquals(2.5, r.posX(), EPS);
    }

    @Test
    void hitboxMatchesEntitySetPosition() {
        AxisAlignedBB b = PhysicsEngine.hitbox(3, 4, 5);
        assertEquals(3 - (double) (0.2f / 2.0f), b.minX, 0.0);
        assertEquals(4 + (double) 0.1f, b.maxY, 0.0);
        assertEquals(HEIGHT, b.maxY - b.minY, 1e-12);
        assertEquals(io.github.ndellagrotte.cleanfpv.common.PlayerSizing.DRONE_WIDTH, PhysicsEngine.HITBOX_WIDTH);
        assertEquals(io.github.ndellagrotte.cleanfpv.common.PlayerSizing.DRONE_HEIGHT, PhysicsEngine.HITBOX_HEIGHT);
    }

    @Test
    void cacheReusesOneQueryForNearbyRequests() {
        int[] calls = {0};
        CollisionWorld counting = region -> {
            calls[0]++;
            return WORLD;
        };
        CollisionCache cache = new CollisionCache(counting);
        cache.getCollisionBoxes(boxAt(0, 0.5, 0).expand(0.1, 0, 0));
        cache.getCollisionBoxes(boxAt(0.3, 0.5, 0).expand(0.1, -0.2, 0.3));
        assertEquals(1, calls[0]);
        cache.getCollisionBoxes(boxAt(5, 0.5, 0));
        assertEquals(2, calls[0]);
    }
}
