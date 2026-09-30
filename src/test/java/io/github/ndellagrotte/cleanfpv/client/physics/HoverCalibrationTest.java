package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static calibration of both built-in presets against the spec reference (spec §5.2–§5.5
 * evaluated as written: 5" 4S at 613 g hovers at ≈ 31.7 % throttle with thrust-to-weight ≈ 5.04;
 * Tiny Whoop at 110 g hovers at ≈ 57.1 % with T/W ≈ 2.53). Hover throttle = settled static thrust
 * (drone held still, level) equal to the weight; T/W = settled static thrust at full throttle over
 * the weight.
 *
 * <p>Tolerances: hover ±3 points, T/W ≈ ±7 %, wide enough for our own lift/drag curve fits and
 * winding-temperature drift, tight enough that the two presets cannot be confused (the 5" has about
 * twice the whoop's T/W and hovers 25 points lower).
 */
class HoverCalibrationTest {

    private static double thrustToWeight(ThrustModel m, DroneBuild b) {
        return Hover.settledThrust(m, b, 1.0) / (MassModel.flownMassKg(b) * Stepper.GRAVITY);
    }

    @Test
    void fiveInchMatchesTheReference() {
        DroneBuild b = DroneBuild.fiveInch();
        assertEquals(613.0, MassModel.flownMassGrams(b), 2.0);
        double hover = Hover.hoverThrottle(new ThrustV2(), b);
        double tw = thrustToWeight(new ThrustV2(), b);
        assertTrue(hover >= 0.29 && hover <= 0.35, "5\" hover throttle " + hover);
        assertTrue(tw >= 4.7 && tw <= 5.4, "5\" T/W " + tw);
    }

    @Test
    void tinyWhoopMatchesTheReference() {
        DroneBuild b = DroneBuild.tinyWhoop();
        assertEquals(109.5, MassModel.flownMassGrams(b), 1.0);
        double hover = Hover.hoverThrottle(new ThrustV2(), b);
        double tw = thrustToWeight(new ThrustV2(), b);
        assertTrue(hover >= 0.54 && hover <= 0.61, "whoop hover throttle " + hover);
        assertTrue(tw >= 2.35 && tw <= 2.75, "whoop T/W " + tw);
    }

    @Test
    void fiveInchAndWhoopAreClearlyDifferent() {
        // Guard against the presets collapsing into one feel (the 5" once flew like a whoop).
        DroneBuild five = DroneBuild.fiveInch();
        DroneBuild whoop = DroneBuild.tinyWhoop();
        for (boolean hiFi : new boolean[] {true, false}) {
            String name = hiFi ? "v2" : "v1";
            double twFive = thrustToWeight(hiFi ? new ThrustV2() : new ThrustV1(), five);
            double twWhoop = thrustToWeight(hiFi ? new ThrustV2() : new ThrustV1(), whoop);
            double hFive = Hover.hoverThrottle(hiFi ? new ThrustV2() : new ThrustV1(), five);
            double hWhoop = Hover.hoverThrottle(hiFi ? new ThrustV2() : new ThrustV1(), whoop);
            assertTrue(twFive >= 1.8 * twWhoop, name + " 5\" T/W " + twFive + " vs whoop " + twWhoop);
            assertTrue(hFive <= hWhoop - 0.2, name + " 5\" hover " + hFive + " vs whoop " + hWhoop);
        }
    }

    @Test
    void v1MatchesV2StaticallyForBothPresets() {
        for (DroneBuild b : new DroneBuild[] {DroneBuild.fiveInch(), DroneBuild.tinyWhoop()}) {
            String name = b.propDiameter > 3f ? "5\"" : "whoop";
            double weight = MassModel.flownMassKg(b) * Stepper.GRAVITY;
            double h2 = Hover.hoverThrottle(new ThrustV2(), b);
            double h1 = Hover.hoverThrottle(new ThrustV1(), b);
            assertEquals(h2, h1, 0.03, name + " v1 hover vs v2");
            // v1 has no thermal model: it matches v2's cold full-throttle thrust...
            ThrustV2 v2 = new ThrustV2();
            v2.prepare(b);
            double cold2 = 4.0 * v2.operatingPoint(1.0).thrust() / weight;
            double tw1 = thrustToWeight(new ThrustV1(), b);
            assertEquals(cold2, tw1, 0.02 * cold2, name + " v1 T/W vs v2 cold");
            // ...and stays within 12 % of v2 after 3 s at full throttle, when v2's windings have
            // warmed up (≈ 9 % thrust fade on the 5").
            double tw2 = thrustToWeight(new ThrustV2(), b);
            assertEquals(tw2, tw1, 0.12 * tw2, name + " v1 T/W vs v2 warm");
        }
    }

    @Test
    void hoverThrottleHoldsAltitudeInTheFullStep() {
        // The static hover throttle, fed through the stick mapping and the whole tick step, holds
        // altitude: spool up on the ground first, then fly 2 s in the air.
        for (DroneBuild build : new DroneBuild[] {DroneBuild.fiveInch(), DroneBuild.tinyWhoop()}) {
            for (boolean hiFi : new boolean[] {true, false}) {
                String name = (build.propDiameter > 3f ? "5\" " : "whoop ") + (hiFi ? "v2" : "v1");
                double hover = Hover.hoverThrottle(hiFi ? new ThrustV2() : new ThrustV1(), build);
                PhysicsConfig cfg = new PhysicsConfig(build, hiFi, false, false, 500);
                PhysicsEngine engine = new PhysicsEngine();
                DroneState s = new DroneState();
                engine.onArm(s, 0, 100, 0);
                StepInput in = new StepInput(new Quaternionf(), (float) (2.0 * hover - 1.0), PhysicsEngine.TICK_DT,
                        false);
                for (int i = 0; i < 20; i++) {
                    engine.stepTick(s, in, cfg, CollisionWorld.EMPTY);
                    s.position.set(0, 100, 0);
                    s.velocity.zero();
                }
                for (int i = 0; i < 40; i++) {
                    engine.stepTick(s, in, cfg, CollisionWorld.EMPTY);
                }
                assertTrue(Math.abs(s.velocity.y) < 1.0, name + " vy " + s.velocity.y);
                assertTrue(Math.abs(s.position.y - 100) < 1.0, name + " y " + s.position.y);
            }
        }
    }

    @Test
    void keyboardIdleFollowsTheSpec() {
        // spec §3.2: keyboard idle is 50 % throttle. As in the original, the 5" (hover ≈ 32 %)
        // climbs there and the whoop (hover ≈ 57 %) sinks.
        assertTrue(Hover.hoverThrottle(new ThrustV2(), DroneBuild.fiveInch()) < 0.5);
        assertTrue(Hover.hoverThrottle(new ThrustV2(), DroneBuild.tinyWhoop()) > 0.5);
    }
}
