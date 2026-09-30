package io.github.ndellagrotte.cleanfpv.client.gui.widget;

import io.github.ndellagrotte.cleanfpv.client.flight.Rates;
import io.github.ndellagrotte.cleanfpv.common.config.RateTriple;
import net.minecraft.client.gui.FontRenderer;

import java.util.Locale;

/**
 * Rate curve chart (spec §4.2 "a settings widget draws all three curves"): angular rate in deg/s
 * over stick deflection 0..1 for roll, pitch and yaw, computed with {@link Rates#rateDegPerSec},
 * plus a vertical marker per axis at the live |stick| value. The vertical scale adapts to the
 * fastest curve (at least 200 deg/s) and is labelled.
 */
public final class RateChart {

    /** Curve colours: roll, pitch, yaw (own palette). */
    public static final int[] COLORS = {0xFFE8705A, 0xFF6CD48A, 0xFF5AA0F0};
    private static final int SEGMENTS = 64;

    private RateChart() {}

    /**
     * @param rates  roll, pitch, yaw triples
     * @param sticks live |stick| per axis in [0, 1] ({@code NaN} = no marker)
     */
    public static void draw(FontRenderer font, int x, int y, int w, int h, RateTriple[] rates, float[] sticks) {
        GuiDraw.rect(x, y, x + w, y + h, 0xC0000000);
        GuiDraw.outline(x, y, x + w, y + h, 0xFF707070);
        double top = 200.0;
        for (RateTriple r : rates) {
            top = Math.max(top, Math.abs(Rates.rateDegPerSec(1.0, r)));
        }
        top = Math.ceil(top / 100.0) * 100.0;
        // grid every 1/4 of the scale
        for (int i = 1; i < 4; i++) {
            int gy = y + h - Math.round(h * i / 4f);
            GuiDraw.rect(x + 1, gy, x + w - 1, gy + 1, 0x30FFFFFF);
            int gx = x + Math.round(w * i / 4f);
            GuiDraw.rect(gx, y + 1, gx + 1, y + h - 1, 0x30FFFFFF);
        }
        float[] xs = new float[SEGMENTS + 1];
        float[] ys = new float[SEGMENTS + 1];
        for (int a = 0; a < rates.length; a++) {
            for (int i = 0; i <= SEGMENTS; i++) {
                double s = i / (double) SEGMENTS;
                double v = Math.abs(Rates.rateDegPerSec(s, rates[a]));
                xs[i] = x + (float) (s * w);
                ys[i] = y + h - (float) (Math.min(v / top, 1.0) * h);
            }
            GuiDraw.polyline(xs, ys, SEGMENTS + 1, COLORS[a], 2f);
        }
        for (int a = 0; a < rates.length && sticks != null && a < sticks.length; a++) {
            float s = sticks[a];
            if (!Float.isFinite(s)) {
                continue;
            }
            s = Math.clamp(Math.abs(s), 0f, 1f);
            int mx = x + Math.round(s * (w - 1));
            GuiDraw.rect(mx, y + 1, mx + 1, y + h - 1, (COLORS[a] & 0x00FFFFFF) | 0xA0000000);
            double v = Math.abs(Rates.rateDegPerSec(s, rates[a]));
            int my = y + h - (int) Math.round(Math.min(v / top, 1.0) * h);
            GuiDraw.rect(mx - 2, my - 2, mx + 3, my + 3, COLORS[a]);
        }
        font.drawStringWithShadow(String.format(Locale.ROOT, "%d°/s", (long) top), x + 3, y + 3, 0xFFC0C0C0);
    }
}
