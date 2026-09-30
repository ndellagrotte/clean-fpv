package io.github.ndellagrotte.cleanfpv;

import com.cleanroommc.client.sdl.SDL;
import net.minecraftforge.client.event.InputEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ToolchainSmokeTest {

    @Test
    void loaderPrimitivesResolve() {
        assertNotNull(InputEvent.MouseTurnEvent.class);
        assertNotNull(SDL.class);
    }
}
