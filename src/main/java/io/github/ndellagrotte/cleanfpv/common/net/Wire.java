package io.github.ndellagrotte.cleanfpv.common.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Small, bounds-checked ByteBuf helpers shared by every packet and by the wire forms of the data
 * classes. Every variable-length read is capped so a malformed packet cannot allocate unbounded
 * memory; an out-of-range length throws {@link IllegalArgumentException}, which Forge's codec
 * turns into a dropped packet.
 */
public final class Wire {

    /** Longest string (in UTF-8 bytes) accepted on the wire. */
    public static final int MAX_STRING_BYTES = 1024;

    private Wire() {}

    public static void writeUuid(ByteBuf buf, UUID id) {
        buf.writeLong(id.getMostSignificantBits());
        buf.writeLong(id.getLeastSignificantBits());
    }

    public static UUID readUuid(ByteBuf buf) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        return new UUID(msb, lsb);
    }

    public static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("string too long for the wire: " + bytes.length + " bytes");
        }
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }

    public static String readString(ByteBuf buf) {
        int len = buf.readUnsignedShort();
        checkLength(len, MAX_STRING_BYTES, "string");
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeBlockPos(ByteBuf buf, BlockPos pos) {
        buf.writeLong(pos.toLong());
    }

    public static BlockPos readBlockPos(ByteBuf buf) {
        return BlockPos.fromLong(buf.readLong());
    }

    public static void writeVec3i(ByteBuf buf, Vec3i v) {
        buf.writeInt(v.getX());
        buf.writeInt(v.getY());
        buf.writeInt(v.getZ());
    }

    public static Vec3i readVec3i(ByteBuf buf) {
        return new Vec3i(buf.readInt(), buf.readInt(), buf.readInt());
    }

    public static void writeIntArray(ByteBuf buf, int[] values) {
        buf.writeShort(values.length);
        for (int v : values) {
            buf.writeInt(v);
        }
    }

    public static int[] readIntArray(ByteBuf buf, int maxLength) {
        int len = buf.readUnsignedShort();
        checkLength(len, maxLength, "int array");
        int[] out = new int[len];
        for (int i = 0; i < len; i++) {
            out[i] = buf.readInt();
        }
        return out;
    }

    /** Reads an element count written as an unsigned short and checks it against {@code max}. */
    public static int readCount(ByteBuf buf, int max, String what) {
        int len = buf.readUnsignedShort();
        checkLength(len, max, what);
        return len;
    }

    public static void checkLength(int len, int max, String what) {
        if (len < 0 || len > max) {
            throw new IllegalArgumentException(what + " length " + len + " exceeds limit " + max);
        }
    }
}
