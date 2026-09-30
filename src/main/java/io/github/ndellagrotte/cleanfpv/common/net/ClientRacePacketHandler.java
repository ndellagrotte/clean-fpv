package io.github.ndellagrotte.cleanfpv.common.net;

import io.github.ndellagrotte.cleanfpv.common.net.packet.GateProgressS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.JoinRaceS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapFinishedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapStartedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.RaceModeS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TrackS2C;

/**
 * Client-side behaviour for the race packets (spec §8.1 #1-5, 8, 9; all S2C). Implemented by
 * {@code client.race.RaceClientNetHandler}, installed by {@code ClientProxy.init}. Always called
 * on the client thread.
 */
public interface ClientRacePacketHandler {

    void onLapStarted(LapStartedS2C message);

    void onLapFinished(LapFinishedS2C message);

    void onGateProgress(GateProgressS2C message);

    void onTrack(TrackS2C message);

    void onGate(GateS2C message);

    void onJoinRace(JoinRaceS2C message);

    void onRaceMode(RaceModeS2C message);
}
