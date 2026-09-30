package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MouseInputTest {

    @Test
    void eventUnitIsPointFifteenDegrees() {
        // 100 event units = 15 degrees, exactly what Entity.turn would have applied.
        assertEquals(Math.toRadians(15.0), MouseInput.rollRad(100f, 1f), 1e-6);
        assertEquals(Math.toRadians(15.0), MouseInput.pitchRad(100f, 1f), 1e-6);
    }

    @Test
    void signsArePreservedAndGainScales() {
        assertEquals(-Math.toRadians(1.5), MouseInput.rollRad(-10f, 1f), 1e-7);
        assertEquals(2 * MouseInput.pitchRad(7f, 1f), MouseInput.pitchRad(7f, 2f), 1e-7);
        assertEquals(0f, MouseInput.rollRad(0f, 3f));
    }

    @Test
    void badInputsAreSanitised() {
        assertEquals(MouseInput.rollRad(10f, 1f), MouseInput.rollRad(10f, Float.NaN), 1e-9);
        assertEquals(0f, MouseInput.pitchRad(Float.POSITIVE_INFINITY, 1f));
        assertEquals(DroneModelConfig.MOUSE_GAIN_MAX, MouseInput.sanitizeGain(1000f));
        assertEquals(DroneModelConfig.MOUSE_GAIN_MIN, MouseInput.sanitizeGain(-1f));
    }

    @Test
    void overlayStickIsMouseDisplacementOverFullRateDisplacement() {
        // 10 rad/s full rate, 20 ms frame → 0.2 rad is full deflection.
        assertEquals(0.5f, MouseInput.overlayStick(0.1, 10.0, 0.02), 1e-6);
        assertEquals(-1f, MouseInput.overlayStick(-5.0, 10.0, 0.02), 0f);
        assertEquals(0f, MouseInput.overlayStick(0.1, 10.0, 0.0), 0f);
        assertEquals(0f, MouseInput.overlayStick(Double.NaN, 10.0, 0.02), 0f);
        assertEquals(0f, MouseInput.overlayStick(0.1, 0.0, 0.02), 0f);
    }
}
