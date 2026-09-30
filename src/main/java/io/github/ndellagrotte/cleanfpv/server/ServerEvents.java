package io.github.ndellagrotte.cleanfpv.server;

import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.ArmedState;
import io.github.ndellagrotte.cleanfpv.common.net.Channel;
import io.github.ndellagrotte.cleanfpv.common.net.packet.HelloS2C;
import io.github.ndellagrotte.cleanfpv.server.net.ServerNetHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedOutEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerRespawnEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Forge event subscriber for server-side drone behaviour: Hello on login, size/abilities re-assert and motion echo on {@code PlayerTickEvent}, lifecycle cleanup (PLAN §6.6, §6.9). Loaded on both physical sides (integrated server): no client classes.
 *
 * <h2>Tick hooks (armed players, logical server only)</h2>
 * <ul>
 *   <li>{@code PlayerTickEvent} START (top of {@code EntityPlayer.onUpdate}): zero motion, so the
 *       server's own {@code travel()/move()} displaces nothing and triggers no block side effects.</li>
 *   <li>{@code PlayerTickEvent} END (last statement of {@code updateSize()}): re-shrink (vanilla
 *       just regrew the box), re-assert flight, then write the motion echo
 *       ({@code v/20·ECHO_TICKS}), then relay this tick's coalesced Transform/Build
 *       ({@link ServerNetHandler#flush}). The next tick's scheduled {@code processPlayer} tasks run
 *       before the entity update and see the echo.</li>
 * </ul>
 * Known limitation (api-notes D10, accepted): vanilla {@code updateSize()} regrows the box to
 * 0.6×1.8 whenever it fits and {@code Entity.setSize} then runs a server-side
 * {@code move(SELF, −0.4, 0, −0.4)} on the inflated box every armed tick; the position is reset
 * by {@code NetHandlerPlayServer.update()}, but block-collision side effects of that probe (pressure
 * plates, cactus, portals) can still happen. There is no mixin-free way to suppress it.
 *
 * <h2>Lifecycle (PLAN §6.9 server column, D3)</h2>
 * Logout: remove + restore (+ relay {@code Arm(false)} to trackers). Death ({@code LivingDeathEvent}
 * for an {@code EntityPlayerMP}, lowest priority, not when cancelled), respawn and dimension change:
 * remove + restore + relay {@code Arm(false)} to trackers <em>and</em> to the player (the client
 * never sees its own death event). Server stop: {@code CommonProxy.serverStopped} clears the
 * registry; the persisted-abilities guard is {@link DroneServer#clearStaleFlight} on login.
 *
 * <p>Owner: (D) net+server. Registered on {@code MinecraftForge.EVENT_BUS} by {@code CommonProxy}; add {@code @SubscribeEvent}
 * <em>instance</em> methods here (static subscribers are not picked up by instance registration).
 */
public final class ServerEvents {

    // ---------------------------------------------------------------------------------------------
    // Config / handshake

    /** Re-read {@code server.json} once per server start (overworld load on the logical server). */
    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!event.getWorld().isRemote && event.getWorld().provider.getDimension() == 0) {
            ServerConfig.reload();
        }
    }

    @SubscribeEvent
    public void onLoggedIn(PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP player)) {
            return;
        }
        // Registry state never survives a connection; a stale entry (e.g. a missed logout) goes too.
        ArmRegistry.SERVER.remove(player.getUniqueID());
        DroneServer.clearStaleFlight(player);
        Channel.sendTo(new HelloS2C(Channel.PROTOCOL_VERSION, ServerConfig.maxSpeed()), player);
    }

    // ---------------------------------------------------------------------------------------------
    // Per tick

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!(event.player instanceof EntityPlayerMP player) || player.world.isRemote) {
            return;
        }
        ArmedState state = ArmRegistry.SERVER.get(player.getUniqueID());
        if (state == null || !state.armed()) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            DroneServer.zeroMotion(player);
        } else {
            DroneServer.reassert(player);
            DroneServer.writeMotionEcho(player, state.transform());
            ServerNetHandler.flush(player);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle

    @SubscribeEvent
    public void onLoggedOut(PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            // Fires before PlayerList saves the player, so restored abilities are what gets saved.
            DroneServer.forceDisarm(player, false);
            ServerNetHandler.forget(player.getUniqueID());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (event.getEntityLiving() instanceof EntityPlayerMP player && !player.world.isRemote) {
            DroneServer.forceDisarm(player, true);
        }
    }

    @SubscribeEvent
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            DroneServer.forceDisarm(player, true);
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerChangedDimensionEvent event) {
        if (event.player instanceof EntityPlayerMP player) {
            DroneServer.forceDisarm(player, true);
        }
    }
}
