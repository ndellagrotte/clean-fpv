package io.github.ndellagrotte.cleanfpv.server.net;

import io.github.ndellagrotte.cleanfpv.CleanFpv;
import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.ArmedState;
import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.ServerPacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformC2S;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformS2C;
import io.github.ndellagrotte.cleanfpv.server.DroneServer;
import io.github.ndellagrotte.cleanfpv.server.ServerConfig;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side owner-packet behaviour (PLAN §6.6): validate, apply abilities/size/step/roll, store
 * in {@code ArmRegistry.SERVER}, relay the S2C form with {@code Channel.sendToAllTracking}.
 *
 * <p>Rate limits: relays are coalesced to at most one Transform per pilot per server tick (the
 * latest valid one, applied and relayed by {@link #flush} from the pilot's {@code PlayerTickEvent}
 * END), Builds are relayed only when they changed and at most every
 * {@value #BUILD_RELAY_INTERVAL_MS} ms (the latest always goes out), and more than
 * {@value #MAX_TRANSFORMS_PER_TICK} Transforms per tick from one player are dropped unvalidated, so a
 * modified client cannot make the server fan out unbounded traffic.
 *
 * <p>Relay targets are the players tracking the sender, which never include the sender itself
 * (api-notes D8), so owners receive no echoes; clients still ignore their own UUID.
 *
 * <p>Also subscribes to {@code PlayerEvent.StartTracking}: a player who starts tracking an armed
 * pilot (login nearby, walking into range, respawn) gets that pilot's Arm + Build + last Transform
 * so late joiners see the drone immediately. The event fires after the spawn packet
 * ({@code EntityTrackerEntry.updatePlayerEntity}), so the client already has the entity.
 *
 * <p>Owner: (D) net+server. Installed by {@code CommonProxy.init} both as the
 * {@link ServerPacketHandler} and on {@code MinecraftForge.EVENT_BUS}. Runs on the server thread;
 * loaded on both physical sides, so it must not reference client classes.
 */
public final class ServerNetHandler implements ServerPacketHandler {

    /** At most one rejected-transform log line per player per this many ms. */
    static final long LOG_INTERVAL_MS = 10_000L;

    /**
     * Transforms accepted for validation per player per server tick. An honest client sends one
     * per tick; the margin covers packets bunched by a ~2 s stall (only the latest matters anyway).
     */
    static final int MAX_TRANSFORMS_PER_TICK = 40;
    /** Minimum interval between Build relays of one pilot (tilt changes re-send the Build). */
    static final long BUILD_RELAY_INTERVAL_MS = 250L;

    /** Per-player rejected-transform log throttle (server thread only). */
    private static final Map<UUID, RejectLog> REJECT_LOGS = new HashMap<>();
    /** Per-player relay coalescing and inbound budget (server thread only). */
    private static final Map<UUID, Relay> RELAYS = new HashMap<>();

    private static final class Relay {
        TransformSnapshot pendingTransform;
        boolean buildDirty;
        long lastBuildRelayMs = Long.MIN_VALUE / 2;
        int budgetTick = Integer.MIN_VALUE;
        int budgetCount;
    }

    private static final class RejectLog {
        long lastLogMs;
        int suppressed;
    }

    @Override
    public void onArm(EntityPlayerMP sender, ArmC2S message) {
        if (message.armed()) {
            if (!sender.isEntityAlive()) {
                // A dead player cannot fly; tell the client so it does not stay armed on its own.
                Channel.sendTo(new ArmS2C(sender.getUniqueID(), false), sender);
                return;
            }
            DroneServer.arm(sender);
        } else {
            DroneServer.disarm(sender);
        }
    }

    @Override
    public void onBuild(EntityPlayerMP sender, BuildC2S message) {
        DroneBuild build = message.build();
        if (build == null) {
            return;
        }
        // DroneBuild.read already sanitizes; do it again so the stored copy never depends on that.
        build = build.copy().sanitize();
        UUID id = sender.getUniqueID();
        ArmedState state = ArmRegistry.SERVER.get(id);
        DroneBuild previous = state != null ? state.build() : null;
        ArmRegistry.SERVER.setBuild(id, build);
        if (ArmRegistry.SERVER.isArmed(id) && !build.equals(previous)) {
            relay(id).buildDirty = true; // relayed by flush(), rate-limited
        }
    }

    @Override
    public void onTransform(EntityPlayerMP sender, TransformC2S message) {
        TransformSnapshot t = message.transform();
        if (t == null) {
            return;
        }
        UUID id = sender.getUniqueID();
        Relay relay = relay(id);
        int tick = sender.getServer() != null ? sender.getServer().getTickCounter() : 0;
        if (relay.budgetTick != tick) {
            relay.budgetTick = tick;
            relay.budgetCount = 0;
        }
        if (++relay.budgetCount > MAX_TRANSFORMS_PER_TICK) {
            return;
        }
        ArmedState state = ArmRegistry.SERVER.get(id);
        boolean armed = state != null && state.armed();
        DroneBuild build = state != null ? state.build() : null;
        TransformRules.Verdict verdict = TransformRules.validate(armed, build, t, ServerConfig.maxSpeed());
        if (!verdict.accepted()) {
            if (verdict.suspicious()) {
                logRejected(sender, verdict, t, build);
            }
            return;
        }
        // Stored now (motion echo, late joiners); roll + relay once per tick in flush().
        ArmRegistry.SERVER.setTransform(id, t);
        relay.pendingTransform = t;
    }

    /**
     * Once per armed pilot per server tick ({@code PlayerTickEvent} END): applies the roll of and
     * relays the latest accepted Transform, and relays a changed Build when its interval allows.
     */
    public static void flush(EntityPlayerMP pilot) {
        UUID id = pilot.getUniqueID();
        Relay relay = RELAYS.get(id);
        ArmedState state = ArmRegistry.SERVER.get(id);
        if (relay == null || state == null || !state.armed()) {
            return;
        }
        DroneBuild build = state.build();
        TransformSnapshot t = relay.pendingTransform;
        relay.pendingTransform = null;
        if (t != null && build != null) {
            pilot.setRoll(TransformRules.cameraRollDeg(t.qx, t.qy, t.qz, t.qw, build.cameraAngle));
            Channel.sendToAllTracking(new TransformS2C(id, t), pilot);
        }
        long now = System.currentTimeMillis();
        if (relay.buildDirty && build != null && now - relay.lastBuildRelayMs >= BUILD_RELAY_INTERVAL_MS) {
            relay.buildDirty = false;
            relay.lastBuildRelayMs = now;
            Channel.sendToAllTracking(new BuildS2C(id, build), pilot);
        }
    }

    /** Drops queued relays (disarm: nothing may be relayed after {@code Arm(false)}). */
    public static void dropPending(UUID playerId) {
        Relay relay = RELAYS.get(playerId);
        if (relay != null) {
            relay.pendingTransform = null;
            relay.buildDirty = false;
        }
    }

    private static Relay relay(UUID id) {
        return RELAYS.computeIfAbsent(id, k -> new Relay());
    }

    /** Late joiners: send the tracked pilot's current drone state to the new tracker. */
    @SubscribeEvent
    public void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof EntityPlayerMP pilot
                && event.getEntityPlayer() instanceof EntityPlayerMP viewer
                && pilot != viewer) {
            DroneServer.sendStateTo(viewer, pilot);
        }
    }

    /** Drops the per-player entries of a player who left (called from {@code ServerEvents}). */
    public static void forget(UUID playerId) {
        REJECT_LOGS.remove(playerId);
        RELAYS.remove(playerId);
    }

    private static void logRejected(EntityPlayerMP sender, TransformRules.Verdict verdict,
                                    TransformSnapshot t, DroneBuild build) {
        long now = System.currentTimeMillis();
        RejectLog log = REJECT_LOGS.computeIfAbsent(sender.getUniqueID(), k -> new RejectLog());
        if (now - log.lastLogMs < LOG_INTERVAL_MS) {
            log.suppressed++;
            return;
        }
        CleanFpv.LOGGER.warn("Dropped drone transform from {}: {} (speed {} m/s, cap {}; max|ω| {} rad/s, limit {}); "
                        + "{} more dropped since the last report",
                sender.getName(), verdict, (float) t.speed(), ServerConfig.maxSpeed(), t.maxAbsOmega(),
                build != null ? (float) TransformRules.omegaLimit(build) : Float.NaN, log.suppressed);
        log.lastLogMs = now;
        log.suppressed = 0;
    }
}
