package io.github.ndellagrotte.cleanfpv.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The server's own settings, {@code config/cleanfpv/server.json}: currently only the speed cap
 * (m/s) that is announced in {@code HelloS2C} and enforced by transform validation (PLAN §6.6).
 * A missing file is created with defaults; a corrupt file is logged and the defaults are used
 * (the file is left untouched so an admin can fix it).
 *
 * <p>(Re)loaded by {@link ServerEvents} when a server's overworld loads, i.e. once per server
 * start; read on the server thread.
 */
public final class ServerConfig {

    /** Lower bound for {@link #maxSpeed()} (m/s); anything slower makes flying pointless. */
    public static final float MIN_MAX_SPEED = 1f;
    /** Upper bound for {@link #maxSpeed()} (m/s): the client physics' absolute clamp. */
    public static final float MAX_MAX_SPEED = 500f;

    static final String FILE_NAME = "server.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile float maxSpeed = Channel.DEFAULT_MAX_SPEED;
    private static volatile boolean loaded;

    private ServerConfig() {}

    /** Gson shape of {@code server.json}. */
    static final class Data {
        /** Top speed a pilot may fly (m/s = blocks/s). */
        float maxSpeed = Channel.DEFAULT_MAX_SPEED;
    }

    /** Server speed cap in m/s, in {@code [MIN_MAX_SPEED, MAX_MAX_SPEED]}. Loads on first use. */
    public static float maxSpeed() {
        if (!loaded) {
            reload();
        }
        return maxSpeed;
    }

    /** Re-reads {@code server.json} (creating it with defaults when missing). */
    public static synchronized void reload() {
        loaded = true;
        File dir = CleanFpv.configDir();
        if (dir == null) {
            maxSpeed = Channel.DEFAULT_MAX_SPEED;
            return;
        }
        File file = new File(dir, FILE_NAME);
        Data data = new Data();
        if (file.isFile()) {
            try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
                Data read = GSON.fromJson(r, Data.class);
                if (read != null) {
                    data = read;
                }
            } catch (IOException | JsonParseException e) {
                CleanFpv.LOGGER.warn("Could not read {}; using defaults", file, e);
            }
        } else {
            try {
                Files.createDirectories(dir.toPath());
                try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    GSON.toJson(data, w);
                }
            } catch (IOException e) {
                CleanFpv.LOGGER.warn("Could not write default {}", file, e);
            }
        }
        maxSpeed = sanitizeMaxSpeed(data.maxSpeed);
    }

    /** Clamps a configured speed cap into range; non-finite → {@link Channel#DEFAULT_MAX_SPEED}. */
    static float sanitizeMaxSpeed(float v) {
        if (!Float.isFinite(v)) {
            return Channel.DEFAULT_MAX_SPEED;
        }
        return Math.clamp(v, MIN_MAX_SPEED, MAX_MAX_SPEED);
    }
}
