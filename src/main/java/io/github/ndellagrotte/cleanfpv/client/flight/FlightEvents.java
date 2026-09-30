package io.github.ndellagrotte.cleanfpv.client.flight;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.input.InputManager;
import io.github.ndellagrotte.cleanfpv.client.physics.PhysicsEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Forge event subscriber that sequences a frame and a tick (PLAN §7): frame counter, input poll,
 * arm FSM, attitude step, physics step, Transform send, client lifecycle (PLAN §6.9).
 *
 * <p>Owner: (F) orchestration. Registered on {@code MinecraftForge.EVENT_BUS} by {@code ClientProxy}
 * as an instance (instance {@code @SubscribeEvent} methods).
 *
 * <h2>Real frame order ({@code Minecraft.runGameLoop}, api-notes D2) and what runs where</h2>
 * <ol>
 *   <li>Scheduled tasks (packets), then 0..N client ticks:
 *     <ul>
 *       <li>{@code ClientTickEvent} START — forced-disarm triggers ({@link ArmController#checkForcedDisarm}).</li>
 *       <li>{@code PlayerTickEvent} START (local player) — armed: re-shrink, physics tick step (tick
 *           mode) or tick boundary (realtime mode), {@code setPosition}, zero motion, flying, overheat
 *           flag, pending Build, Transform; disarmed: velocity tracking for arm inheritance.</li>
 *       <li>{@code PlayerTickEvent} END (local player, armed) — re-assert size/eye/step/flying.</li>
 *     </ul></li>
 *   <li>{@code beginFrame()}, then {@code RenderTickEvent} START:
 *     <ul>
 *       <li>{@link EventPriority#HIGH}: {@code nextFrame()}, {@code InputManager.pollFrame()} (once),
 *           forced-disarm triggers, the arm intent (key / switch), realtime physics step, and the
 *           camera-override flag for this frame, all before render (C, NORMAL: fisheye reconcile)
 *           and client net (D, LOW: remote interpolation).</li>
 *       <li>{@link EventPriority#LOWEST}: spectator camera pose refreshed from this frame's remote
 *           interpolation (D ran at LOW).</li>
 *     </ul></li>
 *   <li>{@code updateCameraAndRender}: {@code MouseTurnEvent} (armed: mouse + sticks attitude step,
 *       camera pose, player rotation outputs; event cancelled) → {@code FOVModifier} /
 *       {@code CameraSetup} (C reads the pose) → world → post chain → HUD.</li>
 *   <li>{@code RenderTickEvent} END: if armed and no attitude step ran this frame (window unfocused,
 *       GUI open: no {@code MouseTurnEvent}), run it with zero mouse and the loader frame dt.</li>
 * </ol>
 *
 * <h2>Lifecycle (PLAN §6.9 client column, api-notes D3/D5)</h2>
 * Disconnect (netty thread → client thread): forced disarm without sending, input reset, flight
 * state and Hello cleared. Client world unload (disconnect and every dimension change): forced
 * disarm (sent while the connection is open), input reset; Hello kept. Death (health ≤ 0 / isDead)
 * and a new {@code EntityPlayerSP} (respawn, dimension change; also seen in
 * {@code EntityJoinWorldEvent}): forced disarm. Client net (D) also raises
 * {@link ClientDroneContext#requestForcedDisarm()} for a server {@code Arm(false)}; it is consumed
 * at the next tick or frame. {@code DrawScreenEvent.Post} stamps the realtime timestamp while a GUI
 * is open (the loader's frame clock already caps dt at 0.1 s, so closing a GUI never produces a dt
 * spike).
 */
public final class FlightEvents {

    private final PhysicsEngine engine = new PhysicsEngine();
    private final ArmController arm = new ArmController(engine);
    private final FlightLoop loop = new FlightLoop(engine, arm);

    /** The arm state machine (for wiring and diagnostics). */
    public ArmController armController() {
        return arm;
    }

    // =============================================================================================
    // Frame

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRenderTickStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ClientDroneContext ctx = ClientDroneContext.get();
        ctx.nextFrame();
        InputManager.get().pollFrame();

        arm.checkForcedDisarm();
        arm.handleIntent(InputManager.get().arm().pollArmIntent(ctx.isArmed()));

        if (ctx.isArmed()) {
            loop.realtimeStep(mc.player, ForgeHooksClient.getFrameDeltaSeconds());
            ctx.setCameraOverride(true);
        } else {
            CameraOutput.applySpectator(mc);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderTickStartLate(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START && !ClientDroneContext.get().isArmed()) {
            CameraOutput.applySpectator(Minecraft.getMinecraft());
        }
    }

    @SubscribeEvent
    public void onMouseTurn(InputEvent.MouseTurnEvent event) {
        ClientDroneContext ctx = ClientDroneContext.get();
        EntityPlayerSP player = event.getPlayer();
        if (!ctx.isArmed() || player == null || player != arm.armedPlayer()) {
            return;
        }
        event.setCanceled(true);
        loop.attitudeStep(player, event.getFrameDeltaSeconds(), event.getYaw(), event.getPitch());
    }

    @SubscribeEvent
    public void onRenderTickEnd(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ClientDroneContext.get().isArmed()) {
            return;
        }
        loop.attitudeStep(Minecraft.getMinecraft().player, ForgeHooksClient.getFrameDeltaSeconds(), 0f, 0f);
    }

    // =============================================================================================
    // Tick

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            arm.checkForcedDisarm();
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.side != Side.CLIENT || !(event.player instanceof EntityPlayerSP player)
                || player != Minecraft.getMinecraft().player) {
            return;
        }
        if (event.phase == TickEvent.Phase.START) {
            arm.checkForcedDisarm();
            loop.tickStart(player);
        } else {
            loop.tickEnd(player);
        }
    }

    // =============================================================================================
    // Lifecycle

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        // Netty thread; may fire twice. Everything below is idempotent.
        Minecraft.getMinecraft().addScheduledTask(() -> {
            arm.forceDisarm("disconnect");
            InputManager.get().reset();
            loop.resetTracking();
            ClientDroneContext.get().reset();
        });
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        if (!event.getWorld().isRemote) {
            return; // integrated server world in the same JVM
        }
        arm.forceDisarm("client world unload");
        InputManager.get().reset();
        loop.resetTracking();
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (!event.getWorld().isRemote || !(event.getEntity() instanceof EntityPlayerSP player)) {
            return;
        }
        if (ClientDroneContext.get().isArmed() && player != arm.armedPlayer()) {
            arm.forceDisarm("new player entity");
        }
        loop.resetTracking();
    }

    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        ClientDroneContext ctx = ClientDroneContext.get();
        if (ctx.isArmed()) {
            ctx.droneState().lastStepNanos = System.nanoTime();
        }
    }
}
