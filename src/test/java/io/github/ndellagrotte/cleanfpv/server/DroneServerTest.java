package io.github.ndellagrotte.cleanfpv.server;

import io.github.ndellagrotte.cleanfpv.client.flight.ArmRules;
import net.minecraft.world.GameType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DroneServerTest {

    @Test
    void creativeRestoreKeepsTheFlyingStateFromArmTime() {
        assertFalse(DroneServer.flyingAfterRestore(GameType.CREATIVE, false), "walked when arming → falls");
        assertTrue(DroneServer.flyingAfterRestore(GameType.CREATIVE, true));
        assertTrue(DroneServer.flyingAfterRestore(GameType.SPECTATOR, false));
        assertFalse(DroneServer.flyingAfterRestore(GameType.SURVIVAL, true));
    }

    @Test
    void serverRestoreAgreesWithClientDisarmRule() {
        for (GameType mode : GameType.values()) {
            for (boolean flyingAtArm : new boolean[] {false, true}) {
                assertEquals(ArmRules.flyingAfterDisarm(mode == GameType.CREATIVE, mode == GameType.SPECTATOR, flyingAtArm),
                        DroneServer.flyingAfterRestore(mode, flyingAtArm), mode + " " + flyingAtArm);
            }
        }
    }
}
