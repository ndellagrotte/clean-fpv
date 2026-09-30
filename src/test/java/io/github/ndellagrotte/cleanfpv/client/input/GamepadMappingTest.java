package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GamepadMappingTest {

    // Shape of an SDL mapping for an XInput-style pad on Linux (6 axes, 11 buttons, 1 hat).
    private static final String PAD = "030000005e0400008e02000014010000,Test Pad,a:b0,b:b1,x:b2,y:b3,"
            + "back:b6,guide:b8,start:b7,leftstick:b9,rightstick:b10,leftshoulder:b4,rightshoulder:b5,"
            + "dpup:h0.1,dpdown:h0.4,dpleft:h0.8,dpright:h0.2,leftx:a0,lefty:a1,rightx:a3,righty:a4,"
            + "lefttrigger:a2,righttrigger:a5,platform:Linux,";

    @Test
    void mapsModeTwoLayout() {
        ChannelMap m = GamepadMapping.channelMap(PAD, 6, 11);
        assertEquals(1, m.throttleAxis);
        assertEquals(0, m.yawAxis);
        assertEquals(3, m.rollAxis);
        assertEquals(4, m.pitchAxis);
        assertEquals(2, m.angleAxis);
        assertTrue(m.invertThrottle);
        assertTrue(m.invertPitch);
        assertFalse(m.invertRoll);
        assertFalse(m.invertYaw);
        assertEquals(4, m.armSwitch);
        assertEquals(2, m.angleSwitch);
        assertEquals(5, m.rightClickSwitch);
    }

    @Test
    void invertedSourceFlipsFlagAndTriggerBecomesVirtualButton() {
        String mapping = "guid,Pad,lefty:a1~,leftx:a0,rightx:a2,righty:a3,leftshoulder:+a5,x:h0.8,rightshoulder:b9";
        ChannelMap m = GamepadMapping.channelMap(mapping, 6, 10);
        assertFalse(m.invertThrottle, "lefty~ cancels the base inversion");
        assertEquals(ChannelMap.virtualIndex(5, 6), m.armSwitch);
        assertTrue(ChannelMap.isVirtual(m.armSwitch));
        assertEquals(10 + 3, m.angleSwitch, "hat 0 left = fourth hat button after the 10 buttons");
        assertEquals(9, m.rightClickSwitch);

        float[] axes = {0, 0, 0, 0, 0, -1f};
        boolean[] buttons = new boolean[14];
        assertFalse(m.readSwitch(ChannelMap.Switch.ARM, axes, buttons), "trigger at rest");
        axes[5] = 1f;
        assertTrue(m.readSwitch(ChannelMap.Switch.ARM, axes, buttons), "trigger pulled");
    }

    @Test
    void missingOrGarbageFallsBackToDefaults() {
        ChannelMap def = ChannelMap.gamepadDefaults(6);
        for (String bad : new String[] {null, "", "nonsense", "g,n,leftx:zz,lefty:a99,rightx:"}) {
            ChannelMap m = GamepadMapping.channelMap(bad, 6, 11);
            assertEquals(def.throttleAxis, m.throttleAxis);
            assertEquals(def.yawAxis, m.yawAxis);
            assertEquals(def.rollAxis, m.rollAxis);
            assertEquals(def.armSwitch, m.armSwitch);
        }
    }

    @Test
    void joystickChannelsFeedStickState() {
        ChannelMap m = ChannelMap.radioDefaults();
        m.invertRoll = true;
        float[] axes = {-1f, 0.5f, 0f, 0.25f, 1f};
        boolean[] buttons = {true, false, true};
        StickState s = InputManager.readJoystick(m, axes, buttons);
        assertEquals(-1f, s.thr());
        assertTrue(s.throttleLow());
        assertEquals(-0.5f, s.roll());
        assertEquals(0.25f, s.yaw());
        assertEquals(1f, s.angle());
        assertTrue(s.arm());
        assertFalse(s.angleSw());
        assertTrue(s.rclick());
    }
}
