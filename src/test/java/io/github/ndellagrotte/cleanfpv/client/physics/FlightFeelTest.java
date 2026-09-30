package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic feel of both built-in presets through the full tick step, against the spec reference
 * (spec §5.2–§5.5 evaluated as written):
 *
 * <table>
 *   <caption>Reference</caption>
 *   <tr><th></th><th>climb (20 s full throttle)</th><th>level top speed</th><th>0→20 m/s at 60°</th></tr>
 *   <tr><td>5" 4S</td><td>47.4 m/s</td><td>51.1 m/s (at ≈ 77° pitch)</td><td>0.40 s</td></tr>
 *   <tr><td>Tiny Whoop</td><td>22.0 m/s</td><td>26.8 m/s (at ≈ 63° pitch)</td><td>0.90 s</td></tr>
 * </table>
 *
 * "Level top speed" is the horizontal speed at the pitch where full-throttle flight neither climbs
 * nor sinks. v1 models axial inflow but not crossflow or heating, so it only has to stay within
 * ≈ 15 % of v2 (it lands within ≈ 5 %).
 */
class FlightFeelTest {

    private static final int SETTLE_TICKS = 400; // 20 s

    private static final class Sim {
        final PhysicsEngine engine = new PhysicsEngine();
        final PhysicsConfig cfg;
        final double hover;
        DroneState s;

        Sim(DroneBuild build, boolean hiFi) {
            cfg = new PhysicsConfig(build, hiFi, false, false, 500);
            hover = Hover.hoverThrottle(hiFi ? new ThrustV2() : new ThrustV1(), build);
        }

        /** Spools up at hover throttle held still, level. */
        void startAtHover() {
            s = new DroneState();
            engine.onArm(s, 0, 1000, 0);
            s.velocity.zero();
            StepInput in = new StepInput(new Quaternionf(), (float) (2.0 * hover - 1.0), PhysicsEngine.TICK_DT, true);
            for (int i = 0; i < 40; i++) {
                engine.stepTick(s, in, cfg, CollisionWorld.EMPTY);
                s.position.set(0, 1000, 0);
                s.velocity.zero();
            }
        }

        StepInput fullThrottle(double pitchDeg) {
            return new StepInput(new Quaternionf().rotateX((float) Math.toRadians(pitchDeg)), 1f,
                    PhysicsEngine.TICK_DT, true);
        }

        void fly(double pitchDeg, int ticks) {
            StepInput in = fullThrottle(pitchDeg);
            for (int i = 0; i < ticks; i++) {
                engine.stepTick(s, in, cfg, CollisionWorld.EMPTY);
            }
        }

        double climb() {
            startAtHover();
            fly(0, SETTLE_TICKS);
            return s.velocity.y;
        }

        double horizontal() {
            return Math.hypot(s.velocity.x, s.velocity.z);
        }

        /** Horizontal speed at the pitch where full-throttle vertical speed settles at zero. */
        double levelTopSpeed() {
            double lo = 0;
            double hi = 89;
            double speed = 0;
            for (int i = 0; i < 12; i++) {
                double mid = 0.5 * (lo + hi);
                startAtHover();
                fly(mid, SETTLE_TICKS);
                speed = horizontal();
                if (s.velocity.y > 0) {
                    lo = mid;
                } else {
                    hi = mid;
                }
            }
            return speed;
        }

        /** Seconds from hover to 20 m/s at 60° pitch, full throttle (NaN if never within 20 s). */
        double timeTo20() {
            startAtHover();
            StepInput in = fullThrottle(60);
            for (int i = 1; i <= SETTLE_TICKS; i++) {
                engine.stepTick(s, in, cfg, CollisionWorld.EMPTY);
                if (s.velocity.length() >= 20.0) {
                    return i * PhysicsEngine.TICK_DT;
                }
            }
            return Double.NaN;
        }
    }

    private static void inRange(double value, double lo, double hi, String what) {
        assertTrue(value >= lo && value <= hi, what + " " + value + " not in [" + lo + ", " + hi + "]");
    }

    @Test
    void fiveInchV2MatchesTheReference() {
        Sim sim = new Sim(DroneBuild.fiveInch(), true);
        inRange(sim.climb(), 43, 52, "5\" climb");
        inRange(sim.levelTopSpeed(), 48, 54, "5\" level top speed");
        inRange(sim.timeTo20(), 0.3, 0.5, "5\" time to 20 m/s");
    }

    @Test
    void tinyWhoopV2MatchesTheReference() {
        Sim sim = new Sim(DroneBuild.tinyWhoop(), true);
        inRange(sim.climb(), 20, 24, "whoop climb");
        inRange(sim.levelTopSpeed(), 24, 29, "whoop level top speed");
        inRange(sim.timeTo20(), 0.75, 1.1, "whoop time to 20 m/s");
    }

    @Test
    void fiveInchIsMuchFasterThanTheWhoop() {
        Sim five = new Sim(DroneBuild.fiveInch(), true);
        Sim whoop = new Sim(DroneBuild.tinyWhoop(), true);
        assertTrue(five.levelTopSpeed() >= 1.6 * whoop.levelTopSpeed(), "top speed");
        assertTrue(five.timeTo20() <= 0.6 * whoop.timeTo20(), "acceleration");
    }

    @Test
    void v1StaysCloseToV2InFlight() {
        for (DroneBuild b : new DroneBuild[] {DroneBuild.fiveInch(), DroneBuild.tinyWhoop()}) {
            String name = b.propDiameter > 3f ? "5\"" : "whoop";
            Sim v2 = new Sim(b, true);
            Sim v1 = new Sim(b, false);
            double c2 = v2.climb();
            assertEquals(c2, v1.climb(), 0.15 * c2, name + " v1 climb vs v2");
            double l2 = v2.levelTopSpeed();
            assertEquals(l2, v1.levelTopSpeed(), 0.15 * l2, name + " v1 level speed vs v2");
            double t2 = v2.timeTo20();
            assertEquals(t2, v1.timeTo20(), 0.25 * t2, name + " v1 time to 20 m/s vs v2");
        }
    }
}
