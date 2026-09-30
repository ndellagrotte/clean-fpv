package io.github.ndellagrotte.cleanfpv.client.gui;

import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import io.github.ndellagrotte.cleanfpv.common.config.SettingsStore;
import net.minecraft.client.Minecraft;

import java.io.File;

/**
 * The client's single {@link SettingsStore} (PLAN §6.7) and its link to
 * {@link ClientDroneContext#setActiveModel}: every store change (load, select, model edits, save)
 * re-publishes the live active model, so flight/input/render always read the current one.
 *
 * <p>Initialised by {@link GuiEvents}'s constructor (i.e. during {@code ClientProxy.init}, after
 * {@code CleanFpv.configDir()} is set in preInit), so the active model is correct before the first
 * world loads. Other subsystems that must react to saved settings (for example re-sending the Build
 * packet while armed, or refreshing the physics config) register a
 * {@link SettingsStore.Listener} via {@link #addListener} and act on
 * {@link SettingsStore.Change#SAVED} / {@link SettingsStore.Change#SELECTED}. Client thread only.
 */
public final class ClientSettings {

    private static SettingsStore store;
    private static boolean dirty;

    private ClientSettings() {}

    /** Creates and loads the store once; later calls are no-ops. */
    public static synchronized void init() {
        if (store != null) {
            return;
        }
        File dir = CleanFpv.configDir();
        if (dir == null) {
            dir = new File(new File(Minecraft.getMinecraft().gameDir, "config"), "cleanfpv");
        }
        store = new SettingsStore(dir);
        store.addListener((change, current) -> ClientDroneContext.get().setActiveModel(current));
        store.load();
        ClientDroneContext.get().setActiveModel(store.current());
    }

    /** The store (initialising it on first use). */
    public static SettingsStore store() {
        if (store == null) {
            init();
        }
        return store;
    }

    /** The live active model (never {@code null}). */
    public static DroneModelConfig current() {
        return store().current();
    }

    public static void addListener(SettingsStore.Listener listener) {
        store().addListener(listener);
    }

    /** Records that a live model field was edited in place; the next {@link #saveIfDirty} writes it. */
    public static void markDirty() {
        dirty = true;
    }

    /** Saves if anything was edited since the last save (called when a settings screen closes). */
    public static void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    /** Saves now (notifies {@code SAVED}) and clears the dirty flag. */
    public static void save() {
        dirty = false;
        store().save();
    }
}
