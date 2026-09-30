package io.github.ndellagrotte.cleanfpv.client.race;

import io.github.ndellagrotte.cleanfpv.common.net.ClientRacePacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateProgressS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.JoinRaceS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapFinishedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapStartedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.RaceModeS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TrackS2C;
import net.minecraft.client.Minecraft;

/**
 * Client-side race packet behaviour (spec §9): applies each packet to {@link RaceClientState}.
 * The lap clock uses the local receive time ({@link Minecraft#getSystemTime()}), as the spec does;
 * the server's timestamp is informational. The final lap times come from the server.
 *
 * <p>Owner: (G) race. Installed by {@code ClientProxy.init} as the {@link ClientRacePacketHandler}.
 * All methods run on the client thread. The state is cleared by {@link RaceClientEvents} on
 * disconnect and world unload.
 */
public final class RaceClientNetHandler implements ClientRacePacketHandler {

    private final RaceClientState state = RaceClientState.get();

    @Override
    public void onLapStarted(LapStartedS2C message) {
        state.lapStarted(message.playerId(), Minecraft.getSystemTime());
    }

    @Override
    public void onLapFinished(LapFinishedS2C message) {
        state.lapFinished(message.playerId(), message.trackId(), message.lapMs());
    }

    @Override
    public void onGateProgress(GateProgressS2C message) {
        state.gateProgress(message.playerId(), message.gateIndex());
    }

    @Override
    public void onTrack(TrackS2C message) {
        state.putTrack(message.track());
    }

    @Override
    public void onGate(GateS2C message) {
        state.addLiveGate(message.gate());
    }

    @Override
    public void onJoinRace(JoinRaceS2C message) {
        state.joinRace(message.playerId(), message.trackId(), message.joined());
    }

    @Override
    public void onRaceMode(RaceModeS2C message) {
        state.setRaceMode(message.enabled());
    }
}
