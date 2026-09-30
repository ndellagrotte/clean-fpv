package io.github.ndellagrotte.cleanfpv.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import java.util.ArrayList;
import java.util.List;

/**
 * Translucent propeller blur discs (spec §6.3), drawn in {@code RenderWorldLastEvent} after the
 * world's own translucent pass so they blend over water, glass and each other correctly.
 *
 * <p>The drone renderer records one entry per drone it actually drew in the current world pass
 * ({@link #record}); {@link #renderAll} draws and clears them. Consequences, all intended: a
 * drone culled by the frustum, the own drone in first person and a spectated pilot's drone (the
 * view entity is not rendered) get no discs, each disc is drawn exactly once per pass (a second
 * anaglyph pass records again), and GUI previews (rendered with shadows off) record nothing.
 *
 * <p>Client render thread only.
 */
public final class PropDiscs {

    private static final int GL_QUADS = 7;

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static int used;

    private PropDiscs() {}

    /**
     * Queues the discs of one drone for this pass. Motors below {@link PropSpin#DISC_THRESHOLD} are
     * skipped here (they were drawn with blades).
     *
     * <p>{@code x, y, z}: camera-relative position of the model origin (entity render offset plus
     * the centre height), in the same space as {@code RenderWorldLastEvent}'s modelview.
     *
     * @param attitude    body attitude (copied)
     * @param omega       signed motor speeds, rad/s (copied)
     * @param brightness  packed lightmap value ({@code Entity.getBrightnessForRender()})
     */
    static void record(DroneModel model, double x, double y, double z, Quaternionfc attitude, float[] omega,
                       PropSpin spin, int brightness) {
        boolean any = false;
        for (int i = 0; i < DroneModel.MOTORS; i++) {
            any |= PropSpin.showsDisc(omega[i]);
        }
        if (!any) {
            return;
        }
        if (used == ENTRIES.size()) {
            ENTRIES.add(new Entry());
        }
        Entry e = ENTRIES.get(used++);
        e.model = model;
        e.x = x;
        e.y = y;
        e.z = z;
        e.attitude.set(attitude);
        e.brightness = brightness;
        for (int i = 0; i < DroneModel.MOTORS; i++) {
            e.omega[i] = omega[i];
            e.angle[i] = spin.angle(i);
        }
    }

    /** Draws and clears the queued discs. Call from {@code RenderWorldLastEvent}. */
    static void renderAll() {
        if (used == 0) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        mc.entityRenderer.enableLightmap();

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        for (int n = 0; n < used; n++) {
            Entry e = ENTRIES.get(n);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, e.brightness & 0xFFFF,
                    (e.brightness >>> 16) & 0xFFFF);
            GlStateManager.pushMatrix();
            GlStateManager.translate(e.x, e.y, e.z);
            GlStateManager.multMatrix(DroneRenderer.rotationBuffer(e.attitude));
            buffer.begin(GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i < DroneModel.MOTORS; i++) {
                if (PropSpin.showsDisc(e.omega[i])) {
                    DroneRenderer.emitRotor(buffer, e.model, i, e.model.disc(), e.angle[i]);
                }
            }
            tessellator.draw();
            GlStateManager.popMatrix();
            e.model = null;
        }
        used = 0;

        mc.entityRenderer.disableLightmap();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Drops queued entries without drawing (world change). */
    static void clear() {
        for (int n = 0; n < used; n++) {
            ENTRIES.get(n).model = null;
        }
        used = 0;
    }

    private static final class Entry {
        DroneModel model;
        double x;
        double y;
        double z;
        final Quaternionf attitude = new Quaternionf();
        final float[] omega = new float[DroneModel.MOTORS];
        final float[] angle = new float[DroneModel.MOTORS];
        int brightness;
    }
}
