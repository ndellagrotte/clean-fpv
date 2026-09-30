package io.github.ndellagrotte.cleanfpv.client.render;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.common.ArmRegistry;
import io.github.ndellagrotte.cleanfpv.common.ArmedState;
import io.github.ndellagrotte.cleanfpv.common.PlayerSizing;
import io.github.ndellagrotte.cleanfpv.common.config.DroneBuild;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderPlayerEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Draws armed players as drones (spec §6.3, PLAN §5 {@code RenderPlayerEvent.Pre} row).
 *
 * <h2>Who is drawn</h2>
 * <ul>
 *   <li>Local player while {@link ClientDroneContext#isArmed()}: attitude
 *       {@link ClientDroneContext#attitude()}, motor speeds {@code droneState().omega}, build =
 *       the active model's build with {@code cameraAngle} replaced by the live
 *       {@link ClientDroneContext#cameraTiltDeg()} (so the own model follows tilt changes: PLAN §10
 *       "renderer refreshed on camera-angle change"). Not drawn in first person.</li>
 *   <li>Remote players with a {@link RemoteDroneView}: its attitude, ω and build. A player the
 *       client registry marks armed before its first transform arrives is drawn level, facing its
 *       entity yaw, with ω = 0.</li>
 * </ul>
 * The vanilla player render is cancelled for all of them. Position comes from the event (vanilla's
 * interpolated entity position, the same the hitbox, shadow and sounds use); the model origin sits
 * at the middle of the armed hitbox. Vanilla still draws the player's shadow (a {@code Render}
 * field without an event hook) and no name tag (it is part of the cancelled {@code doRender}).
 *
 * <h2>Cache</h2>
 * One {@link DroneModel} + {@link PropSpin} per UUID. The model is rebuilt when the drone's build
 * no longer {@link DroneBuild#equals equals} the cached copy (covers Build packets, settings saves
 * and tilt changes) or after {@link #invalidate}/{@link #invalidateAll}; entries unused for
 * {@link #EVICT_AFTER_MS} are evicted by {@link #prune}.
 *
 * <p>Client render thread only.
 */
public final class DroneRenderer {

    /** Cache entries not drawn for this long are dropped (remote disarm, player left). */
    public static final long EVICT_AFTER_MS = 10_000L;

    private static final int GL_QUADS = 7;
    private static final float CENTRE_HEIGHT = PlayerSizing.DRONE_HEIGHT * 0.5f;
    private static final DroneBuild FALLBACK_BUILD = DroneBuild.fiveInch();

    private static final Map<UUID, Entry> CACHE = new HashMap<>();
    private static final FloatBuffer MATRIX = GLAllocation.createDirectFloatBuffer(16);
    private static final Matrix4f SCRATCH_MATRIX = new Matrix4f();
    private static final Quaternionf SCRATCH_ATTITUDE = new Quaternionf();
    private static final DroneBuild SCRATCH_BUILD = new DroneBuild();
    private static final float[] SCRATCH_OMEGA = new float[DroneModel.MOTORS];
    private static final Vector3f SCRATCH_ORIGIN = new Vector3f();

    private DroneRenderer() {}

    // =============================================================================================
    // Public API (orchestration / net may call these)

    /** Forces the model of {@code playerId} to be rebuilt on its next draw (also resets its prop angles). */
    public static void invalidate(UUID playerId) {
        if (playerId != null) {
            CACHE.remove(playerId);
        }
    }

    /** Drops every cached model (disconnect, world change, resource reload). */
    public static void invalidateAll() {
        CACHE.clear();
        PropDiscs.clear();
    }

    /** Evicts entries not drawn within {@link #EVICT_AFTER_MS}. Called once per client tick. */
    public static void prune(long nowMs) {
        Iterator<Entry> it = CACHE.values().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().lastUsedMs > EVICT_AFTER_MS) {
                it.remove();
            }
        }
    }

    // =============================================================================================
    // Event entry point

    /** {@code RenderPlayerEvent.Pre}: cancels and substitutes the drone for armed players. */
    static void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        EntityPlayer player = event.getEntityPlayer();
        Minecraft mc = Minecraft.getMinecraft();
        ClientDroneContext ctx = ClientDroneContext.get();
        boolean inWorld = event.getRenderer().getRenderManager().isRenderShadow();
        Quaternionfc attitude;
        DroneBuild build;
        float[] omega = SCRATCH_OMEGA;

        if (player == mc.player) {
            if (!ctx.isArmed()) {
                return;
            }
            event.setCanceled(true);
            if (inWorld && mc.gameSettings.thirdPersonView == 0 && mc.getRenderViewEntity() == player) {
                return; // true FPV: the own drone is never visible in first person
            }
            attitude = ctx.attitude();
            DroneBuild own = ctx.activeModel().build;
            SCRATCH_BUILD.copyFrom(own != null ? own : FALLBACK_BUILD);
            SCRATCH_BUILD.cameraAngle = ctx.cameraTiltDeg();
            build = SCRATCH_BUILD;
            double[] w = ctx.droneState().omega;
            for (int i = 0; i < DroneModel.MOTORS; i++) {
                omega[i] = (float) w[i];
            }
        } else {
            UUID id = player.getUniqueID();
            RemoteDroneView view = ctx.remoteDrones().get(id);
            if (view != null) {
                attitude = view.attitude(SCRATCH_ATTITUDE);
                build = view.build() != null ? view.build() : FALLBACK_BUILD;
                for (int i = 0; i < DroneModel.MOTORS; i++) {
                    omega[i] = view.omega(i);
                }
            } else {
                ArmedState state = ArmRegistry.CLIENT.get(id);
                if (state == null || !state.armed()) {
                    return;
                }
                float yaw = player.prevRotationYaw
                        + (player.rotationYaw - player.prevRotationYaw) * event.getPartialRenderTick();
                attitude = SCRATCH_ATTITUDE.rotationY((float) Math.toRadians(-yaw));
                build = state.build() != null ? state.build() : FALLBACK_BUILD;
                Arrays.fill(omega, 0f);
            }
            event.setCanceled(true);
        }

        Entry entry = entryFor(player.getUniqueID(), build);
        entry.spin.advance(omega, System.nanoTime());
        double y = event.getY() + CENTRE_HEIGHT;
        drawBody(entry, event.getX(), y, event.getZ(), attitude, omega);
        if (inWorld) {
            PropDiscs.record(entry.model, event.getX(), y, event.getZ(), attitude, omega, entry.spin,
                    player.getBrightnessForRender());
        }
    }

    // =============================================================================================
    // Drawing

    private static Entry entryFor(UUID id, DroneBuild build) {
        Entry e = CACHE.get(id);
        if (e == null || !e.build.equals(build)) {
            DroneBuild copy = build.copy();
            PropSpin spin = e != null ? e.spin : new PropSpin();
            e = new Entry(copy, DroneModel.build(copy), spin);
            CACHE.put(id, e);
        }
        e.lastUsedMs = Minecraft.getSystemTime();
        return e;
    }

    /** Opaque body plus the blades of every motor below the disc threshold. */
    private static void drawBody(Entry e, double x, double y, double z, Quaternionfc attitude, float[] omega) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.multMatrix(rotationBuffer(attitude));
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.color(1f, 1f, 1f, 1f);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        emit(buffer, e.model.body());
        for (int i = 0; i < DroneModel.MOTORS; i++) {
            if (!PropSpin.showsDisc(omega[i])) {
                emitRotor(buffer, e.model, i, e.model.blades(omega[i]), e.spin.angle(i));
            }
        }
        tessellator.draw();

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private static void emit(BufferBuilder buffer, float[] v) {
        for (int k = 0; k < v.length; k += DroneModel.FLOATS_PER_VERTEX) {
            buffer.pos(v[k], v[k + 1], v[k + 2]).color(v[k + 3], v[k + 4], v[k + 5], v[k + 6]).endVertex();
        }
    }

    /** Emits a rotor-frame mesh for motor {@code motor}, turned by {@code angle} (rad) about the spin axis. */
    static void emitRotor(BufferBuilder buffer, DroneModel model, int motor, float[] v, float angle) {
        Vector3f o = model.rotorOrigin(motor, SCRATCH_ORIGIN);
        float sin = (float) Math.sin(angle);
        float cos = (float) Math.cos(angle);
        for (int k = 0; k < v.length; k += DroneModel.FLOATS_PER_VERTEX) {
            float x = v[k];
            float z = v[k + 2];
            buffer.pos(o.x + x * cos + z * sin, o.y + v[k + 1], o.z - x * sin + z * cos)
                    .color(v[k + 3], v[k + 4], v[k + 5], v[k + 6]).endVertex();
        }
    }

    /** Column-major rotation matrix of {@code q} in a shared direct buffer (valid until the next call). */
    static FloatBuffer rotationBuffer(Quaternionfc q) {
        MATRIX.clear();
        SCRATCH_MATRIX.rotation(q).get(MATRIX);
        return MATRIX;
    }

    private static final class Entry {
        final DroneBuild build;
        final DroneModel model;
        final PropSpin spin;
        long lastUsedMs;

        Entry(DroneBuild build, DroneModel model, PropSpin spin) {
            this.build = build;
            this.model = model;
            this.spin = spin;
        }
    }
}
