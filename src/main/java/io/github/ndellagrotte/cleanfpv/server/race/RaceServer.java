package io.github.ndellagrotte.cleanfpv.server.race;

import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateProgressS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.GateS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.JoinRaceS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapFinishedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.LapStartedS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.RaceModeS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TrackS2C;
import io.github.ndellagrotte.cleanfpv.common.race.CrossingScanner;
import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.LapSequencer;
import io.github.ndellagrotte.cleanfpv.common.race.Leaderboard;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import io.github.ndellagrotte.cleanfpv.server.ServerConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-authoritative race module (PLAN v1 §6.8, spec §9 and §13 "race authority server-side"):
 * tracks and gates (persisted per world by {@link RaceStore}), gate-crossing detection from player
 * positions, lap timing on the server clock, the best-lap leaderboard, the {@code /cleanfpv race}
 * command ({@link RaceCommand}), and the race S2C packets.
 *
 * <h2>Timing</h2>
 * At {@code ServerTickEvent} END every armed racer's position (the centre of the player's box; at
 * that point it is the position the client last reported, see NetHandlerPlayServer.update) is
 * sampled. The segment from the previous sample is tested against the track gates by
 * {@link CrossingScanner}; crossing times are interpolated inside the tick on a monotonic
 * millisecond clock. Segments are skipped (not tested) after a dimension change, a gap of more
 * than {@link #MAX_SAMPLE_GAP_MS}, a jump longer than {@link #maxSegment} allows for the elapsed time
 * (teleport), or
 * while the pilot is disarmed, so a {@code /tp} through a gate never counts. Only armed pilots
 * trigger gates: this is a drone race.
 *
 * <h2>Packets and who gets them</h2>
 * <ul>
 *   <li>{@link TrackS2C}: every track to a player on login, respawn and dimension change (the
 *       client drops its race state on world unload); a created or edited track to everyone. A
 *       track packet means "this track (re)defined": the client clears that track's leaderboard
 *       and progress, and the server resets its racers on that track and re-sends their state.</li>
 *   <li>{@link JoinRaceS2C}: to the pilot and every other racer on that track.</li>
 *   <li>{@link LapStartedS2C}, {@link GateProgressS2C}: to the pilot only.</li>
 *   <li>{@link LapFinishedS2C}: to every racer on the track (leaderboard). On join the pilot also
 *       gets the stored top {@value Leaderboard#SHOWN} (plus their own best), sent before the
 *       join packet, which resets the pilot's own session on the client.</li>
 *   <li>{@link RaceModeS2C} + {@link GateS2C}: "race mode" is a per-player gate display for
 *       building and checking tracks. On: the client's live gate list is cleared and the gates of
 *       the player's display track are sent (the track last named in {@code mode}/{@code create}/
 *       {@code addgate}, else the joined track); new gates of that track follow as they are
 *       added. Off: the list is cleared.</li>
 * </ul>
 * A deleted track has no packet: racers on it get a leave, and the stale definition stays in
 * clients' memory until they change world (harmless: only the joined track is shown).
 *
 * <h2>Lifecycle</h2>
 * Logout withdraws the pilot (spec §9: leaving the server leaves the track) and forgets their race
 * mode. Respawn and dimension change abort the running lap and re-send the race state. All state
 * is dropped when the overworld unloads (server stop) and reloaded in {@link #onServerStarting}.
 *
 * <p>Owner: (G) race. Registered on {@code MinecraftForge.EVENT_BUS} by {@code CommonProxy} on
 * both physical sides; on a client connected to a remote server nothing here runs (no server).
 * No client classes.
 */
public final class RaceServer {

    /**
     * Minimum segment length that still counts as flying (≈ two ticks at the 25 blocks/tick cap). One
     * sample can include many client packets (a server hitch or a client stall bunches them into one
     * tick), so the real limit grows with the time since the previous sample, see {@link #maxSegment}.
     */
    static final double MAX_SEGMENT = 40.0;
    /** Margin factor and slack (blocks) on the time-scaled segment limit (dt jitter, catch-up bursts). */
    static final double SEGMENT_TIME_FACTOR = 1.5;
    static final double SEGMENT_SLACK = 2.0;
    /** Samples further apart than this do not form a segment (server hitch, pilot re-armed). */
    static final long MAX_SAMPLE_GAP_MS = 1000L;

    private MinecraftServer server;
    private RaceStore store = RaceStore.empty();
    private final Map<UUID, Racer> racers = new HashMap<>();
    private final Set<UUID> raceMode = new HashSet<>();
    /** Player → track shown in race mode, when set explicitly. */
    private final Map<UUID, UUID> displayFocus = new HashMap<>();
    private final Map<UUID, TrackGeometry> geometry = new HashMap<>();

    /** Called on {@code FMLServerStartingEvent} (after the worlds loaded): load tracks, register the command. */
    public void onServerStarting(FMLServerStartingEvent event) {
        clearState();
        server = event.getServer();
        store = RaceStore.load(server.getEntityWorld().getSaveHandler().getWorldDirectory());
        event.registerServerCommand(new RaceCommand(this));
    }

    // ---------------------------------------------------------------------------------------------
    // Events

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0) {
            clearState();
            server = null;
            store = RaceStore.empty();
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && server != null && !racers.isEmpty()) {
            tickRacers();
        }
    }

    @SubscribeEvent
    public void onLoggedIn(PlayerLoggedInEvent event) {
        if (server != null && event.player instanceof EntityPlayerMP player) {
            sendAllTracks(player);
        }
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerLoggedOutEvent event) {
        if (server != null) {
            UUID id = event.player.getUniqueID();
            withdraw(id);
            raceMode.remove(id);
            displayFocus.remove(id);
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerRespawnEvent event) {
        if (server != null && event.player instanceof EntityPlayerMP player) {
            resync(player);
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerChangedDimensionEvent event) {
        if (server != null && event.player instanceof EntityPlayerMP player) {
            resync(player);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Timing

    private void tickRacers() {
        long now = monotonicMs();
        List<Racer> snapshot = new ArrayList<>(racers.values());
        for (Racer racer : snapshot) {
            EntityPlayerMP player = server.getPlayerList().getPlayerByUUID(racer.playerId);
            TrackDef track = store.track(racer.trackId);
            if (player == null || track == null || track.gates.isEmpty()) {
                racer.dropSample();
                continue;
            }
            if (!ArmRegistry.SERVER.isArmed(racer.playerId) || !player.isEntityAlive()) {
                racer.dropSample();
                continue;
            }
            double x = player.posX;
            double y = player.posY + player.height * 0.5;
            double z = player.posZ;
            int dim = player.dimension;
            if (racer.hasSample() && racer.sampleDim() == dim && now - racer.sampleMs() <= MAX_SAMPLE_GAP_MS) {
                double[] from = racer.sample();
                double dx = x - from[0];
                double dy = y - from[1];
                double dz = z - from[2];
                double limit = maxSegment(now - racer.sampleMs(), ServerConfig.maxSpeed());
                if (dx * dx + dy * dy + dz * dz <= limit * limit) {
                    double[] to = {x, y, z};
                    CrossingScanner.scan(racer.laps, geometry(track).inDimension(dim), from.clone(),
                            racer.sampleMs(), to, now, crossing -> onCrossing(player, racer, track, crossing, now));
                }
            }
            racer.setSample(x, y, z, dim, now);
        }
    }

    private void onCrossing(EntityPlayerMP player, Racer racer, TrackDef track, CrossingScanner.Crossing crossing,
                            long nowMs) {
        LapSequencer.Step step = crossing.step();
        UUID id = racer.playerId;
        switch (step.kind()) {
            case STARTED -> sendLapStart(player, track, crossing.timeMs(), step.nextGate(), nowMs);
            case PROGRESS -> Channel.sendTo(new GateProgressS2C(step.nextGate(), track.id, id), player);
            case FINISHED -> {
                store.submitLap(track.id, id, step.lapMs());
                sendToRacers(track.id, new LapFinishedS2C(step.lapMs(), track.id, id), player);
                sendLapStart(player, track, crossing.timeMs(), step.nextGate(), nowMs);
            }
            case NONE -> {
            }
        }
    }

    private static void sendLapStart(EntityPlayerMP player, TrackDef track, long crossingMs, int nextGate, long nowMs) {
        long wallClock = System.currentTimeMillis() - Math.max(0L, nowMs - crossingMs);
        Channel.sendTo(new LapStartedS2C(wallClock, player.getUniqueID()), player);
        Channel.sendTo(new GateProgressS2C(nextGate, track.id, player.getUniqueID()), player);
    }

    private TrackGeometry geometry(TrackDef track) {
        TrackGeometry g = geometry.get(track.id);
        if (g == null || g.def != track) {
            g = new TrackGeometry(track);
            geometry.put(track.id, g);
        }
        return g;
    }

    /**
     * Longest sample-to-sample segment (blocks) that is scanned for crossings, {@code dtMs} after the
     * previous sample: {@code max(MAX_SEGMENT, maxSpeed·dt·1.5 + 2)} (1 block = 1 m). Longer jumps are
     * teleports and are not scanned.
     */
    static double maxSegment(long dtMs, double maxSpeedMps) {
        double speed = Double.isFinite(maxSpeedMps) && maxSpeedMps > 0.0 ? maxSpeedMps : 0.0;
        double timed = speed / 1000.0 * Math.max(0L, dtMs) * SEGMENT_TIME_FACTOR + SEGMENT_SLACK;
        return Math.max(MAX_SEGMENT, timed);
    }

    private static long monotonicMs() {
        return System.nanoTime() / 1_000_000L;
    }

    // ---------------------------------------------------------------------------------------------
    // Operations used by RaceCommand (server thread)

    RaceStore store() {
        return store;
    }

    MinecraftServer server() {
        return server;
    }

    /** The track the player is racing on, or {@code null}. */
    TrackDef joinedTrack(UUID playerId) {
        Racer r = racers.get(playerId);
        return r == null ? null : store.track(r.trackId);
    }

    int racerCount(UUID trackId) {
        int n = 0;
        for (Racer r : racers.values()) {
            if (r.trackId.equals(trackId)) {
                n++;
            }
        }
        return n;
    }

    TrackDef createTrack(String name) {
        TrackDef track = new TrackDef(UUID.randomUUID(), name, List.of());
        store.put(track);
        Channel.sendToAll(new TrackS2C(track));
        return track;
    }

    void deleteTrack(TrackDef track) {
        List<UUID> viewers = new ArrayList<>();
        for (UUID viewer : raceMode) {
            TrackDef shown = displayTrack(viewer);
            if (shown != null && shown.id.equals(track.id)) {
                viewers.add(viewer);
            }
        }
        for (Racer r : new ArrayList<>(racers.values())) {
            if (r.trackId.equals(track.id)) {
                withdraw(r.playerId);
            }
        }
        store.remove(track.id);
        geometry.remove(track.id);
        displayFocus.values().removeIf(track.id::equals);
        for (UUID viewer : viewers) {
            refreshRaceMode(viewer);
        }
    }

    /** Appends a gate; returns the new definition. The leaderboard is cleared (times no longer compare). */
    TrackDef addGate(TrackDef track, GateDef gate) {
        List<GateDef> gates = new ArrayList<>(track.gates);
        gates.add(gate);
        TrackDef updated = new TrackDef(track.id, track.name, gates);
        redefine(updated);
        for (UUID viewer : raceMode) {
            TrackDef shown = displayTrack(viewer);
            EntityPlayerMP p = player(viewer);
            if (p != null && shown != null && shown.id.equals(updated.id)) {
                Channel.sendTo(new GateS2C(gate), p);
            }
        }
        return updated;
    }

    /** Removes gate {@code index}; returns the new definition. The leaderboard is cleared. */
    TrackDef removeGate(TrackDef track, int index) {
        List<GateDef> gates = new ArrayList<>(track.gates);
        gates.remove(index);
        TrackDef updated = new TrackDef(track.id, track.name, gates);
        redefine(updated);
        for (UUID viewer : new ArrayList<>(raceMode)) {
            TrackDef shown = displayTrack(viewer);
            if (shown != null && shown.id.equals(updated.id)) {
                refreshRaceMode(viewer);
            }
        }
        return updated;
    }

    void resetBoard(TrackDef track) {
        redefine(track);
    }

    /** Joins (or re-joins) {@code track}, leaving any other track first. */
    void join(EntityPlayerMP player, TrackDef track) {
        UUID id = player.getUniqueID();
        Racer old = racers.get(id);
        if (old != null && !old.trackId.equals(track.id)) {
            withdraw(id);
        }
        racers.put(id, new Racer(id, track.id, track.gates.size()));
        Channel.sendTo(new TrackS2C(track), player);
        sendBoard(player, track);
        sendToRacers(track.id, new JoinRaceS2C(true, track.id, id), player);
        if (raceMode.contains(id) && !displayFocus.containsKey(id)) {
            refreshRaceMode(id);
        }
    }

    /** Leaves the current track; returns it, or {@code null} if the player was not racing. */
    TrackDef leave(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        TrackDef track = joinedTrack(id);
        boolean was = withdraw(id);
        if (was && raceMode.contains(id) && !displayFocus.containsKey(id)) {
            refreshRaceMode(id);
        }
        return was ? track : null;
    }

    boolean isRaceMode(UUID playerId) {
        return raceMode.contains(playerId);
    }

    /** Sets the track shown in race mode (and re-sends it if race mode is on). */
    void focus(UUID playerId, TrackDef track) {
        UUID old = displayFocus.put(playerId, track.id);
        if (raceMode.contains(playerId) && !track.id.equals(old)) {
            refreshRaceMode(playerId);
        }
    }

    /** Race mode on/off for one player; returns the track now shown (on) or {@code null}. */
    TrackDef setRaceMode(EntityPlayerMP player, boolean on) {
        UUID id = player.getUniqueID();
        if (on) {
            raceMode.add(id);
            refreshRaceMode(id);
            return displayTrack(id);
        }
        raceMode.remove(id);
        Channel.sendTo(new RaceModeS2C(false), player);
        return null;
    }

    /** The track shown in race mode: the explicit focus, else the joined track, else {@code null}. */
    TrackDef displayTrack(UUID playerId) {
        TrackDef focused = store.track(displayFocus.get(playerId));
        return focused != null ? focused : joinedTrack(playerId);
    }

    // ---------------------------------------------------------------------------------------------
    // Sync helpers

    /** Stores a (re)defined track, clears its board, broadcasts it and restarts its racers. */
    private void redefine(TrackDef track) {
        store.board(track.id).clear();
        store.put(track);
        Channel.sendToAll(new TrackS2C(track));
        for (Racer r : racers.values()) {
            if (!r.trackId.equals(track.id)) {
                continue;
            }
            r.reset(track.gates.size());
            EntityPlayerMP p = player(r.playerId);
            if (p != null) {
                Channel.sendTo(new JoinRaceS2C(true, track.id, r.playerId), p);
            }
        }
    }

    private void sendAllTracks(EntityPlayerMP player) {
        for (TrackDef t : store.tracks()) {
            Channel.sendTo(new TrackS2C(t), player);
        }
    }

    /** After respawn / dimension change: the client dropped its race state on world unload. */
    private void resync(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        sendAllTracks(player);
        Racer racer = racers.get(id);
        TrackDef track = racer == null ? null : store.track(racer.trackId);
        if (track != null) {
            racer.reset(track.gates.size());
            sendBoard(player, track);
            Channel.sendTo(new JoinRaceS2C(true, track.id, id), player);
        }
        if (raceMode.contains(id)) {
            refreshRaceMode(id);
        }
    }

    /** Stored top laps (plus the player's own best) as lap-finished packets, fastest first. */
    private void sendBoard(EntityPlayerMP player, TrackDef track) {
        Leaderboard board = store.board(track.id);
        UUID self = player.getUniqueID();
        boolean selfSent = false;
        for (Leaderboard.Entry e : board.top(Leaderboard.SHOWN)) {
            Channel.sendTo(new LapFinishedS2C(e.lapMs(), track.id, e.playerId()), player);
            selfSent |= e.playerId().equals(self);
        }
        int own = board.bestMs(self);
        if (!selfSent && own > 0) {
            Channel.sendTo(new LapFinishedS2C(own, track.id, self), player);
        }
    }

    private void refreshRaceMode(UUID playerId) {
        EntityPlayerMP p = player(playerId);
        if (p == null) {
            return;
        }
        Channel.sendTo(new RaceModeS2C(true), p);
        TrackDef shown = displayTrack(playerId);
        if (shown != null) {
            for (GateDef g : shown.gates) {
                Channel.sendTo(new GateS2C(g), p);
            }
        }
    }

    /** Removes the racer and tells them and the remaining racers of that track. */
    private boolean withdraw(UUID playerId) {
        Racer r = racers.remove(playerId);
        if (r == null) {
            return false;
        }
        JoinRaceS2C leave = new JoinRaceS2C(false, r.trackId, playerId);
        EntityPlayerMP self = player(playerId);
        if (self != null) {
            Channel.sendTo(leave, self);
        }
        sendToRacers(r.trackId, leave, null);
        return true;
    }

    /** Sends to every racer on {@code trackId}, plus {@code also} if it is not one of them. */
    private void sendToRacers(UUID trackId, IMessage message, EntityPlayerMP also) {
        boolean alsoSent = also == null;
        for (Racer r : racers.values()) {
            if (!r.trackId.equals(trackId)) {
                continue;
            }
            EntityPlayerMP p = player(r.playerId);
            if (p != null) {
                Channel.sendTo(message, p);
                alsoSent |= p == also;
            }
        }
        if (!alsoSent) {
            Channel.sendTo(message, also);
        }
    }

    private EntityPlayerMP player(UUID id) {
        return server == null ? null : server.getPlayerList().getPlayerByUUID(id);
    }

    private void clearState() {
        racers.clear();
        raceMode.clear();
        displayFocus.clear();
        geometry.clear();
    }
}
