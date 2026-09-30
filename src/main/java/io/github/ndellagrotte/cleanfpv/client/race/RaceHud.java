package io.github.ndellagrotte.cleanfpv.client.race;

import io.github.ndellagrotte.cleanfpv.client.ClientDroneContext;
import io.github.ndellagrotte.cleanfpv.client.render.Fisheye;
import io.github.ndellagrotte.cleanfpv.client.render.FisheyeMath;
import io.github.ndellagrotte.cleanfpv.common.race.LapTimes;
import io.github.ndellagrotte.cleanfpv.common.race.Leaderboard;
import io.github.ndellagrotte.cleanfpv.common.race.TrackDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.UUID;

/**
 * Race HUD (spec §9), all undistorted on top of the (possibly fisheyed) picture:
 * <ul>
 *   <li>the next-gate ring: a square ring (corners on the diagonals, inner edge at 45 % of the
 *       outer) with a warm centre fading to a dark rim, at the gate centre's screen position
 *       ({@link RingPlacement}); drawn whenever the pilot races, regardless of race mode;</li>
 *   <li>the text block on the right, through Forge's debug-text lists
 *       ({@code RenderGameOverlayEvent.Text}, so it stacks under F3's right column): track, running
 *       lap, gates passed, last and best lap, top {@value Leaderboard#SHOWN} and the pilot's own
 *       rank when they are not on it.</li>
 * </ul>
 * Pilot names come from the client's player list only (no web lookups); unknown pilots show the
 * first eight characters of their UUID.
 */
final class RaceHud {

    /** Ring outer half-size as a fraction of the mean of the scaled screen sides. */
    private static final double RING_SIZE = 0.03;
    private static final double RING_INNER = 0.45;
    /** Drop a target older than this (a frame without {@code RenderWorldLastEvent}). */
    private static final long TARGET_MAX_AGE_MS = 250L;

    private boolean hasTarget;
    private final double[] target = new double[3];
    private final float[] modelView = new float[16];
    private final float[] projection = new float[16];
    private long targetTimeMs;
    private final double[] placed = new double[3];

    void clearTarget() {
        hasTarget = false;
    }

    void setTarget(double rx, double ry, double rz, float[] modelView, float[] projection, long nowMs) {
        target[0] = rx;
        target[1] = ry;
        target[2] = rz;
        System.arraycopy(modelView, 0, this.modelView, 0, 16);
        System.arraycopy(projection, 0, this.projection, 0, 16);
        targetTimeMs = nowMs;
        hasTarget = true;
    }

    // ---------------------------------------------------------------------------------------------
    // Ring (RenderGameOverlayEvent.Post, ALL)

    void drawRing(ScaledResolution res) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!hasTarget || Minecraft.getSystemTime() - targetTimeMs > TARGET_MAX_AGE_MS || mc.player == null) {
            return;
        }
        double width = res.getScaledWidth_double();
        double height = res.getScaledHeight_double();
        double outer = RING_SIZE * (width + height) * 0.5;
        RingPlacement.Distortion lens = null;
        if (Fisheye.isActive()) {
            float fovDeg = ClientDroneContext.get().cameraPose().vFovDeg();
            double fovRad = Math.toRadians(Float.isFinite(fovDeg) && fovDeg > 0f ? fovDeg : 70f);
            double aspect = mc.displayHeight > 0 ? (double) mc.displayWidth / mc.displayHeight : 1.0;
            lens = (u, v, out) -> FisheyeMath.screenOf(u, v, fovRad, aspect, out);
        }
        RingPlacement.place(modelView, projection, target[0], target[1], target[2], width, height, outer,
                lens, placed);
        drawSquareRing(placed[0], placed[1], outer, outer * RING_INNER);
    }

    private static void drawSquareRing(double x, double y, double outer, double inner) {
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_TRIANGLE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        // Corners on the diagonals, starting at (+1, +1) and going round; the fifth pair closes it.
        int[][] corners = {{1, 1}, {-1, 1}, {-1, -1}, {1, -1}, {1, 1}};
        for (int[] c : corners) {
            buf.pos(x + c[0] * outer, y + c[1] * outer, 0.0).color(0.08f, 0.1f, 0.14f, 0.85f).endVertex();
            buf.pos(x + c[0] * inner, y + c[1] * inner, 0.0).color(1.0f, 0.78f, 0.35f, 0.95f).endVertex();
        }
        tess.draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ---------------------------------------------------------------------------------------------
    // Text (RenderGameOverlayEvent.Text, right column)

    void addText(List<String> right) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        RaceClientState state = RaceClientState.get();
        UUID self = mc.player.getUniqueID();
        TrackDef track = state.joinedTrack(self);
        RaceClientState.Progress progress = state.progress(self);
        if (track == null || progress == null) {
            return;
        }
        if (!right.isEmpty()) {
            right.add("");
        }
        right.add(I18n.format("cleanfpv.race.hud.track", track.name));
        int gates = track.gates.size();
        if (gates == 0) {
            right.add(I18n.format("cleanfpv.race.hud.no_gates"));
        } else if (progress.lapRunning()) {
            long running = Minecraft.getSystemTime() - progress.lapStartMs();
            right.add(I18n.format("cleanfpv.race.hud.lap", LapTimes.format(running)));
            int passed = progress.nextGate() == 0 ? gates : progress.nextGate();
            right.add(I18n.format("cleanfpv.race.hud.gates", passed, gates));
        } else {
            right.add(I18n.format("cleanfpv.race.hud.waiting"));
        }
        if (progress.lastLapMs() > 0) {
            right.add(I18n.format("cleanfpv.race.hud.last", LapTimes.format(progress.lastLapMs())));
        }
        Leaderboard board = state.board(track.id);
        int best = board.bestMs(self);
        if (best > 0) {
            right.add(I18n.format("cleanfpv.race.hud.best", LapTimes.format(best)));
        }
        List<Leaderboard.Entry> top = board.top(Leaderboard.SHOWN);
        if (top.isEmpty()) {
            return;
        }
        right.add(I18n.format("cleanfpv.race.hud.leaderboard"));
        boolean selfShown = false;
        int rank = 1;
        for (Leaderboard.Entry e : top) {
            boolean mine = e.playerId().equals(self);
            selfShown |= mine;
            String line = I18n.format("cleanfpv.race.hud.entry", rank++, name(mc, e.playerId()),
                    LapTimes.format(e.lapMs()));
            right.add(mine ? TextFormatting.YELLOW + line : line);
        }
        if (!selfShown && best > 0) {
            right.add(TextFormatting.YELLOW + I18n.format("cleanfpv.race.hud.own_rank", board.rank(self),
                    LapTimes.format(best)));
        }
    }

    private static String name(Minecraft mc, UUID id) {
        if (mc.player != null && id.equals(mc.player.getUniqueID())) {
            return mc.player.getName();
        }
        NetworkPlayerInfo info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(id);
        if (info != null && info.getGameProfile() != null && info.getGameProfile().getName() != null) {
            return info.getGameProfile().getName();
        }
        return id.toString().substring(0, 8);
    }
}
