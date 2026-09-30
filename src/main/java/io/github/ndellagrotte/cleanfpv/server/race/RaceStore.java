package io.github.ndellagrotte.cleanfpv.server.race;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.Leaderboard;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Tracks and best-lap leaderboards of one world, persisted as JSON in the world save
 * ({@code <world>/data/cleanfpv_race.json}). Server thread only.
 *
 * <p>Every mutation is written through immediately (write to a temp file, then move over the old
 * one), so a crash never loses a track or a record. A corrupt file is renamed to
 * {@code .corrupt} and the world starts without tracks; a bad track or gate entry is skipped with a
 * log line.
 */
final class RaceStore {

    static final String FILE_NAME = "cleanfpv_race.json";
    static final int SCHEMA = 1;
    /** Tracks per world. */
    static final int MAX_TRACKS = 64;
    /** Gates per track (the wire allows more; this keeps a track packet small). */
    static final int MAX_GATES = 128;
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File file;
    private final Map<UUID, TrackDef> tracks = new LinkedHashMap<>();
    private final Map<UUID, Leaderboard> boards = new HashMap<>();

    private RaceStore(File file) {
        this.file = file;
    }

    /** A store without a file (nothing loaded, saves are no-ops). */
    static RaceStore empty() {
        return new RaceStore(null);
    }

    /** Loads the store of the world whose save directory is {@code worldDir}. */
    static RaceStore load(File worldDir) {
        RaceStore store = new RaceStore(new File(new File(worldDir, "data"), FILE_NAME));
        store.read();
        return store;
    }

    // ---------------------------------------------------------------------------------------------
    // Queries

    Collection<TrackDef> tracks() {
        return Collections.unmodifiableCollection(tracks.values());
    }

    TrackDef track(UUID id) {
        return id == null ? null : tracks.get(id);
    }

    /** Case-insensitive name lookup, or {@code null}. */
    TrackDef byName(String name) {
        for (TrackDef t : tracks.values()) {
            if (t.name.equalsIgnoreCase(name)) {
                return t;
            }
        }
        return null;
    }

    /** The leaderboard of a track (created on demand). */
    Leaderboard board(UUID trackId) {
        return boards.computeIfAbsent(trackId, id -> new Leaderboard());
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    // ---------------------------------------------------------------------------------------------
    // Mutations (each one saves)

    /** Inserts or replaces a track definition. */
    void put(TrackDef track) {
        tracks.put(track.id, track);
        save();
    }

    void remove(UUID trackId) {
        tracks.remove(trackId);
        boards.remove(trackId);
        save();
    }

    /** Records a lap; saves and returns true if it is the pilot's new best. */
    boolean submitLap(UUID trackId, UUID playerId, int lapMs) {
        boolean improved = board(trackId).submit(playerId, lapMs);
        if (improved) {
            save();
        }
        return improved;
    }

    void clearBoard(UUID trackId) {
        board(trackId).clear();
        save();
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence

    private void read() {
        if (file == null || !file.isFile()) {
            return;
        }
        FileData data;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            data = GSON.fromJson(r, FileData.class);
        } catch (IOException | JsonParseException | IllegalStateException e) {
            CleanFpv.LOGGER.error("Race data {} is unreadable; starting without tracks", file, e);
            backUpCorrupt();
            return;
        }
        if (data == null || data.tracks == null) {
            return;
        }
        for (TrackData td : data.tracks) {
            TrackDef track = td == null ? null : td.toDef();
            if (track == null) {
                CleanFpv.LOGGER.warn("Skipping a malformed race track in {}", file);
                continue;
            }
            if (tracks.size() >= MAX_TRACKS || byName(track.name) != null || tracks.containsKey(track.id)) {
                CleanFpv.LOGGER.warn("Skipping race track '{}' in {} (duplicate or over the limit)", track.name, file);
                continue;
            }
            tracks.put(track.id, track);
            Leaderboard board = board(track.id);
            if (td.best != null) {
                td.best.forEach((player, ms) -> {
                    UUID id = parseUuid(player);
                    if (id != null && ms != null) {
                        board.submit(id, ms);
                    }
                });
            }
        }
        CleanFpv.LOGGER.info("Loaded {} race track(s) from {}", tracks.size(), file);
    }

    private void save() {
        if (file == null) {
            return;
        }
        FileData data = new FileData();
        data.schema = SCHEMA;
        data.tracks = new ArrayList<>();
        for (TrackDef t : tracks.values()) {
            TrackData td = TrackData.of(t);
            Leaderboard board = boards.get(t.id);
            if (board != null) {
                for (Leaderboard.Entry e : board.all()) {
                    td.best.put(e.playerId().toString(), e.lapMs());
                }
            }
            data.tracks.add(td);
        }
        Path target = file.toPath();
        Path tmp = target.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            CleanFpv.LOGGER.error("Could not save race data to {}", file, e);
        }
    }

    private void backUpCorrupt() {
        try {
            Path p = file.toPath();
            Files.move(p, p.resolveSibling(FILE_NAME + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CleanFpv.LOGGER.warn("Could not back up the corrupt race data file {}", file, e);
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // JSON shapes (plain classes; field names are the file format)

    static final class FileData {
        int schema;
        List<TrackData> tracks;
    }

    static final class TrackData {
        String id;
        String name;
        List<GateData> gates = new ArrayList<>();
        Map<String, Integer> best = new LinkedHashMap<>();

        static TrackData of(TrackDef t) {
            TrackData td = new TrackData();
            td.id = t.id.toString();
            td.name = t.name;
            for (GateDef g : t.gates) {
                td.gates.add(GateData.of(g));
            }
            return td;
        }

        TrackDef toDef() {
            UUID uuid = parseUuid(id);
            if (uuid == null || !validName(name)) {
                return null;
            }
            List<GateDef> defs = new ArrayList<>();
            if (gates != null) {
                for (GateData g : gates) {
                    GateDef def = g == null ? null : g.toDef();
                    if (def == null) {
                        CleanFpv.LOGGER.warn("Race track '{}': dropping a malformed gate", name);
                        continue;
                    }
                    if (defs.size() < MAX_GATES) {
                        defs.add(def);
                    }
                }
            }
            return new TrackDef(uuid, name, defs);
        }
    }

    static final class GateData {
        int dim;
        int[] a;
        int[] b;
        int[] rowStart;
        int[] rowEnd;
        int[] dirA;
        int[] dirB;

        static GateData of(GateDef g) {
            GateData d = new GateData();
            d.dim = g.dimension;
            d.a = xyz(g.cornerA);
            d.b = xyz(g.cornerB);
            int rows = g.rowCount();
            d.rowStart = new int[rows];
            d.rowEnd = new int[rows];
            for (int j = 0; j < rows; j++) {
                d.rowStart[j] = g.rowStart(j);
                d.rowEnd[j] = g.rowEnd(j);
            }
            d.dirA = xyz(g.dirA);
            d.dirB = xyz(g.dirB);
            return d;
        }

        GateDef toDef() {
            if (!is3(a) || !is3(b) || !is3(dirA) || !is3(dirB) || rowStart == null || rowEnd == null
                    || rowStart.length != rowEnd.length || rowStart.length > GateDef.MAX_ROWS) {
                return null;
            }
            return new GateDef(dim, new BlockPos(a[0], a[1], a[2]), new BlockPos(b[0], b[1], b[2]),
                    rowStart, rowEnd, new Vec3i(dirA[0], dirA[1], dirA[2]), new Vec3i(dirB[0], dirB[1], dirB[2]));
        }

        private static boolean is3(int[] v) {
            return v != null && v.length == 3;
        }

        private static int[] xyz(Vec3i v) {
            return new int[] {v.getX(), v.getY(), v.getZ()};
        }
    }
}
