package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroneModelTest {

    private static void assertQuads(float[] v) {
        assertTrue(v.length > 0);
        assertEquals(0, v.length % (4 * DroneModel.FLOATS_PER_VERTEX), "whole quads");
        for (float f : v) {
            assertTrue(Float.isFinite(f));
        }
        for (int k = 3; k < v.length; k += DroneModel.FLOATS_PER_VERTEX) {
            for (int c = 0; c < 4; c++) {
                assertTrue(v[k + c] >= 0f && v[k + c] <= 1f, "colour in [0,1]");
            }
        }
    }

    @Test
    void presetsBuildFiniteGeometry() {
        for (DroneBuild b : new DroneBuild[] {DroneBuild.fiveInch(), DroneBuild.tinyWhoop()}) {
            DroneModel m = DroneModel.build(b);
            assertQuads(m.body());
            assertQuads(m.blades(1f));
            assertQuads(m.blades(-1f));
            assertQuads(m.disc());
            assertEquals((float) b.bladeLengthMetres(), m.propRadius(), 1e-6f);
        }
    }

    @Test
    void rotorsAreOnTheDiagonalsAndPropsDoNotOverlap() {
        DroneModel m = DroneModel.build(DroneBuild.fiveInch());
        Vector3f a = m.rotorOrigin(0, new Vector3f());
        Vector3f b = m.rotorOrigin(1, new Vector3f());
        Vector3f c = m.rotorOrigin(2, new Vector3f());
        assertEquals(Math.abs(a.x), Math.abs(a.z), 1e-5f);
        assertEquals(a.length(), c.length(), 1e-5f);
        assertTrue(a.distance(b) >= 2f * m.propRadius(), "neighbouring props clear each other");
        assertTrue(a.y > 0f || a.y > m.lowestY());
    }

    @Test
    void bladeCountScalesMesh() {
        DroneBuild two = DroneBuild.fiveInch();
        two.blades = 2;
        DroneBuild four = DroneBuild.fiveInch();
        four.blades = 4;
        assertEquals(2 * DroneModel.build(two).blades(1f).length, DroneModel.build(four).blades(1f).length);
    }

    @Test
    void extremeBuildsStayFinite() {
        DroneBuild big = DroneBuild.fiveInch();
        big.frameLength = 5000f;
        big.frameWidth = 5000f;
        big.propDiameter = 13f;
        big.blades = 16;
        big.cameraAngle = -90f;
        assertQuads(DroneModel.build(big.sanitize()).body());
        DroneBuild small = DroneBuild.tinyWhoop();
        small.propPitch = 0f;
        assertQuads(DroneModel.build(small.sanitize()).blades(-1f));
    }
}
