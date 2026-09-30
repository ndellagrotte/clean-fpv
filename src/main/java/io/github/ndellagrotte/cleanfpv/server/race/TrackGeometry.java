package io.github.ndellagrotte.cleanfpv.server.race;

import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.GateVolume;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gate volumes of one track definition, with per-dimension views where gates of other
 * dimensions are {@code null} (never crossed). Built once per {@link TrackDef} instance; tracks are
 * immutable and replaced on edit, so a stale instance is detected by identity.
 */
final class TrackGeometry {

    final TrackDef def;
    private final List<GateVolume> volumes;
    private final Map<Integer, List<GateVolume>> byDimension = new HashMap<>();

    TrackGeometry(TrackDef def) {
        this.def = def;
        List<GateVolume> list = new ArrayList<>(def.gates.size());
        for (GateDef g : def.gates) {
            list.add(GateVolume.of(g));
        }
        this.volumes = Collections.unmodifiableList(list);
    }

    /** Index-aligned with {@code def.gates}; gates outside {@code dimension} are {@code null}. */
    List<GateVolume> inDimension(int dimension) {
        return byDimension.computeIfAbsent(dimension, dim -> {
            List<GateVolume> view = new ArrayList<>(volumes.size());
            for (int i = 0; i < volumes.size(); i++) {
                view.add(def.gates.get(i).dimension == dim ? volumes.get(i) : null);
            }
            return Collections.unmodifiableList(view);
        });
    }
}
