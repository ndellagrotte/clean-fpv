package io.github.ndellagrotte.cleanfpv.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Loads, edits and saves {@code settings.json} (PLAN §6.7, spec §10): {@code {schemaVersion,
 * models, currentModel, firstTimeSetup}} as Gson JSON in one directory (the mod uses
 * {@code config/cleanfpv/}; tests pass a temp directory).
 *
 * <h2>Robustness</h2>
 * <ul>
 *   <li>Missing file: defaults (all {@link Presets}, {@link Presets#defaultModel()} selected,
 *       {@code firstTimeSetup = true}) are created and written.</li>
 *   <li>Unreadable / malformed file (I/O error, invalid JSON, wrong top-level types): the file is
 *       moved aside to {@code settings.json.corrupt-<yyyyMMdd-HHmmss>} (clock injectable) and
 *       defaults are recreated and written.</li>
 *   <li>A single malformed model entry is dropped with a log line; the rest loads. Every loaded
 *       model is {@link DroneModelConfig#normalize() normalized}; the entry key is authoritative for
 *       the name.</li>
 *   <li>Presets are recognised by name: missing ones are re-added, stored build keys are ignored
 *       (built-in build restored) and never written; a non-preset name never keeps
 *       {@code preset = true}.</li>
 *   <li>An unknown {@code currentModel} falls back to the default preset.</li>
 *   <li>A schema-1 file (or one without {@code schemaVersion}) is migrated once, see
 *       {@link #migrateV1}; the original is kept as {@code settings.json.v1-backup}.</li>
 *   <li>Saving writes a temp file in the same directory and moves it over the target (atomic where
 *       the file system supports it).</li>
 * </ul>
 *
 * <h2>Editing</h2>
 * Mutators ({@link #select}, {@link #put}, {@link #cloneCurrent}, {@link #delete}, {@link #rename},
 * {@link #setFirstTimeSetup}) change memory only and notify listeners; call {@link #save()} to
 * persist. Callers may also mutate the live {@link #current()} object's fields directly (the
 * settings screens do) and then {@link #save()}. Not thread-safe: client thread only.
 *
 * <p>No Minecraft imports: usable from unit tests.
 */
public final class SettingsStore {

    public static final String FILE_NAME = "settings.json";
    /** Maximum length of a user model name (spec §11.2). */
    public static final int MAX_NAME_LENGTH = 24;
    /** Suffix of the copy kept of a schema-1 file before {@link #migrateV1} rewrites it. */
    public static final String LEGACY_BACKUP_SUFFIX = ".v1-backup";

    private static final Logger LOG = LogManager.getLogger("Clean FPV/settings");
    private static final DateTimeFormatter BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** What changed, for {@link Listener}s. */
    public enum Change {
        /** {@link #load()} finished (also after recovery from a corrupt file). */
        LOADED,
        /** The active model switched. */
        SELECTED,
        /** A model was added, replaced, removed or renamed (the active one may be affected). */
        MODELS,
        /** A flag such as {@code firstTimeSetup} changed. */
        FLAGS,
        /** {@link #save()} wrote the file (fields of the active model may have been edited in place). */
        SAVED
    }

    /** Notified after every change; {@code current} is the (live) active model, never {@code null}. */
    @FunctionalInterface
    public interface Listener {
        void onSettingsChanged(Change change, DroneModelConfig current);
    }

    /** Result of {@link #checkName(String)}. */
    public enum NameCheck { OK, EMPTY, TOO_LONG, DUPLICATE, RESERVED }

    private final File directory;
    private final Clock clock;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Settings settings;

    /** Store in {@code directory} using the system clock for backup names. */
    public SettingsStore(File directory) {
        this(directory, Clock.systemDefaultZone());
    }

    /** Store in {@code directory}; {@code clock} names corrupt-file backups. */
    public SettingsStore(File directory, Clock clock) {
        this.directory = directory;
        this.clock = clock;
        this.settings = defaults();
    }

    // =============================================================================================
    // Files

    public File directory() {
        return directory;
    }

    public File file() {
        return new File(directory, FILE_NAME);
    }

    /**
     * Loads the file (see class doc for recovery rules), replacing the in-memory settings, and
     * notifies {@link Change#LOADED}. Never throws.
     */
    public void load() {
        File f = file();
        Settings loaded;
        boolean writeBack = false;
        if (!f.isFile()) {
            LOG.info("No {} yet; creating defaults", f);
            loaded = defaults();
            writeBack = true;
        } else {
            try {
                String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                loaded = parse(text);
                if (loaded.schemaVersion < 2) {
                    backupLegacy(f);
                    migrateV1(loaded);
                    writeBack = true;
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Settings file {} is unreadable ({}); backing it up and recreating defaults", f, e.toString());
                backupCorrupt(f);
                loaded = defaults();
                writeBack = true;
            }
        }
        repair(loaded);
        settings = loaded;
        if (writeBack) {
            write();
        }
        fire(Change.LOADED);
    }

    /** Writes the settings atomically and notifies {@link Change#SAVED}; returns success. */
    public boolean save() {
        boolean ok = write();
        fire(Change.SAVED);
        return ok;
    }

    private boolean write() {
        Settings out = settings.copy();
        for (DroneModelConfig m : out.models.values()) {
            sanitizeCalibration(m.normalize().channels);
            if (m.preset) {
                m.build = null; // presets always use the built-in build (spec §10)
            }
        }
        try {
            Files.createDirectories(directory.toPath());
            Path target = file().toPath();
            Path tmp = directory.toPath().resolve(FILE_NAME + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(out, w);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not save {}", file(), e);
            return false;
        }
    }

    private void backupCorrupt(File f) {
        String stamp = LocalDateTime.now(clock).format(BACKUP_STAMP);
        File backup = new File(directory, FILE_NAME + ".corrupt-" + stamp);
        for (int n = 1; backup.exists(); n++) {
            backup = new File(directory, FILE_NAME + ".corrupt-" + stamp + "-" + n);
        }
        try {
            Files.move(f.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOG.warn("Corrupt settings backed up to {}", backup);
        } catch (IOException e) {
            LOG.error("Could not back up corrupt settings file {}", f, e);
        }
    }

    /** Keeps a copy of a pre-migration file (first one wins; failures are only logged). */
    private void backupLegacy(File f) {
        File backup = new File(directory, FILE_NAME + LEGACY_BACKUP_SUFFIX);
        if (backup.exists()) {
            return;
        }
        try {
            Files.copy(f.toPath(), backup.toPath());
        } catch (IOException e) {
            LOG.warn("Could not back up {} before migrating it: {}", f, e.toString());
        }
    }

    // =============================================================================================
    // Parsing and repair

    /** Parses file text; throws on structural corruption, drops malformed model entries. */
    private Settings parse(String text) {
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) {
            throw new JsonParseException("top level is not an object");
        }
        JsonObject obj = root.getAsJsonObject();
        Settings s = new Settings();
        // Every build writes schemaVersion; a file without it predates schema 2.
        s.schemaVersion = obj.has("schemaVersion") ? obj.get("schemaVersion").getAsInt() : 1;
        if (obj.has("currentModel") && !obj.get("currentModel").isJsonNull()) {
            s.currentModel = obj.get("currentModel").getAsString();
        }
        if (obj.has("firstTimeSetup")) {
            JsonElement e = obj.get("firstTimeSetup");
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
                throw new JsonParseException("firstTimeSetup is not a boolean");
            }
            s.firstTimeSetup = e.getAsBoolean();
        }
        if (obj.has("models") && !obj.get("models").isJsonNull()) {
            JsonElement models = obj.get("models");
            if (!models.isJsonObject()) {
                throw new JsonParseException("models is not an object");
            }
            for (Map.Entry<String, JsonElement> e : models.getAsJsonObject().entrySet()) {
                String key = e.getKey();
                DroneModelConfig m = parseModel(key, e.getValue());
                if (m != null) {
                    s.models.put(key, m);
                }
            }
        }
        return s;
    }

    private DroneModelConfig parseModel(String key, JsonElement json) {
        if (key == null || key.isBlank()) {
            LOG.warn("Dropping settings model with a blank name");
            return null;
        }
        try {
            if (!json.isJsonObject()) {
                throw new JsonParseException("entry is not an object");
            }
            DroneModelConfig m = gson.fromJson(json, DroneModelConfig.class);
            if (m == null) {
                throw new JsonParseException("entry is null");
            }
            m.name = key;
            sanitizeCalibration(m.normalize().channels);
            return m;
        } catch (RuntimeException e) {
            LOG.warn("Dropping corrupt settings model '{}': {}", key, e.toString());
            return null;
        }
    }

    /** Non-finite calibration bounds (which JSON cannot hold) reset to the [−1, 1] default. */
    private static void sanitizeCalibration(ChannelMap c) {
        if (c == null || c.calMin == null || c.calMax == null) {
            return;
        }
        for (int i = 0; i < c.calMin.length; i++) {
            if (!Float.isFinite(c.calMin[i])) {
                c.calMin[i] = -1f;
            }
        }
        for (int i = 0; i < c.calMax.length; i++) {
            if (!Float.isFinite(c.calMax[i])) {
                c.calMax[i] = 1f;
            }
        }
    }

    /**
     * Schema 1 → 2. Schema 1 split the 5-inch build into three presets that differed only in their
     * controller defaults ("5in Radio", "5in Gamepad", "5in Keyboard"); schema 2 has the two presets
     * of the spec, "5 Inch 4S" and "Tiny Whoop", with the controller scheme as a plain per-model
     * setting. Rules:
     * <ol>
     *   <li>An entry named like a schema-2 preset that was not a schema-1 preset (i.e. a user model
     *       called "5 Inch 4S") is a user model: it is renamed with a numeric suffix ("5 Inch 4S 2")
     *       and keeps its position, build and settings; {@code currentModel} follows it.</li>
     *   <li>The legacy 5-inch presets are dropped as models. The settings of one of them (the current
     *       model if it was a legacy preset, else "5in Radio", else the first one present) are carried
     *       onto "5 Inch 4S" (controller, channels, calibration, rates, display, camera, flight
     *       flags), so calibration is not lost. The build is the built-in 5-inch build as before.</li>
     *   <li>{@code currentModel} "5in *" becomes "5 Inch 4S". "Tiny Whoop" (a preset in both
     *       schemas) and user models are untouched.</li>
     * </ol>
     * Logs one summary line; {@link #load()} then rewrites the file as schema 2.
     */
    static void migrateV1(Settings s) {
        int from = s.schemaVersion;
        LinkedHashMap<String, DroneModelConfig> in = s.models == null ? new LinkedHashMap<>() : s.models;
        String current = s.currentModel;
        List<String> notes = new ArrayList<>();

        Set<String> taken = new HashSet<>(in.keySet());
        LinkedHashMap<String, DroneModelConfig> out = new LinkedHashMap<>();
        for (Map.Entry<String, DroneModelConfig> e : in.entrySet()) {
            String key = e.getKey();
            if (Presets.isPreset(key) && !Presets.LEGACY_V1_NAMES.contains(key)) {
                String renamed = freeName(key, taken);
                taken.add(renamed);
                notes.add("user model '" + key + "' renamed to '" + renamed + "'");
                if (key.equals(current)) {
                    current = renamed;
                }
                key = renamed;
            }
            if (e.getValue() != null) {
                e.getValue().name = key;
            }
            out.put(key, e.getValue());
        }

        String carryFrom = null;
        if (Presets.LEGACY_FIVE_INCH.contains(current) && out.get(current) != null) {
            carryFrom = current;
        } else {
            for (String legacy : Presets.LEGACY_FIVE_INCH) {
                if (out.get(legacy) != null) {
                    carryFrom = legacy;
                    break;
                }
            }
        }
        DroneModelConfig carried = carryFrom == null ? null : out.get(carryFrom);
        for (String legacy : Presets.LEGACY_FIVE_INCH) {
            if (out.containsKey(legacy)) {
                out.remove(legacy);
                notes.add("dropped legacy preset '" + legacy + "'");
            }
        }
        if (carried != null) {
            carried.name = Presets.FIVE_INCH;
            carried.preset = true;
            carried.build = Presets.builtInBuild(Presets.FIVE_INCH);
            LinkedHashMap<String, DroneModelConfig> withFive = new LinkedHashMap<>();
            withFive.put(Presets.FIVE_INCH, carried);
            withFive.putAll(out);
            out = withFive;
            notes.add("settings of '" + carryFrom + "' carried onto '" + Presets.FIVE_INCH + "'");
        }
        if (Presets.LEGACY_FIVE_INCH.contains(current)) {
            notes.add("current model '" + current + "' -> '" + Presets.FIVE_INCH + "'");
            current = Presets.FIVE_INCH;
        }
        s.models = out;
        s.currentModel = current;
        s.schemaVersion = Settings.SCHEMA_VERSION;
        LOG.info("Migrated settings from schema {} to {}: {}", from, Settings.SCHEMA_VERSION,
                notes.isEmpty() ? "nothing to change" : String.join("; ", notes));
    }

    /** {@code base + " n"} (n ≥ 2, within {@link #MAX_NAME_LENGTH}) that is neither taken nor a preset. */
    private static String freeName(String base, Set<String> taken) {
        String stem = base.length() > MAX_NAME_LENGTH - 4 ? base.substring(0, MAX_NAME_LENGTH - 4).trim() : base;
        for (int i = 2; ; i++) {
            String candidate = stem + " " + i;
            if (!taken.contains(candidate) && !Presets.isPreset(candidate)) {
                return candidate;
            }
        }
    }

    /** Presets present and protected, names consistent, current model valid. */
    private static void repair(Settings s) {
        if (s.models == null) {
            s.models = new LinkedHashMap<>();
        }
        LinkedHashMap<String, DroneModelConfig> ordered = new LinkedHashMap<>();
        for (String name : Presets.names()) {
            DroneModelConfig stored = s.models.get(name);
            DroneModelConfig m = stored != null ? stored : Presets.create(name);
            m.name = name;
            m.preset = true;
            m.build = Presets.builtInBuild(name);
            ordered.put(name, m.normalize());
        }
        for (Map.Entry<String, DroneModelConfig> e : s.models.entrySet()) {
            if (Presets.isPreset(e.getKey()) || e.getValue() == null) {
                continue;
            }
            DroneModelConfig m = e.getValue();
            m.name = e.getKey();
            m.preset = false;
            ordered.put(e.getKey(), m.normalize());
        }
        s.models = ordered;
        if (s.currentModel == null || !s.models.containsKey(s.currentModel)) {
            s.currentModel = Presets.defaultModel();
        }
        s.schemaVersion = Settings.SCHEMA_VERSION;
    }

    /** Fresh settings: every preset, the default one selected, wizard pending. */
    public static Settings defaults() {
        Settings s = new Settings();
        repair(s);
        return s;
    }

    // =============================================================================================
    // Queries

    /** The live settings object (read-mostly; prefer the mutators below for structural changes). */
    public Settings settings() {
        return settings;
    }

    /** The active model (live object, never {@code null}). */
    public DroneModelConfig current() {
        DroneModelConfig m = settings.current();
        if (m == null) {
            repair(settings);
            m = settings.current();
        }
        return m;
    }

    public String currentName() {
        return settings.currentModel;
    }

    /** The live model with this name, or {@code null}. */
    public DroneModelConfig model(String name) {
        return settings.models.get(name);
    }

    /** Model names in display order (presets first). */
    public List<String> modelNames() {
        return new ArrayList<>(settings.models.keySet());
    }

    public boolean isFirstTimeSetup() {
        return settings.firstTimeSetup;
    }

    /** Validates a candidate name for a new or renamed user model ({@code null}/blank → EMPTY). */
    public NameCheck checkName(String candidate) {
        String n = candidate == null ? "" : candidate.trim();
        if (n.isEmpty()) {
            return NameCheck.EMPTY;
        }
        if (n.length() > MAX_NAME_LENGTH) {
            return NameCheck.TOO_LONG;
        }
        if (Presets.isPreset(n)) {
            return NameCheck.RESERVED;
        }
        if (settings.models.containsKey(n)) {
            return NameCheck.DUPLICATE;
        }
        return NameCheck.OK;
    }

    // =============================================================================================
    // Mutators (memory only; call save())

    public void setFirstTimeSetup(boolean firstTimeSetup) {
        if (settings.firstTimeSetup != firstTimeSetup) {
            settings.firstTimeSetup = firstTimeSetup;
            fire(Change.FLAGS);
        }
    }

    /** Makes {@code name} the active model; returns false if no such model. */
    public boolean select(String name) {
        if (name == null || !settings.models.containsKey(name)) {
            return false;
        }
        if (!name.equals(settings.currentModel)) {
            settings.currentModel = name;
            fire(Change.SELECTED);
        }
        return true;
    }

    /**
     * Inserts or replaces the model under {@code model.name} (a deep copy is stored). Replacing a
     * preset keeps it a preset with its built-in build; a new model must pass {@link #checkName}.
     * Returns the stored (live) model, or {@code null} if rejected.
     */
    public DroneModelConfig put(DroneModelConfig model) {
        if (model == null || model.name == null) {
            return null;
        }
        String name = model.name.trim();
        DroneModelConfig m = model.copy();
        m.name = name;
        if (Presets.isPreset(name)) {
            m.preset = true;
            m.build = Presets.builtInBuild(name);
        } else {
            m.preset = false;
            if (!settings.models.containsKey(name) && checkName(name) != NameCheck.OK) {
                return null;
            }
        }
        m.normalize();
        settings.models.put(name, m);
        fire(Change.MODELS);
        return m;
    }

    /**
     * "New" in the model list (PLAN §10 c): deep copy of the <em>current</em> model under
     * {@code newName}, never a preset. Does not select it. Returns the new live model or
     * {@code null} if the name is invalid.
     */
    public DroneModelConfig cloneCurrent(String newName) {
        return cloneModel(settings.currentModel, newName);
    }

    /** Deep copy of model {@code source} under {@code newName}; {@code null} if invalid. */
    public DroneModelConfig cloneModel(String source, String newName) {
        DroneModelConfig src = settings.models.get(source);
        if (src == null || checkName(newName) != NameCheck.OK) {
            return null;
        }
        DroneModelConfig m = src.copyAs(newName.trim());
        m.normalize();
        settings.models.put(m.name, m);
        fire(Change.MODELS);
        return m;
    }

    /**
     * Deletes a user model; presets are protected (returns false). Deleting the active model selects
     * the default preset.
     */
    public boolean delete(String name) {
        if (name == null || Presets.isPreset(name) || !settings.models.containsKey(name)) {
            return false;
        }
        settings.models.remove(name);
        if (name.equals(settings.currentModel)) {
            settings.currentModel = Presets.defaultModel();
        }
        fire(Change.MODELS);
        return true;
    }

    /** Renames a user model keeping its position; presets cannot be renamed. */
    public boolean rename(String oldName, String newName) {
        if (oldName == null || Presets.isPreset(oldName) || !settings.models.containsKey(oldName)) {
            return false;
        }
        String n = newName == null ? "" : newName.trim();
        if (n.equals(oldName)) {
            return true;
        }
        if (checkName(n) != NameCheck.OK) {
            return false;
        }
        LinkedHashMap<String, DroneModelConfig> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, DroneModelConfig> e : settings.models.entrySet()) {
            if (e.getKey().equals(oldName)) {
                DroneModelConfig m = e.getValue();
                m.name = n;
                renamed.put(n, m);
            } else {
                renamed.put(e.getKey(), e.getValue());
            }
        }
        settings.models = renamed;
        if (oldName.equals(settings.currentModel)) {
            settings.currentModel = n;
        }
        fire(Change.MODELS);
        return true;
    }

    /**
     * Restores a preset's controller/rate/display settings to its defaults (keeping nothing but the
     * name); returns false for non-presets.
     */
    public boolean resetPreset(String name) {
        DroneModelConfig fresh = Presets.create(name);
        if (fresh == null) {
            return false;
        }
        settings.models.put(name, fresh);
        fire(Change.MODELS);
        return true;
    }

    // =============================================================================================
    // Listeners

    public void addListener(Listener l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire(Change change) {
        DroneModelConfig cur = current();
        for (Listener l : listeners) {
            try {
                l.onSettingsChanged(change, cur);
            } catch (RuntimeException e) {
                LOG.error("Settings listener failed on {}", change, e);
            }
        }
    }
}
