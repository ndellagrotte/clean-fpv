package io.github.ndellagrotte.cleanfpv.common.race;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Best lap per pilot on one track (PLAN §10: the leaderboard keeps each pilot's <em>best</em> lap,
 * not their latest). Used by the server (authoritative, persisted) and by the client (rebuilt from
 * lap-finished packets). Not thread-safe; each side uses it on its main thread.
 */
public final class Leaderboard {

    /** Rows shown on the HUD and sent to a joining pilot. */
    public static final int SHOWN = 10;

    /** One row. */
    public record Entry(UUID playerId, int lapMs) {
    }

    /** Fastest first; equal times in UUID order so the ranking is stable. */
    public static final Comparator<Entry> ORDER =
            Comparator.comparingInt(Entry::lapMs).thenComparing(Entry::playerId);

    private final Map<UUID, Integer> best = new HashMap<>();

    /**
     * Records a finished lap. Returns true if it is the pilot's new best (or first) lap.
     * Non-positive times are ignored.
     */
    public boolean submit(UUID playerId, int lapMs) {
        if (playerId == null || lapMs <= 0) {
            return false;
        }
        Integer old = best.get(playerId);
        if (old != null && old <= lapMs) {
            return false;
        }
        best.put(playerId, lapMs);
        return true;
    }

    /** The pilot's best lap in ms, or −1. */
    public int bestMs(UUID playerId) {
        Integer ms = best.get(playerId);
        return ms == null ? -1 : ms;
    }

    /** 1-based rank of the pilot, or −1 if they have no lap. */
    public int rank(UUID playerId) {
        Integer ms = best.get(playerId);
        if (ms == null) {
            return -1;
        }
        Entry mine = new Entry(playerId, ms);
        int rank = 1;
        for (Map.Entry<UUID, Integer> e : best.entrySet()) {
            if (ORDER.compare(new Entry(e.getKey(), e.getValue()), mine) < 0) {
                rank++;
            }
        }
        return rank;
    }

    /** The fastest {@code n} entries, fastest first. */
    public List<Entry> top(int n) {
        List<Entry> all = all();
        return n >= all.size() ? all : new ArrayList<>(all.subList(0, Math.max(0, n)));
    }

    /** All entries, fastest first. */
    public List<Entry> all() {
        List<Entry> out = new ArrayList<>(best.size());
        best.forEach((id, ms) -> out.add(new Entry(id, ms)));
        out.sort(ORDER);
        return out;
    }

    public int size() {
        return best.size();
    }

    public boolean isEmpty() {
        return best.isEmpty();
    }

    public void clear() {
        best.clear();
    }
}
