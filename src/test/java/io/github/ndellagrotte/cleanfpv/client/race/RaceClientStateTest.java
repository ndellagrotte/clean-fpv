package io.github.ndellagrotte.cleanfpv.client.race;

import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceClientStateTest {

    private static final UUID ME = new UUID(1, 1);
    private static final UUID OTHER = new UUID(2, 2);
    private static final GateDef GATE = new GateDef(0, new BlockPos(0, 64, 0), new BlockPos(2, 66, 0),
            new int[] {0, 0, 0}, new int[] {2, 2, 2}, new Vec3i(1, 0, 0), new Vec3i(0, 1, 0));
    private static final TrackDef TRACK = new TrackDef(new UUID(7, 7), "loop", List.of(GATE, GATE));

    /** The server's join sequence: track, leaderboard replay, join. */
    private static RaceClientState joined() {
        RaceClientState s = new RaceClientState();
        s.putTrack(TRACK);
        s.lapFinished(OTHER, TRACK.id, 40_000);
        s.lapFinished(ME, TRACK.id, 45_000);
        s.joinRace(ME, TRACK.id, true);
        return s;
    }

    @Test
    void joinSequenceKeepsTheBoardAndResetsOwnProgress() {
        RaceClientState s = joined();
        assertSame(TRACK, s.joinedTrack(ME));
        RaceClientState.Progress p = s.progress(ME);
        assertFalse(p.lapRunning());
        assertEquals(-1, p.lastLapMs());
        assertEquals(0, p.nextGate());
        assertEquals(1, s.board(TRACK.id).rank(OTHER));
        assertEquals(45_000, s.board(TRACK.id).bestMs(ME));
    }

    @Test
    void lapPacketsDriveProgressAndKeepTheBestLap() {
        RaceClientState s = joined();
        s.lapStarted(ME, 1_000);
        s.gateProgress(ME, 1);
        assertTrue(s.progress(ME).lapRunning());
        assertEquals(1_000, s.progress(ME).lapStartMs());
        assertEquals(1, s.progress(ME).nextGate());

        s.lapFinished(ME, TRACK.id, 50_000);
        assertEquals(50_000, s.progress(ME).lastLapMs());
        assertEquals(45_000, s.board(TRACK.id).bestMs(ME));
        s.lapFinished(ME, TRACK.id, 39_000);
        assertEquals(1, s.board(TRACK.id).rank(ME));
    }

    @Test
    void trackRedefinitionClearsBoardAndProgress() {
        RaceClientState s = joined();
        s.lapStarted(ME, 1_000);
        s.gateProgress(ME, 1);
        s.putTrack(new TrackDef(TRACK.id, "loop", List.of(GATE)));
        assertTrue(s.board(TRACK.id).isEmpty());
        assertFalse(s.progress(ME).lapRunning());
        assertEquals(0, s.progress(ME).nextGate());
        assertEquals(1, s.joinedTrack(ME).gates.size());
    }

    @Test
    void leavingOnlyAffectsThatTrack() {
        RaceClientState s = joined();
        s.joinRace(ME, new UUID(9, 9), false);
        assertTrue(s.progress(ME) != null);
        s.joinRace(ME, TRACK.id, false);
        assertNull(s.progress(ME));
        assertNull(s.joinedTrack(ME));
    }

    @Test
    void raceModeGatesLiveOnlyWhileOnAndToggleClears() {
        RaceClientState s = new RaceClientState();
        s.addLiveGate(GATE);
        assertTrue(s.liveGates().isEmpty());
        s.setRaceMode(true);
        s.addLiveGate(GATE);
        assertEquals(1, s.liveGates().size());
        s.setRaceMode(true);
        assertTrue(s.liveGates().isEmpty());
        s.addLiveGate(GATE);
        s.setRaceMode(false);
        assertFalse(s.isRaceMode());
        assertTrue(s.liveGates().isEmpty());
    }

    @Test
    void clearForgetsEverything() {
        RaceClientState s = joined();
        s.setRaceMode(true);
        s.clear();
        assertFalse(s.isRaceMode());
        assertNull(s.track(TRACK.id));
        assertNull(s.progress(ME));
        assertTrue(s.board(TRACK.id).isEmpty());
    }
}
