package io.github.ndellagrotte.cleanfpv.client.net;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.PlayerSizing;
import io.github.ndellagrotte.cleanfpv.common.TransformSnapshot;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import io.github.ndellagrotte.cleanfpv.common.net.ClientPacketHandler;
import io.github.ndellagrotte.cleanfpv.common.net.packet.ArmS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.BuildS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.HelloS2C;
import io.github.ndellagrotte.cleanfpv.common.net.packet.TransformS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.UUID;

/**
 * Client-side drone packet behaviour (PLAN §6.6): store Hello in {@code ClientDroneContext},
 * update {@code ArmRegistry.CLIENT} and {@link RemoteDrones} for other players (ignore own UUID),
 * and ask orchestration to disarm when the server disarms the local pilot. Also a Forge event
 * subscriber for remote-state lifecycle and per-frame remote interpolation.
 *
 * <h2>Packets</h2>
 * <ul>
 *   <li>Hello → {@code ClientDroneContext.setServerInfo}.</li>
 *   <li>Arm/Build/Transform for another player → {@code ArmRegistry.CLIENT} + {@link RemoteDrones}.
 *       A transform implies armed (the server relays transforms only for armed pilots).</li>
 *   <li>{@code Arm(false)} for the local player (server-side death, respawn, dimension change) →
 *       {@code ClientDroneContext.requestForcedDisarm()} while armed; {@code ArmController} (F)
 *       performs the disarm with full restore. Own Build/Transform echoes are ignored.</li>
 * </ul>
 *
 * <h2>Events</h2>
 * <ul>
 *   <li>{@code ClientDisconnectionFromServerEvent} (netty thread, may fire twice) → hops to the
 *       client thread: clears Hello (api-notes D5: only here), registry and remote state, requests
 *       a forced disarm if armed.</li>
 *   <li>{@code WorldEvent.Unload} (client world; also on every dimension change) → clears remote
 *       state and requests a forced disarm if armed; Hello is kept.</li>
 *   <li>{@code WorldEvent.Load} (client world) → installs an {@code onEntityRemoved} listener that
 *       drops a pilot whose entity leaves the world (D6: 1.12 has no leave event).</li>
 *   <li>{@code PlayerTickEvent} END for remote players → drone-size their entity while armed, so
 *       the nameplate and hitbox match the drone; restored on disarm.</li>
 *   <li>{@code RenderTickEvent} START (low priority, after F's frame counter) →
 *       {@link RemoteDrones#updateFrame}, once per frame.</li>
 *   <li>{@code ClientTickEvent} END → D3 backstop: an armed local player with {@code health ≤ 0}
 *       or {@code isDead} requests a forced disarm.</li>
 * </ul>
 *
 * <p>Owner: (D) net+server. Installed by {@code ClientProxy.init} both as the
 * {@link ClientPacketHandler} and on {@code MinecraftForge.EVENT_BUS} (instance methods only). The
 * constructor installs {@link RemoteDrones} into {@code ClientDroneContext}. All methods except
 * the disconnect hook run on the client thread.
 */
public final class ClientNetHandler implements ClientPacketHandler {

    private final RemoteDrones remoteDrones = new RemoteDrones();

    public ClientNetHandler() {
        ClientDroneContext.get().setRemoteDrones(remoteDrones);
    }

    /** The remote-pilot store this handler feeds (also installed in {@code ClientDroneContext}). */
    public RemoteDrones remoteDrones() {
        return remoteDrones;
    }

    // ---------------------------------------------------------------------------------------------
    // Packets

    @Override
    public void onHello(HelloS2C message) {
        ClientDroneContext.get().setServerInfo(message.protocol(), message.maxSpeed());
    }

    @Override
    public void onArm(ArmS2C message) {
        UUID id = message.playerId();
        if (id == null) {
            return;
        }
        if (isLocal(id)) {
            if (!message.armed()) {
                requestDisarmIfArmed();
            }
            return;
        }
        if (message.armed()) {
            ArmRegistry.CLIENT.setArmed(id, true);
            remoteDrones.onArmed(id);
        } else {
            dropRemote(id);
        }
    }

    @Override
    public void onBuild(BuildS2C message) {
        UUID id = message.playerId();
        DroneBuild build = message.build();
        if (id == null || build == null || isLocal(id)) {
            return;
        }
        ArmRegistry.CLIENT.setBuild(id, build);
        remoteDrones.onBuild(id, build);
    }

    @Override
    public void onTransform(TransformS2C message) {
        UUID id = message.playerId();
        TransformSnapshot t = message.transform();
        if (id == null || t == null || !t.isFinite() || isLocal(id)) {
            return;
        }
        ArmRegistry.CLIENT.setArmed(id, true);
        ArmRegistry.CLIENT.setTransform(id, t);
        remoteDrones.onTransform(id, t, System.currentTimeMillis());
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            ClientDroneContext.get().clearServerInfo();
            requestDisarmIfArmed();
            clearRemoteState();
        });
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        World world = event.getWorld();
        if (world.isRemote) {
            world.addEventListener(new EntityRemovalListener(this::onEntityRemoved));
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (event.getWorld().isRemote) {
            requestDisarmIfArmed();
            clearRemoteState();
        }
    }

    private void onEntityRemoved(net.minecraft.entity.Entity entity) {
        if (entity instanceof EntityPlayer player && !(entity instanceof EntityPlayerSP)) {
            UUID id = player.getUniqueID();
            // World.removeEntity drops the old entity from playerEntities before its first callback,
            // and the dead-entity purge calls back a second time a tick later with the same OLD
            // object. If a new live entity with this UUID exists by then (re-tracked, or the respawn
            // order), the callback is stale: dropping the UUID would lose the re-sent Build.
            EntityPlayer live = entity.world != null ? entity.world.getPlayerEntityByUUID(id) : null;
            if (live != null && live != entity) {
                return;
            }
            if (remoteDrones.contains(id) || ArmRegistry.CLIENT.get(id) != null) {
                ArmRegistry.CLIENT.remove(id);
                remoteDrones.remove(id);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Per tick / frame

    /** Remote pilots: drone-size their entity after vanilla's {@code updateSize()} regrew it. */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !event.player.world.isRemote
                || event.player instanceof EntityPlayerSP) {
            return;
        }
        if (ArmRegistry.CLIENT.isArmed(event.player.getUniqueID())) {
            PlayerSizing.shrink(event.player);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        remoteDrones.updateFrame(mc.world, mc.player != null ? mc.player.getUniqueID() : null,
                System.currentTimeMillis());
    }

    /** D3: the client never sees its own death event; treat a dead local player as a disarm. */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player != null && (player.getHealth() <= 0f || player.isDead)) {
            requestDisarmIfArmed();
        }
    }

    // ---------------------------------------------------------------------------------------------

    private static boolean isLocal(UUID id) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player != null) {
            return id.equals(mc.player.getUniqueID());
        }
        return mc.getSession() != null && id.equals(mc.getSession().getProfile().getId());
    }

    private static void requestDisarmIfArmed() {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (ctx.isArmed()) {
            ctx.requestForcedDisarm();
        }
    }

    /** Disarm of one remote pilot: registry, view, entity size and roll. */
    private void dropRemote(UUID id) {
        ArmRegistry.CLIENT.remove(id);
        remoteDrones.remove(id);
        World world = Minecraft.getMinecraft().world;
        EntityPlayer entity = world != null ? world.getPlayerEntityByUUID(id) : null;
        if (entity != null && !(entity instanceof EntityPlayerSP)) {
            PlayerSizing.restore(entity);
            entity.roll = 0f;
            entity.prevRoll = 0f;
        }
    }

    /** Every remote pilot: restore entities still in the world, then clear registry and views. */
    private void clearRemoteState() {
        World world = Minecraft.getMinecraft().world;
        if (world != null) {
            for (EntityPlayer p : world.playerEntities) {
                if (!(p instanceof EntityPlayerSP) && ArmRegistry.CLIENT.isArmed(p.getUniqueID())) {
                    PlayerSizing.restore(p);
                }
            }
        }
        ArmRegistry.CLIENT.clear();
        remoteDrones.clear();
    }
}
