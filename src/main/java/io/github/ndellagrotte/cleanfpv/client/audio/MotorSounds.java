package io.github.ndellagrotte.cleanfpv.client.audio;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsConfig;
import io.github.ndellagrotte.cleanfpv.client.render.RemoteDroneView;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns every {@link MotorSound}: the local pilot's and one per armed remote pilot (the orchestrator
 * decided remote drones are audible too; spec §7 originally had them silent). Reconciled once per
 * client tick by {@link #tick()} against {@link ClientDroneContext#isArmed()} and
 * {@link ClientDroneContext#remoteDrones()}, so starting/stopping needs no explicit call; the
 * public {@link #startLocal()}/{@link #stopLocal()}/{@link #stopAll()} let orchestration act on the
 * exact arm/disarm/disconnect moment.
 *
 * <p>The local sound starts on arm regardless of an open GUI (PLAN §6.5). A sound the engine
 * dropped (sound-system reload, category muted when it started) is restarted after
 * {@link #RESTART_AFTER_TICKS} ticks of not playing; there is never more than one sound per pilot.
 *
 * <p>Client thread only.
 */
public final class MotorSounds {

    /** Ticks a started sound may report "not playing" before it is replaced. */
    public static final int RESTART_AFTER_TICKS = 40;

    private static final double FALLBACK_OMEGA_MAX = DroneBuild.fiveInch().noLoadOmega();

    private static Slot local;
    private static final Map<UUID, Slot> REMOTE = new HashMap<>();
    private static final Set<UUID> SEEN = new HashSet<>();

    private MotorSounds() {}

    // =============================================================================================
    // Public API

    /** Starts the local motor sound now unless one is already running (call at arm; the next tick stops it again if not armed). */
    public static void startLocal() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null && local == null) {
            local = new Slot(localSound(mc.player));
            local.play(mc.getSoundHandler());
        }
    }

    /** Stops the local motor sound (disarm). */
    public static void stopLocal() {
        if (local != null) {
            local.stop(Minecraft.getMinecraft().getSoundHandler());
            local = null;
        }
    }

    /** Stops every motor sound (disconnect, world unload). */
    public static void stopAll() {
        stopLocal();
        SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
        for (Slot slot : REMOTE.values()) {
            slot.stop(handler);
        }
        REMOTE.clear();
    }

    // =============================================================================================
    // Per-tick reconcile

    /** Call once per client tick (END). */
    static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            stopAll();
            return;
        }
        SoundHandler handler = mc.getSoundHandler();
        ClientDroneContext ctx = ClientDroneContext.get();

        if (ctx.isArmed()) {
            if (local != null && (local.sound.entity() != mc.player || local.stale(handler))) {
                stopLocal();
            }
            startLocal();
        } else {
            stopLocal();
        }

        SEEN.clear();
        for (RemoteDroneView view : ctx.remoteDrones().all()) {
            UUID id = view.playerId();
            EntityPlayer pilot = id != null ? mc.world.getPlayerEntityByUUID(id) : null;
            if (pilot == null || pilot == mc.player || pilot.isDead) {
                continue;
            }
            SEEN.add(id);
            Slot slot = REMOTE.get(id);
            if (slot != null && (slot.sound.entity() != pilot || slot.stale(handler))) {
                slot.stop(handler);
                slot = null;
            }
            if (slot == null) {
                slot = new Slot(remoteSound(pilot, id));
                slot.play(handler);
                REMOTE.put(id, slot);
            }
        }
        Iterator<Map.Entry<UUID, Slot>> it = REMOTE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Slot> e = it.next();
            if (!SEEN.contains(e.getKey())) {
                e.getValue().stop(handler);
                it.remove();
            }
        }
    }

    // =============================================================================================
    // Sound factories

    private static MotorSound localSound(Entity player) {
        ClientDroneContext ctx = ClientDroneContext.get();
        return new MotorSound(player,
                () -> ctx.droneState().omega[0],
                () -> {
                    PhysicsConfig pc = ctx.physicsConfig();
                    DroneBuild build = pc != null && pc.build() != null ? pc.build() : ctx.activeModel().build;
                    return build != null ? build.noLoadOmega() : FALLBACK_OMEGA_MAX;
                });
    }

    private static MotorSound remoteSound(Entity pilot, UUID id) {
        ClientDroneContext ctx = ClientDroneContext.get();
        return new MotorSound(pilot,
                () -> {
                    RemoteDroneView v = ctx.remoteDrones().get(id);
                    return v != null ? v.omega(0) : 0.0;
                },
                () -> {
                    RemoteDroneView v = ctx.remoteDrones().get(id);
                    DroneBuild build = v != null ? v.build() : null;
                    return build != null ? build.noLoadOmega() : FALLBACK_OMEGA_MAX;
                });
    }

    /** One sound plus its "engine dropped it" watchdog. */
    private static final class Slot {
        final MotorSound sound;
        int silentTicks;

        Slot(MotorSound sound) {
            this.sound = sound;
        }

        void play(SoundHandler handler) {
            handler.playSound(sound);
        }

        void stop(SoundHandler handler) {
            sound.finish();
            handler.stopSound(sound);
        }

        /** True once the sound ended or the engine has not been playing it for a while. */
        boolean stale(SoundHandler handler) {
            if (sound.isFinished()) {
                return true;
            }
            silentTicks = handler.isSoundPlaying(sound) ? 0 : silentTicks + 1;
            return silentTicks >= RESTART_AFTER_TICKS;
        }
    }
}
