package io.github.ndellagrotte.cleanfpv.client.gui.widget;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * Small immediate-mode drawing helpers for the settings screens: filled rectangles, outlines,
 * polylines, horizontal value bars and a two-axis stick gimbal. Coordinates are scaled GUI pixels;
 * colours are {@code 0xAARRGGBB}.
 */
public final class GuiDraw {

    private GuiDraw() {}

    public static void rect(int x0, int y0, int x1, int y1, int argb) {
        Gui.drawRect(x0, y0, x1, y1, argb);
    }

    public static void outline(int x0, int y0, int x1, int y1, int argb) {
        rect(x0, y0, x1, y0 + 1, argb);
        rect(x0, y1 - 1, x1, y1, argb);
        rect(x0, y0, x0 + 1, y1, argb);
        rect(x1 - 1, y0, x1, y1, argb);
    }

    /** Connected line through {@code xs[i], ys[i]} (float GUI coordinates). */
    public static void polyline(float[] xs, float[] ys, int count, int argb, float lineWidth) {
        if (count < 2) {
            return;
        }
        float a = (argb >>> 24 & 255) / 255f;
        float r = (argb >> 16 & 255) / 255f;
        float g = (argb >> 8 & 255) / 255f;
        float b = (argb & 255) / 255f;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        GlStateManager.glLineWidth(lineWidth);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < count; i++) {
            buf.pos(xs[i], ys[i], 0.0).color(r, g, b, a).endVertex();
        }
        tess.draw();
        GlStateManager.glLineWidth(1f);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    /**
     * Horizontal bar for a value in [−1, 1]: dark track, fill from the centre to the value, and
     * optional calibration markers ({@code NaN} = none).
     */
    public static void valueBar(int x, int y, int w, int h, float value, float markMin, float markMax, int fillArgb) {
        rect(x, y, x + w, y + h, 0xA0000000);
        int cx = x + w / 2;
        float v = Float.isFinite(value) ? Math.clamp(value, -1f, 1f) : 0f;
        int vx = x + Math.round((v + 1f) * 0.5f * w);
        rect(Math.min(cx, vx), y + 1, Math.max(cx, vx) + (vx == cx ? 1 : 0), y + h - 1, fillArgb);
        rect(cx, y, cx + 1, y + h, 0x80FFFFFF);
        if (Float.isFinite(markMin)) {
            int mx = x + Math.round((Math.clamp(markMin, -1f, 1f) + 1f) * 0.5f * (w - 1));
            rect(mx, y - 1, mx + 1, y + h + 1, 0xFFFFD040);
        }
        if (Float.isFinite(markMax)) {
            int mx = x + Math.round((Math.clamp(markMax, -1f, 1f) + 1f) * 0.5f * (w - 1));
            rect(mx, y - 1, mx + 1, y + h + 1, 0xFFFFD040);
        }
        outline(x, y, x + w, y + h, 0xFF606060);
    }

    /** Square stick gimbal of side {@code size}; {@code sx, sy} in [−1, 1] (+y = up). */
    public static void gimbal(int x, int y, int size, float sx, float sy) {
        rect(x, y, x + size, y + size, 0xA0000000);
        outline(x, y, x + size, y + size, 0xFF808080);
        int c = size / 2;
        rect(x + c, y + 2, x + c + 1, y + size - 2, 0x40FFFFFF);
        rect(x + 2, y + c, x + size - 2, y + c + 1, 0x40FFFFFF);
        float cx = Float.isFinite(sx) ? Math.clamp(sx, -1f, 1f) : 0f;
        float cy = Float.isFinite(sy) ? Math.clamp(sy, -1f, 1f) : 0f;
        int px = x + Math.round((cx + 1f) * 0.5f * (size - 4)) + 2;
        int py = y + Math.round((1f - (cy + 1f) * 0.5f) * (size - 4)) + 2;
        rect(px - 2, py - 2, px + 2, py + 2, 0xFF40E0FF);
    }
}
