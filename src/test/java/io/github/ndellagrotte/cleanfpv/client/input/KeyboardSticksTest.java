package io.github.ndellagrotte.cleanfpv.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyboardSticksTest {

    private static final StickState IDLE = StickState.KEYBOARD_IDLE;

    private static StickState keys(StickState base, String held) {
        return KeyboardSticks.apply(base, held.contains("A"), held.contains("D"), held.contains("W"),
                held.contains("S"), held.contains(" "));
    }

    @Test
    void noKeysReturnsBaseUnchanged() {
        assertSame(IDLE, keys(IDLE, ""));
        assertEquals(0f, IDLE.thr());
        assertFalse(IDLE.throttleLow(), "keyboard idle is 50 % throttle");
    }

    @Test
    void yawKeys() {
        assertEquals(-0.5f, keys(IDLE, "A").yaw());
        assertEquals(0.5f, keys(IDLE, "D").yaw());
        assertEquals(0f, keys(IDLE, "AD").yaw());
        assertEquals(0f, keys(IDLE, "A").thr(), "yaw keys leave throttle alone");
    }

    @Test
    void throttleKeys() {
        assertEquals(1f, keys(IDLE, " ").thr());
        assertEquals(0.5f, keys(IDLE, "W").thr());
        assertEquals(1f, keys(IDLE, "W ").thr(), "Space wins over W");
        assertEquals(-1f, keys(IDLE, "S").thr());
        assertTrue(keys(IDLE, "S").throttleLow());
        assertEquals(-0.5f, keys(IDLE, "WS").thr());
        assertEquals(0f, keys(IDLE, " S").thr());
    }

    @Test
    void combinesWithJoystickAndClamps() {
        StickState stick = new StickState(0.3f, 0.2f, -0.4f, 0.8f, 0.1f, true, true, false);
        StickState s = keys(stick, "DS");
        assertEquals(1f, s.yaw(), 1e-6f);
        assertEquals(-0.7f, s.thr(), 1e-6f);
        assertEquals(0.2f, s.roll());
        assertEquals(-0.4f, s.pitch());
        assertEquals(0.1f, s.angle());
        assertTrue(s.arm());
        assertTrue(s.angleSw());
        assertFalse(s.rclick());
        assertEquals(-1f, keys(new StickState(-0.5f, 0, 0, 0, 0, false, false, false), "S").thr(), "clamped");
    }
}
