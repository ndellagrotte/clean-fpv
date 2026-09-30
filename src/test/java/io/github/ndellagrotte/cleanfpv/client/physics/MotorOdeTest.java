package io.github.ndellagrotte.cleanfpv.client.physics;

import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotorOdeTest {

    private static final MotorParams P = MotorParams.of(DroneBuild.fiveInch());

    @Test
    void noLoadClampHolds() {
        double w = 0.0;
        for (int i = 0; i < 1000; i++) {
            w = MotorOde.step(w, 1000.0, 0.01, P, P.coldOhms(), null);
            assertTrue(Math.abs(w) <= P.noLoadOmega());
        }
        assertEquals(P.noLoadOmega(), w, 1e-9);
        w = MotorOde.step(0.0, -1000.0, 1.0, P, P.coldOhms(), null);
        assertEquals(-P.noLoadOmega(), w, 1e-9);
        // starting above the clamp is pulled inside immediately
        assertEquals(P.noLoadOmega(), MotorOde.step(1e9, 0.0, 1e-6, P, P.coldOhms(), null), 1e-9);
    }

    @Test
    void unloadedMotorApproachesKvTimesVolts() {
        double volts = 8.0;
        double w = 0.0;
        for (int i = 0; i < 400; i++) {
            w = MotorOde.step(w, volts, 1.0 / 128.0, P, P.coldOhms(), MotorOde.LoadTorque.NONE);
        }
        assertEquals(volts * P.kvRad(), w, 1e-3 * volts * P.kvRad());
    }

    @Test
    void rk4MatchesAnalyticSpoolUp() {
        // Unloaded: dω/dt = (V·kv − ω)/τ with τ = J·R·kv²  →  ω(t) = V·kv·(1 − e^(−t/τ)).
        double volts = 10.0;
        double tau = P.spoolTimeConstant(P.coldOhms());
        double w = 0.0;
        double t = 0.0;
        for (int i = 0; i < 12; i++) {
            w = MotorOde.step(w, volts, 1.0 / 128.0, P, P.coldOhms(), null);
            t += 1.0 / 128.0;
        }
        double exact = volts * P.kvRad() * (1.0 - Math.exp(-t / tau));
        assertEquals(exact, w, 1e-5 * exact);
    }

    @Test
    void neverProducesNaN() {
        double[] omegas = {0.0, Double.NaN, Double.POSITIVE_INFINITY, -1e12, 1e-300};
        double[] volts = {0.0, Double.NaN, 1e9, -1e9, Double.NEGATIVE_INFINITY};
        double[] dts = {0.0, -1.0, 1e-9, 1.0 / 128.0, 5.0, Double.NaN};
        for (double w : omegas) {
            for (double v : volts) {
                for (double dt : dts) {
                    double r = MotorOde.step(w, v, dt, P, P.coldOhms(), omega -> -1e-3 * omega);
                    assertTrue(Double.isFinite(r) && Math.abs(r) <= P.noLoadOmega(), w + " " + v + " " + dt);
                }
            }
        }
        // a load that returns NaN
        assertTrue(Double.isFinite(MotorOde.step(100, 10, 0.01, P, P.coldOhms(), omega -> Double.NaN)));
    }

    @Test
    void extremeBuildsStayFiniteInBothModels() {
        DroneBuild tiny = new DroneBuild();
        tiny.motorKv = 20000f;
        tiny.motorWidth = 5f;
        tiny.motorHeight = 2f;
        tiny.batteryCells = 12;
        tiny.propDiameter = 13f;
        tiny.propPitch = 20f;
        tiny.blades = 16;
        tiny.bladeWidth = 5f;
        DroneBuild zeroKv = new DroneBuild();
        zeroKv.motorKv = 0f;
        zeroKv.propPitch = 0f;
        for (DroneBuild b : new DroneBuild[] {tiny, zeroKv, DroneBuild.tinyWhoop(), DroneBuild.fiveInch()}) {
            for (ThrustModel m : new ThrustModel[] {new ThrustV1(), new ThrustV2()}) {
                m.prepare(b);
                DroneState s = new DroneState();
                s.velocity.set(300, -200, 100);
                Quaternionf q = new Quaternionf().rotationYXZ(1f, 2f, 3f);
                for (double thr : new double[] {1.0, -1.0, 0.0, 0.5}) {
                    for (int i = 0; i < 256; i++) {
                        Vector3d f = new Vector3d();
                        double[] torque = new double[4];
                        m.step(s, q, thr, 1.0 / 128.0, f, torque);
                        assertTrue(f.isFinite(), "force " + f);
                        for (int k = 0; k < 4; k++) {
                            assertTrue(Double.isFinite(s.omega[k]) && Math.abs(s.omega[k]) <= m.noLoadOmega() + 1e-9);
                            assertTrue(Double.isFinite(s.temperature[k]) && Double.isFinite(torque[k]));
                        }
                    }
                }
            }
        }
    }

    @Test
    void motorsSpinInAlternatingDirections() {
        ThrustV2 m = new ThrustV2();
        m.prepare(DroneBuild.fiveInch());
        DroneState s = new DroneState();
        for (int i = 0; i < 64; i++) {
            m.step(s, new Quaternionf(), 0.5, 1.0 / 128.0, new Vector3d(), null);
        }
        assertTrue(s.omega[0] < 0 && s.omega[2] < 0);
        assertTrue(s.omega[1] > 0 && s.omega[3] > 0);
        assertEquals(-s.omega[0], s.omega[1], 1e-6 * s.omega[1]);
    }

    @Test
    void reverseSpinPushesDown() {
        // 3D mode: negative throttle reverses the motors and the thrust.
        ThrustV2 m = new ThrustV2();
        m.prepare(DroneBuild.fiveInch());
        DroneState s = new DroneState();
        Vector3d f = new Vector3d();
        for (int i = 0; i < 256; i++) {
            f.zero();
            m.step(s, new Quaternionf(), -0.5, 1.0 / 128.0, f, null);
        }
        assertTrue(f.y < -1.0, "thrust " + f);
        ThrustV1 v1 = new ThrustV1();
        v1.prepare(DroneBuild.fiveInch());
        DroneState s1 = new DroneState();
        for (int i = 0; i < 256; i++) {
            f.zero();
            v1.step(s1, new Quaternionf(), -0.5, 1.0 / 128.0, f, null);
        }
        assertTrue(f.y < -1.0, "v1 thrust " + f);
    }

    @Test
    void overheatComesFromLiveTemperatures() {
        assertTrue(!Thermal.overheating(new double[] {293.15, 293.15, 293.15, 293.15}));
        double hot = DroneState.AMBIENT_K + 300.0; // R ratio 1 + 0.00393·300 = 2.18 > 2
        assertTrue(Thermal.overheating(new double[] {hot, hot, hot, hot}));
        assertEquals(DroneState.AMBIENT_K + 10.0, Thermal.temperature(10.0 * P.heatCapacity(), P.heatCapacity()), 1e-9);
        // constant power converges to P·τ joules
        double q = 0.0;
        for (int i = 0; i < 10_000; i++) {
            q = Thermal.stepHeat(q, 5.0, P.coolingTau(), P.heatCapacity(), 0.01);
        }
        assertEquals(5.0 * P.coolingTau(), q, 1e-6);
        assertEquals(0.0, Thermal.stepHeat(Double.NaN, Double.NaN, P.coolingTau(), P.heatCapacity(), 0.01), 0.0);
    }
}
