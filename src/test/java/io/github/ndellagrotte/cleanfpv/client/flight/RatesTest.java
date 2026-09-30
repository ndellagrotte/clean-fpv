package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.common.config.RateTriple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RatesTest {

    private static final double EPS = 1e-3;

    @Test
    void radioDefaultsSpotValues() {
        RateTriple r = RateTriple.radio(); // 1.15 / 0.67 / 0
        assertEquals(0.0, Rates.rateDegPerSec(0.0, r), EPS);
        assertEquals(230.0 * 0.5 / (1.0 - 0.5 * 0.67), Rates.rateDegPerSec(0.5, r), EPS);
        assertEquals(230.0 / 0.33, Rates.rateDegPerSec(1.0, r), 0.05); // ≈ 697 °/s at full stick
        assertEquals(-Rates.rateDegPerSec(1.0, r), Rates.rateDegPerSec(-1.0, r), EPS);
        // near centre ≈ 230 °/s per unit stick
        assertEquals(230.0, Rates.rateDegPerSec(0.001, r) / 0.001, 0.2);
    }

    @Test
    void gamepadDefaultsSpotValues() {
        RateTriple r = RateTriple.gamepad(); // 1.1 / 0.3 / 0.5
        assertEquals(220.0 / 0.7, Rates.rateDegPerSec(1.0, r), EPS);
        double rc = 0.5 * 0.125 * 0.5 + 0.5 * 0.5; // expo on 0.5
        assertEquals(200.0 * 1.1 * rc / (1.0 - 0.5 * 0.3), Rates.rateDegPerSec(0.5, r), EPS);
        RateTriple yaw = RateTriple.gamepadYaw(); // 1.0 / 0.3 / 0.5
        assertEquals(200.0 / 0.7, Rates.rateDegPerSec(1.0, yaw), EPS);
    }

    @Test
    void stretchAboveTwoAndSuperClamp() {
        assertEquals(200.0 * (3.0 + 14.54), Rates.rateDegPerSec(1.0, new RateTriple(3f, 0f, 0f)), 0.01);
        assertEquals(200.0 * 2.0, Rates.rateDegPerSec(1.0, new RateTriple(2f, 0f, 0f)), EPS);
        // super 1 at full stick: divisor clamps at 0.01 → 100×
        assertEquals(200.0 * 1.0 * 100.0, Rates.rateDegPerSec(1.0, new RateTriple(1f, 1f, 0f)), 0.1);
    }

    @Test
    void clampsAndGuards() {
        RateTriple r = RateTriple.radio();
        assertEquals(Rates.rateDegPerSec(1.0, r), Rates.rateDegPerSec(3.0, r), EPS);
        assertEquals(0.0, Rates.rateDegPerSec(Double.NaN, r));
        assertEquals(0.0, Rates.rateDegPerSec(1.0, null));
        assertEquals(Math.toRadians(Rates.rateDegPerSec(0.7, r)), Rates.rateRadPerSec(0.7, r), 1e-12);
    }
}
