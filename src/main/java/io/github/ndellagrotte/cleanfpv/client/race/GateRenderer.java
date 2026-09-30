package io.github.ndellagrotte.cleanfpv.client.race;

import io.github.ndellagrotte.cleanfpv.common.race.GateDef;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import java.nio.FloatBuffer;
import java.util.UUID;

/**
 * World-space race visuals, drawn in {@code RenderWorldLastEvent} (spec §9):
 * <ul>
 *   <li>race mode: every live gate as a wireframe box from {@code cornerA} to {@code cornerB + 1},
 *       green, thick lines;</li>
 *   <li>racing: the next gate of the joined track as an amber box (an addition: the spec only has
 *       the screen ring, but the outline makes the target unambiguous up close).</li>
 * </ul>
 * Gates of other dimensions are skipped. It also records the camera matrices and the next gate's
 * centre for {@link RaceHud}, which draws the ring after the post chain.
 */
final class GateRenderer {

    private static final float LIVE_R = 0.35f;
    private static final float LIVE_G = 1.0f;
    private static final float LIVE_B = 0.4f;
    private static final float NEXT_R = 1.0f;
    private static final float NEXT_G = 0.72f;
    private static final float NEXT_B = 0.2f;
    private static final float LINE_WIDTH = 3.0f;
    /** The next-gate outline sits just outside the live one so both stay visible. */
    private static final double NEXT_INFLATE = 0.03;

    private final FloatBuffer matrixBuffer = GLAllocation.createDirectFloatBuffer(16);
    private final float[] modelView = new float[16];
    private final float[] projection = new float[16];

    /** Called from {@code RenderWorldLastEvent}. */
    void render(RaceHud hud) {
        hud.clearTarget();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            return;
        }
        RaceClientState state = RaceClientState.get();
        int dimension = mc.world.provider.getDimension();
        GateDef next = nextGate(state, mc.player.getUniqueID());
        if (next != null && next.dimension != dimension) {
            next = null;
        }
        boolean live = state.isRaceMode() && !state.liveGates().isEmpty();
        if (!live && next == null) {
            return;
        }
        RenderManager rm = mc.getRenderManager();
        double ox = rm.viewerPosX;
        double oy = rm.viewerPosY;
        double oz = rm.viewerPosZ;

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(LINE_WIDTH);
        GlStateManager.depthMask(false);
        if (live) {
            for (GateDef g : state.liveGates()) {
                if (g.dimension == dimension) {
                    RenderGlobal.drawSelectionBoundingBox(box(g, 0.0).offset(-ox, -oy, -oz),
                            LIVE_R, LIVE_G, LIVE_B, 1.0f);
                }
            }
        }
        if (next != null) {
            RenderGlobal.drawSelectionBoundingBox(box(next, NEXT_INFLATE).offset(-ox, -oy, -oz),
                    NEXT_R, NEXT_G, NEXT_B, 1.0f);
        }
        GlStateManager.depthMask(true);
        GlStateManager.glLineWidth(1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);

        if (next != null) {
            read(GL11.GL_MODELVIEW_MATRIX, modelView);
            read(GL11.GL_PROJECTION_MATRIX, projection);
            Vec3d c = next.center();
            hud.setTarget(c.x - ox, c.y - oy, c.z - oz, modelView, projection, Minecraft.getSystemTime());
        }
    }

    /** The next gate of the pilot's joined track, or {@code null}. */
    static GateDef nextGate(RaceClientState state, UUID self) {
        TrackDef track = state.joinedTrack(self);
        RaceClientState.Progress progress = state.progress(self);
        if (track == null || progress == null || track.gates.isEmpty()) {
            return null;
        }
        int index = progress.nextGate();
        return index >= 0 && index < track.gates.size() ? track.gates.get(index) : null;
    }

    private static AxisAlignedBB box(GateDef g, double inflate) {
        double minX = Math.min(g.cornerA.getX(), g.cornerB.getX()) - inflate;
        double minY = Math.min(g.cornerA.getY(), g.cornerB.getY()) - inflate;
        double minZ = Math.min(g.cornerA.getZ(), g.cornerB.getZ()) - inflate;
        double maxX = Math.max(g.cornerA.getX(), g.cornerB.getX()) + 1.0 + inflate;
        double maxY = Math.max(g.cornerA.getY(), g.cornerB.getY()) + 1.0 + inflate;
        double maxZ = Math.max(g.cornerA.getZ(), g.cornerB.getZ()) + 1.0 + inflate;
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private void read(int pname, float[] dest) {
        matrixBuffer.clear();
        GlStateManager.getFloat(pname, matrixBuffer);
        matrixBuffer.rewind();
        matrixBuffer.get(dest);
    }
}
