package io.github.ndellagrotte.cleanfpv.common.race;

import io.github.ndellagrotte.cleanfpv.common.net.Wire;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A race track (spec §8.1 "TrackDef"): id, display name, ordered gates. Immutable.
 * Wire form: UUID, string name, u16 gate count (≤ {@link #MAX_GATES}), gates.
 */
public final class TrackDef {

    public static final int MAX_GATES = 1024;

    public final UUID id;
    public final String name;
    public final List<GateDef> gates;

    public TrackDef(UUID id, String name, List<GateDef> gates) {
        this.id = id;
        this.name = name;
        this.gates = List.copyOf(gates);
    }

    public void write(ByteBuf buf) {
        Wire.writeUuid(buf, id);
        Wire.writeString(buf, name);
        buf.writeShort(gates.size());
        for (GateDef g : gates) {
            g.write(buf);
        }
    }

    public static TrackDef read(ByteBuf buf) {
        UUID id = Wire.readUuid(buf);
        String name = Wire.readString(buf);
        int n = Wire.readCount(buf, MAX_GATES, "gates");
        List<GateDef> gates = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            gates.add(GateDef.read(buf));
        }
        return new TrackDef(id, name, gates);
    }
}
