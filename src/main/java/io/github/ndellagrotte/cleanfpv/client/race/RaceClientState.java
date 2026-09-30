package io.github.ndellagrotte.cleanfpv.client.race;

import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.Leaderboard;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client race store (spec §9): race-mode flag and live gate list, track definitions, per-track
 * leaderboards (best lap per pilot, fed by lap-finished packets) and per-pilot progress. One
 * instance per client (not static maps), cleared on disconnect and world unload; the server
 * re-sends what is needed. Client thread only; no Minecraft types, so it is unit-testable.
 *
 * <p>Packet semantics (see {@code RaceServer}): a track packet (re)defines a track and clears its
 * leaderboard and every pilot's progress on it; a join resets that pilot's progress; the lap clock
 * is the local receive time of the lap-started packet (spec: the server stamp is informational).
 */
public final class RaceClientState {

    /** Cap on the live gate list (race mode) against a misbehaving server. */
    public static final int MAX_LIVE_GATES = 1024;
    /** Cap on stored track definitions. */
    public static final int MAX_TRACKS = 256;

    private static final RaceClientState INSTANCE = new RaceClientState();

    /** Use {@link #get()}; tests create their own instances. */
    RaceClientState() {}

    /** One pilot's race progress as far as this client knows. */
    public static final class Progress {
        private final UUID trackId;
        private int nextGate;
        private long lapStartMs = -1L;
        private int lastLapMs = -1;

        Progress(UUID trackId) {
            this.trackId = trackId;
        }

        public UUID trackId() {
            return trackId;
        }

        /** Index of the next gate to pass (0 = start/finish). */
        public int nextGate() {
            return nextGate;
        }

        /** Whether a lap is being timed. */
        public boolean lapRunning() {
            return lapStartMs >= 0L;
        }

        /** Local clock (ms) at lap start, or −1. */
        public long lapStartMs() {
            return lapStartMs;
        }

        /** Last completed lap (ms), or −1. */
        public int lastLapMs() {
            return lastLapMs;
        }

        void clear() {
            nextGate = 0;
            lapStartMs = -1L;
            lastLapMs = -1;
        }
    }

    private boolean raceMode;
    private final List<GateDef> liveGates = new ArrayList<>();
    private final Map<UUID, TrackDef> tracks = new HashMap<>();
    private final Map<UUID, Leaderboard> boards = new HashMap<>();
    private final Map<UUID, Progress> pilots = new HashMap<>();

    /** The client's store. Tests create their own instances. */
    public static RaceClientState get() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------------------------------------
    // Packet effects

    /** Race mode on/off; both clear the live gate list (spec §8.1 #9). */
    public void setRaceMode(boolean enabled) {
        raceMode = enabled;
        liveGates.clear();
    }

    /** A gate for the live list; ignored while race mode is off (spec §8.1 #5). */
    public void addLiveGate(GateDef gate) {
        if (raceMode && gate != null && liveGates.size() < MAX_LIVE_GATES) {
            liveGates.add(gate);
        }
    }

    public void putTrack(TrackDef track) {
        if (track == null || (!tracks.containsKey(track.id) && tracks.size() >= MAX_TRACKS)) {
            return;
        }
        tracks.put(track.id, track);
        boards.remove(track.id);
        for (Progress p : pilots.values()) {
            if (p.trackId.equals(track.id)) {
                p.clear();
            }
        }
    }

    public void joinRace(UUID playerId, UUID trackId, boolean joined) {
        if (playerId == null || trackId == null) {
            return;
        }
        if (joined) {
            pilots.put(playerId, new Progress(trackId));
        } else {
            Progress p = pilots.get(playerId);
            if (p != null && p.trackId.equals(trackId)) {
                pilots.remove(playerId);
            }
        }
    }

    /** A lap started now ({@code nowMs}, local clock) on the pilot's current track. */
    public void lapStarted(UUID playerId, long nowMs) {
        Progress p = pilots.get(playerId);
        if (p != null) {
            p.lapStartMs = nowMs;
        }
    }

    public void gateProgress(UUID playerId, int nextGate) {
        Progress p = pilots.get(playerId);
        if (p != null && nextGate >= 0) {
            p.nextGate = nextGate;
        }
    }

    /** A finished lap: leaderboard entry (best is kept) and the pilot's last lap. */
    public void lapFinished(UUID playerId, UUID trackId, int lapMs) {
        if (playerId == null || trackId == null || lapMs <= 0) {
            return;
        }
        boards.computeIfAbsent(trackId, id -> new Leaderboard()).submit(playerId, lapMs);
        Progress p = pilots.get(playerId);
        if (p != null && p.trackId.equals(trackId)) {
            p.lastLapMs = lapMs;
        }
    }

    /** Disconnect / world unload. */
    public void clear() {
        raceMode = false;
        liveGates.clear();
        tracks.clear();
        boards.clear();
        pilots.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Queries

    public boolean isRaceMode() {
        return raceMode;
    }

    public List<GateDef> liveGates() {
        return Collections.unmodifiableList(liveGates);
    }

    public TrackDef track(UUID trackId) {
        return trackId == null ? null : tracks.get(trackId);
    }

    /** Leaderboard of a track (empty if nothing is known). */
    public Leaderboard board(UUID trackId) {
        Leaderboard b = trackId == null ? null : boards.get(trackId);
        return b == null ? new Leaderboard() : b;
    }

    /** A pilot's progress, or {@code null} if they are not in a race (as far as this client knows). */
    public Progress progress(UUID playerId) {
        return playerId == null ? null : pilots.get(playerId);
    }

    /** The track {@code playerId} races on, if its definition is known. */
    public TrackDef joinedTrack(UUID playerId) {
        Progress p = progress(playerId);
        return p == null ? null : tracks.get(p.trackId);
    }
}
