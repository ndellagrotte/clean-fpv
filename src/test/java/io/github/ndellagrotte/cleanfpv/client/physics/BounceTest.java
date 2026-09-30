package io.github.ndellagrotte.cleanfpv.client.physics;

import net.minecraft.util.math.AxisAlignedBB;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BounceTest {

    /** Wall with its face at x = 1. */
    private static final CollisionWorld WALL = region -> List.of(new AxisAlignedBB(1, -10, -10, 2, 10, 10));

    @Test
    void restitutionEndpoints() {
        assertEquals(0.2, Bounce.restitution(0.0), 1e-12);
        assertEquals(0.65, Bounce.restitution(Math.PI / 2), 1e-12);
        assertEquals(0.2 + 0.45 * Math.sin(0.5), Bounce.restitution(0.5), 1e-12);
    }

    private static Sweep.Result hitWall(Vector3d v, double h, double x0) {
        AxisAlignedBB start = PhysicsEngine.hitbox(x0, 0, 0);
        double ax = v.x * h;
        double ay = v.y * h;
        double az = v.z * h;
        Sweep.Result hit = Sweep.move(WALL, start, ax, ay, az);
        assertTrue(hit.collided());
        return Bounce.respond(WALL, hit, ax, ay, az, v);
    }

    @Test
    void headOnKeepsTwentyPercentAndReverses() {
        Vector3d v = new Vector3d(20, 0, 0);
        Sweep.Result end = hitWall(v, 0.05, 0.5);
        assertEquals(-4.0, v.x, 1e-9);
        assertEquals(0.0, v.y, 1e-12);
        assertEquals(0.0, v.z, 1e-12);
        assertTrue(end.box().maxX <= 1.0, "never inside the wall");
    }

    @Test
    void glancingKeepsNearlySixtyFivePercent() {
        Vector3d v = new Vector3d(0.5, 0, 30);
        double speed = v.length();
        Sweep.Result end = hitWall(v, 0.1, 0.85);
        // attempted = (0.05, 0, 3); blocked is along x, so θ = angle(attempted, x̂)
        double theta = Math.atan2(3.0, 0.05);
        double e = Bounce.restitution(theta);
        assertTrue(e > 0.64);
        assertEquals(speed * e, v.length(), 1e-9);
        assertTrue(v.x < 0, "normal component reversed");
        assertTrue(v.z > 0, "tangential direction kept");
        assertTrue(end.box().maxX <= 1.0);
    }

    @Test
    void slowImpactStopsCompletely() {
        Vector3d v = new Vector3d(4, 0, 0); // 0.2 × 4 = 0.8 < 1 m/s
        Sweep.Result end = hitWall(v, 0.2, 0.5);
        assertEquals(0.0, v.length(), 0.0);
        assertEquals(1.0, end.box().maxX, 1e-9);
    }

    @Test
    void degenerateAngleStops() {
        assertTrue(Double.isNaN(Bounce.angleBetween(0, 0, 0, 1, 0, 0)));
        Vector3d v = new Vector3d(Double.NaN, 0, 0);
        Sweep.Result hit = new Sweep.Result(PhysicsEngine.hitbox(0, 0, 0), 0, 0, 0, true, false, false);
        Bounce.respond(WALL, hit, 0.1, 0, 0, v);
        assertEquals(0.0, v.x, 0.0);
    }
}
