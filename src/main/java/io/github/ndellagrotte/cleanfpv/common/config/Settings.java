package io.github.ndellagrotte.cleanfpv.common.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root of {@code config/cleanfpv/settings.json} (PLAN §6.7): {@code {models, currentModel,
 * firstTimeSetup}}. Data only; loading, saving, presets and backups belong to
 * {@code SettingsStore} (gui/config implementer).
 */
public class Settings {

    /**
     * Current JSON schema version (bump when a migration is needed). 1: first builds, with the
     * scheme-split "5in Radio/Gamepad/Keyboard" presets; 2: presets "5 Inch 4S" and "Tiny Whoop"
     * (migrated by {@code SettingsStore}).
     */
    public static final int SCHEMA_VERSION = 2;

    public int schemaVersion = SCHEMA_VERSION;
    /** Models by name, in insertion (display) order. */
    public LinkedHashMap<String, DroneModelConfig> models = new LinkedHashMap<>();
    /** Key of the active model in {@link #models}. */
    public String currentModel = "";
    /** True until the wizard completes (or "Returning Pilot" / Complete screen clears it). */
    public boolean firstTimeSetup = true;

    public Settings() {}

    /** The active model, or {@code null} if {@link #currentModel} names no entry. */
    public DroneModelConfig current() {
        return models == null ? null : models.get(currentModel);
    }

    /** Deep copy. */
    public Settings copy() {
        Settings s = new Settings();
        s.schemaVersion = schemaVersion;
        s.currentModel = currentModel;
        s.firstTimeSetup = firstTimeSetup;
        if (models != null) {
            for (Map.Entry<String, DroneModelConfig> e : models.entrySet()) {
                s.models.put(e.getKey(), e.getValue() == null ? null : e.getValue().copy());
            }
        }
        return s;
    }
}
