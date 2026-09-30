package io.github.ndellagrotte.cleanfpv.server;

import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.ArmedState;
import io.github.ndellagrotte.cleanfpv.common.PlayerSizing;
import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformS2C;
import io.github.ndellagrotte.cleanfpv.server.net.ServerNetHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.GameType;

/**
 * Server-side arm/disarm effects shared by the packet handler and the lifecycle hooks (PLAN §5
 * rows Abilities / Entity size, §6.6, §6.9). Static helpers; server thread only; state lives in
 * {@link ArmRegistry#SERVER}.
 *
 * <p>Persistence guard: abilities are saved in player NBT, and a server stop saves players
 * <em>before</em> logging them out, so an armed pilot could be saved with {@code allowFlying}. While
 * armed the player carries the {@link #ARMED_FLIGHT_TAG} marker in its Forge entity data; on the next
 * login {@link #clearStaleFlight} restores the game-mode abilities and drops the marker.
 */
public final class DroneServer {

    /** Marker in {@code Entity.getEntityData()} (persisted as ForgeData) while flight is ours. */
    public static final String ARMED_FLIGHT_TAG = "cleanfpv:armedFlight";
    /**
     * Server {@code isFlying} when the pilot armed, next to {@link #ARMED_FLIGHT_TAG} (persisted too,
     * so the stale-flight path after a restart restores it). Creative restore needs it:
     * {@code GameType.configurePlayerCapabilities} never writes {@code isFlying} for CREATIVE.
     */
    public static final String FLYING_AT_ARM_TAG = "cleanfpv:flyingAtArm";

    private DroneServer() {}

    // ---------------------------------------------------------------------------------------------
    // Arm / disarm

    /**
     * Arms {@code player}: registry, flight abilities, drone size, zero roll, relay to trackers
     * (plus the stored build, if the client sent one before the arm). No-op when already armed.
     *
     * @return whether the state changed
     */
    public static boolean arm(EntityPlayerMP player) {
        if (!ArmRegistry.SERVER.setArmed(player.getUniqueID(), true)) {
            return false;
        }
        // Before grantFlight: reassert() also grants flight, by which time isFlying is already true.
        player.getEntityData().setBoolean(FLYING_AT_ARM_TAG, player.capabilities.isFlying);
        grantFlight(player);
        PlayerSizing.shrink(player);
        player.setRoll(0f);
        player.fallDistance = 0f;
        Channel.sendToAllTracking(new ArmS2C(player.getUniqueID(), true), player);
        ArmedState state = ArmRegistry.SERVER.get(player.getUniqueID());
        if (state != null && state.build() != null) {
            Channel.sendToAllTracking(new BuildS2C(player.getUniqueID(), state.build()), player);
        }
        return true;
    }

    /**
     * Owner-requested disarm: keeps the registry entry (and its build) but clears the armed flag,
     * restores abilities/size/roll and relays {@code Arm(false)}. No-op when not armed.
     *
     * @return whether the state changed
     */
    public static boolean disarm(EntityPlayerMP player) {
        if (!ArmRegistry.SERVER.isArmed(player.getUniqueID())) {
            return false;
        }
        ArmRegistry.SERVER.setArmed(player.getUniqueID(), false);
        ServerNetHandler.dropPending(player.getUniqueID());
        restore(player);
        Channel.sendToAllTracking(new ArmS2C(player.getUniqueID(), false), player);
        return true;
    }

    /**
     * Lifecycle disarm (PLAN §6.9 server column, D3): removes the registry entry, restores the
     * player, and if it was armed relays {@code Arm(false)} to trackers and (when
     * {@code notifySelf}) to the player itself, whose client then disarms with a full restore.
     * Safe to call for players that were never armed (then only the stale-flight marker is handled).
     *
     * @return whether the player was armed
     */
    public static boolean forceDisarm(EntityPlayerMP player, boolean notifySelf) {
        ArmedState removed = ArmRegistry.SERVER.remove(player.getUniqueID());
        ServerNetHandler.dropPending(player.getUniqueID());
        boolean wasArmed = removed != null && removed.armed();
        if (wasArmed) {
            restore(player);
            ArmS2C off = new ArmS2C(player.getUniqueID(), false);
            Channel.sendToAllTracking(off, player);
            if (notifySelf && player.connection != null) {
                Channel.sendTo(off, player);
            }
        } else {
            clearStaleFlight(player);
        }
        return wasArmed;
    }

    // ---------------------------------------------------------------------------------------------
    // Per-tick re-assert (PlayerTickEvent END)

    /**
     * Re-applies drone size/eye/step and flight abilities; vanilla {@code updateSize} undoes size.
     * Also clears {@code isInWeb}: vanilla's regrow in {@code updateSize} runs a server-side
     * {@code move} on a 0.6×1.8 box (api-notes D10) whose block collisions can set it from webs the
     * drone never touched, and the flag would then scale the next {@code processPlayer} replay by
     * 0.25 ("moved wrongly"). The client does not model web slow-down (Sweep).
     */
    public static void reassert(EntityPlayerMP player) {
        PlayerSizing.shrink(player);
        player.isInWeb = false;
        PlayerCapabilities caps = player.capabilities;
        if (!caps.allowFlying || !caps.isFlying) {
            grantFlight(player);
        }
    }

    /**
     * Ticks of travel the motion echo covers. {@code processPlayer} measures every packet of a tick's
     * batch from the position captured once at tick start ({@code firstGood}), so after a stall of
     * {@code k} ticks the k-th packet claims about {@code (k·Δ)²} while one tick of echo covers only
     * {@code Δ²}; with the per-tick allowance reset to 100 for {@code k > 5}, a 300 ms hitch failed at
     * ~34 m/s. Echoing {@code ECHO_TICKS} ticks of travel tolerates bunches of up to ~1 s.
     */
    static final double ECHO_TICKS = 20.0;

    /**
     * Motion echo (PLAN §6.6), END half: the server's motion becomes the pilot's last reported
     * velocity times {@link #ECHO_TICKS} ticks ({@code v/20·ECHO_TICKS}), so {@code processPlayer}'s
     * "moved too quickly" check ({@code claimed² − motion²}) tolerates packet bunching at the drone's
     * own speed. START zeroes it again.
     *
     * <p>The enlarged value is not observable: it is written after {@code travel()}, zeroed at the next
     * START before the server's own move, and the player tracker does not send player velocity.
     * Trade-off: while armed this effectively disables vanilla's speed check for the pilot; that is
     * acceptable because the server already trusts the (speed-validated) client Transform.
     */
    public static void writeMotionEcho(EntityPlayerMP player, TransformSnapshot transform) {
        if (transform != null) {
            double s = ECHO_TICKS / 20.0;
            player.motionX = transform.vx * s;
            player.motionY = transform.vy * s;
            player.motionZ = transform.vz * s;
        } else {
            zeroMotion(player);
        }
        player.fallDistance = 0f;
    }

    /** Motion echo, START half: the server's own {@code travel()} must not displace the player. */
    public static void zeroMotion(EntityPlayerMP player) {
        player.motionX = 0.0;
        player.motionY = 0.0;
        player.motionZ = 0.0;
    }

    // ---------------------------------------------------------------------------------------------
    // Late joiners

    /** Sends {@code pilot}'s current drone state to {@code viewer} (StartTracking / login). */
    public static void sendStateTo(EntityPlayerMP viewer, EntityPlayerMP pilot) {
        ArmedState state = ArmRegistry.SERVER.get(pilot.getUniqueID());
        if (state == null || !state.armed() || viewer.connection == null) {
            return;
        }
        Channel.sendTo(new ArmS2C(pilot.getUniqueID(), true), viewer);
        if (state.build() != null) {
            Channel.sendTo(new BuildS2C(pilot.getUniqueID(), state.build()), viewer);
        }
        if (state.transform() != null) {
            Channel.sendTo(new TransformS2C(pilot.getUniqueID(), state.transform()), viewer);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Abilities / restore

    private static void grantFlight(EntityPlayerMP player) {
        player.capabilities.allowFlying = true;
        player.capabilities.isFlying = true;
        player.getEntityData().setBoolean(ARMED_FLIGHT_TAG, true);
        if (player.connection != null) {
            player.sendPlayerAbilities();
        }
    }

    /**
     * Game-mode abilities (creative gets back its {@code isFlying} from arm time, matching the
     * client's {@code ArmRules.flyingAfterDisarm}), vanilla size/eye/step, zero roll, zero fall
     * distance (the armed descent accumulated by {@code processPlayer} since the last END must not
     * count toward the fall after disarm); drops the stale-flight markers.
     */
    public static void restore(EntityPlayerMP player) {
        NBTTagCompound data = player.getEntityData();
        GameType mode = player.interactionManager.getGameType();
        mode.configurePlayerCapabilities(player.capabilities);
        player.capabilities.isFlying = flyingAfterRestore(mode, data.getBoolean(FLYING_AT_ARM_TAG));
        data.removeTag(ARMED_FLIGHT_TAG);
        data.removeTag(FLYING_AT_ARM_TAG);
        if (player.connection != null) {
            player.sendPlayerAbilities();
        }
        PlayerSizing.restore(player);
        player.setRoll(0f);
        player.fallDistance = 0f;
    }

    /**
     * Server {@code isFlying} after a disarm: spectators fly, creative players get back what they
     * had at arm time (missing tag → false, i.e. fall), everyone else falls. Same rule as the
     * client's {@code ArmRules.flyingAfterDisarm}, so the abilities packet agrees with the client.
     */
    static boolean flyingAfterRestore(GameType mode, boolean flyingAtArm) {
        if (mode == GameType.SPECTATOR) {
            return true;
        }
        return mode == GameType.CREATIVE && flyingAtArm;
    }

    /**
     * If the player was saved while armed (server stopped mid-flight), restores its game-mode
     * abilities and removes the marker. Called on login and from {@link #forceDisarm}.
     */
    public static void clearStaleFlight(EntityPlayerMP player) {
        NBTTagCompound data = player.getEntityData();
        if (data.getBoolean(ARMED_FLIGHT_TAG)) {
            restore(player);
        }
    }
}
