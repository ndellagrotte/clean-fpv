package io.github.ndellagrotte.cleanfpv.server.race;

import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceStoreTest {

    private static final GateDef GATE = new GateDef(-1, new BlockPos(1, 64, 5), new BlockPos(4, 66, 5),
            new int[] {0, 1, 0}, new int[] {3, 2, -1}, new Vec3i(1, 0, 0), new Vec3i(0, 1, 0));

    @Test
    void tracksAndBestLapsSurviveAReload(@TempDir File world) {
        RaceStore store = RaceStore.load(world);
        TrackDef track = new TrackDef(UUID.randomUUID(), "Canyon_1", List.of(GATE, GATE));
        store.put(track);
        UUID pilot = new UUID(3, 4);
        assertTrue(store.submitLap(track.id, pilot, 42_000));
        assertFalse(store.submitLap(track.id, pilot, 43_000));

        RaceStore reloaded = RaceStore.load(world);
        TrackDef back = reloaded.byName("canyon_1");
        assertEquals(track.id, back.id);
        assertEquals(track.gates, back.gates);
        assertEquals(42_000, reloaded.board(track.id).bestMs(pilot));

        reloaded.remove(track.id);
        assertNull(RaceStore.load(world).track(track.id));
    }

    @Test
    void corruptFileIsSetAsideAndBadEntriesSkipped(@TempDir File world) throws Exception {
        File data = new File(world, "data");
        assertTrue(data.mkdirs());
        File file = new File(data, RaceStore.FILE_NAME);
        Files.writeString(file.toPath(), "{ not json", StandardCharsets.UTF_8);
        RaceStore store = RaceStore.load(world);
        assertTrue(store.tracks().isEmpty());
        assertTrue(new File(data, RaceStore.FILE_NAME + ".corrupt").isFile());

        Files.writeString(file.toPath(), """
                {"schema":1,"tracks":[
                  {"id":"not-a-uuid","name":"x","gates":[]},
                  {"id":"00000000-0000-0001-0000-000000000001","name":"bad name!","gates":[]},
                  {"id":"00000000-0000-0001-0000-000000000002","name":"ok","gates":[{"dim":0,"a":[0,0]}],
                   "best":{"00000000-0000-0000-0000-000000000009":1234,"junk":5}}
                ]}""", StandardCharsets.UTF_8);
        RaceStore partial = RaceStore.load(world);
        assertEquals(1, partial.tracks().size());
        TrackDef ok = partial.byName("ok");
        assertTrue(ok.gates.isEmpty());
        assertEquals(1234, partial.board(ok.id).bestMs(new UUID(0, 9)));
    }

    @Test
    void namesAreValidated() {
        assertTrue(RaceStore.validName("a"));
        assertTrue(RaceStore.validName("Track-2_b"));
        assertFalse(RaceStore.validName(""));
        assertFalse(RaceStore.validName("has space"));
        assertFalse(RaceStore.validName("x".repeat(33)));
        assertFalse(RaceStore.validName(null));
    }
}
