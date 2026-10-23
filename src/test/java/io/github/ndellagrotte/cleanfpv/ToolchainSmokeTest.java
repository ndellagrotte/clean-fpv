/*
 * Copyright (c) 2026 ndellagrotte
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package io.github.ndellagrotte.cleanfpv;

import com.cleanroommc.client.input.mouse.MouseTurnEvent;
import com.cleanroommc.client.sdl.SDL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ToolchainSmokeTest {

    @Test
    void loaderPrimitivesResolve() {
        assertNotNull(MouseTurnEvent.class);
        assertNotNull(SDL.class);
    }
}
