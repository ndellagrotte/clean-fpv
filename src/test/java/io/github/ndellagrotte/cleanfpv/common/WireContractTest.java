package io.github.ndellagrotte.cleanfpv.common;

import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateProgressS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.HelloS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.JoinRaceS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapFinishedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapStartedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.RaceModeS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TrackS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformS2C;
import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WireContractTest {

    @Test
    void droneBuildRoundTripsAllWireFields() {
        DroneBuild b = DroneBuild.tinyWhoop();
        b.red = 0.25f;
        b.cameraAngle = 45f;
        ByteBuf buf = Unpooled.buffer();
        b.write(buf);
        assertEquals(9 * 4 + 3 * 4 + 5 * 4 + 3 + 2 * 4, buf.readableBytes());
        DroneBuild r = DroneBuild.read(buf);
        assertEquals(b, r);
        assertEquals(0, buf.readableBytes());
    }

    @Test
    void transformRoundTrips() {
        UUID id = UUID.randomUUID();
        TransformSnapshot t = new TransformSnapshot(0.1f, 0.2f, 0.3f, 0.9f, 1f, -2f, 3f,
                new float[] {10f, -20f, 30f, -40f}, 123456789L);
        TransformS2C msg = new TransformS2C(id, t);
        ByteBuf buf = Unpooled.buffer();
        msg.toBytes(buf);
        TransformS2C back = new TransformS2C();
        back.fromBytes(buf);
        assertEquals(id, back.playerId());
        assertEquals(-40f, back.transform().omega(3));
        assertEquals(123456789L, back.transform().epochMs);
        assertTrue(back.transform().isFinite());
    }

    @Test
    void trackRoundTrips() {
        GateDef g = new GateDef(0, new BlockPos(1, 2, 3), new BlockPos(4, 5, 6), new int[] {0, 1},
                new int[] {3, 4}, new Vec3i(1, 0, 0), new Vec3i(0, 1, 0));
        TrackDef t = new TrackDef(UUID.randomUUID(), "Loop", List.of(g, g));
        ByteBuf buf = Unpooled.buffer();
        new TrackS2C(t).toBytes(buf);
        TrackS2C back = new TrackS2C();
        back.fromBytes(buf);
        assertEquals("Loop", back.track().name);
        assertEquals(g, back.track().gates.get(1));
    }

    @Test
    void discriminatorsAreUniqueAndDense() {
        int[] ids = {HelloS2C.DISCRIMINATOR, LapStartedS2C.DISCRIMINATOR, LapFinishedS2C.DISCRIMINATOR,
                GateProgressS2C.DISCRIMINATOR, TrackS2C.DISCRIMINATOR, GateS2C.DISCRIMINATOR,
                BuildC2S.DISCRIMINATOR, ArmC2S.DISCRIMINATOR, JoinRaceS2C.DISCRIMINATOR,
                RaceModeS2C.DISCRIMINATOR, TransformC2S.DISCRIMINATOR, BuildS2C.DISCRIMINATOR,
                ArmS2C.DISCRIMINATOR, TransformS2C.DISCRIMINATOR};
        Set<Integer> seen = new HashSet<>();
        for (int id : ids) {
            assertTrue(seen.add(id), "duplicate discriminator " + id);
            assertTrue(id >= 0 && id < ids.length);
        }
    }

    @Test
    void channelMapCalibrationAndVirtualButtons() {
        ChannelMap m = ChannelMap.radioDefaults();
        m.calMin[0] = 0f;
        m.calMax[0] = 0.5f;
        assertEquals(1f, m.calibrate(0, 0.5f), 1e-6);
        assertEquals(-1f, m.calibrate(0, 0f), 1e-6);
        m.calMin[1] = 0.3f;
        m.calMax[1] = 0.3f;
        assertEquals(0.5f, m.calibrate(1, 0.5f), 1e-6);
        float[] axes = {0f, 0f, 0f, 0f, 0f, 0.8f};
        boolean[] buttons = {false, true};
        m.armSwitch = ChannelMap.virtualIndex(5, axes.length);
        assertTrue(m.readSwitch(ChannelMap.Switch.ARM, axes, buttons));
        assertTrue(m.readSwitch(ChannelMap.Switch.ANGLE, axes, buttons));
        assertFalse(m.readSwitch(ChannelMap.Switch.RIGHT_CLICK, axes, buttons));
        m.invertRightClick = true;
        assertTrue(m.readSwitch(ChannelMap.Switch.RIGHT_CLICK, axes, buttons));
    }
}
