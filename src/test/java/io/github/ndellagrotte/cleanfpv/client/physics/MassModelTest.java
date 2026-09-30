package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MassModelTest {

    @Test
    void defaultBuildMatchesSpec() {
        // spec §5.4: "Default 5″ build: V ≈ 409 g → default ≈ 613 g"
        DroneBuild b = DroneBuild.fiveInch();
        double v = MassModel.componentMassGrams(b);
        assertEquals(409.0, v, 1.0);
        assertEquals(1.5 * v, MassModel.flownMassGrams(b), 1e-9);
        assertEquals(613.0, MassModel.flownMassGrams(b), 2.0);
    }

    @Test
    void tinyWhoopMatchesSpec() {
        // spec §5.4 formula on the §5.5 preset: 40 g base + 8×2.5 mm stators + 25 mm plates and
        // standoffs + small stack + 1S 450 mAh battery, no pro camera.
        DroneBuild b = DroneBuild.tinyWhoop();
        double v = MassModel.componentMassGrams(b);
        assertEquals(73.0, v, 1.0);
        assertEquals(109.5, MassModel.flownMassGrams(b), 1.0);
    }

    @Test
    void unitBugTermsStayUncounted() {
        // spec §13.1: prop blades, split camera and arms weigh ~0 g in the original; changing them
        // must not change the mass (the propulsion is tuned at the original's mass).
        DroneBuild b = DroneBuild.fiveInch();
        double v = MassModel.componentMassGrams(b);
        b.armWidth *= 3f;
        b.armThickness *= 3f;
        b.bladeWidth *= 1.5f;
        b.blades = 5;
        assertEquals(v, MassModel.componentMassGrams(b), 1e-9);
    }

    @Test
    void sliderIsClampedToOneToTwoTimesComponentMass() {
        DroneBuild b = DroneBuild.fiveInch();
        double v = MassModel.componentMassGrams(b);
        b.mass = 1f;
        assertEquals(v, MassModel.flownMassGrams(b), 1e-9);
        b.mass = 1e6f;
        assertEquals(2 * v, MassModel.flownMassGrams(b), 1e-9);
        b.mass = (float) (1.2 * v);
        assertEquals(1.2 * v, MassModel.flownMassGrams(b), 1e-3);
        b.mass = Float.NaN;
        assertEquals(1.5 * v, MassModel.flownMassGrams(b), 1e-9);
    }

    @Test
    void batteryRegressionAndFloor() {
        assertEquals(-19.69 + 36.48 + 26 - 0.64 + 104, MassModel.batteryGrams(4, 1300), 1e-9);
        assertEquals(10.0, MassModel.batteryGrams(1, 300), 1e-9);
    }

    @Test
    void dragDiskFromMass() {
        double m = 0.6;
        double r = Math.cbrt(3 * m / (1900 * 4 * Math.PI));
        assertEquals(1.225 * Math.PI * r * r * 1.05 / 2, DragModel.dragFactor(m), 1e-12);
        assertEquals(0.0, DragModel.dragFactor(0.0));
    }
}
