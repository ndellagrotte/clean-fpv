package io.github.ndellagrotte.cleanfpv.client.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MotorToneTest {

    @Test
    void mapsRatioToPitchAndVolume() {
        assertEquals(0f, MotorTone.ratio(0, 1000));
        assertEquals(0.5f, MotorTone.ratio(-500, 1000), 1e-6f);
        assertEquals(1f, MotorTone.ratio(2000, 1000));
        assertEquals(0.5f, MotorTone.pitch(0f));
        assertEquals(2.0f, MotorTone.pitch(1f));
        assertEquals(1.25f, MotorTone.pitch(0.5f), 1e-6f);
        assertEquals(0.25f, MotorTone.volume(0f));
        assertEquals(1.0f, MotorTone.volume(1f));
    }

    @Test
    void degenerateInputsAreSilentButAudible() {
        assertEquals(0f, MotorTone.ratio(100, 0));
        assertEquals(0f, MotorTone.ratio(Double.NaN, 100));
        assertEquals(0f, MotorTone.ratio(100, Double.POSITIVE_INFINITY));
        assertEquals(MotorTone.MIN_VOLUME, MotorTone.volume(MotorTone.ratio(100, 0)));
    }
}
