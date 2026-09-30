package io.github.ndellagrotte.cleanfpv.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

/**
 * Horizontal slider over {@code [min, max]} snapped to {@code step}, drawn like vanilla's option
 * sliders. Reads the live value from a getter each frame (unless being dragged) and writes every
 * change through a setter; the label is {@code formatter(value)}. Height is fixed at 20 (texture).
 */
public class FpvSlider extends FpvButton {

    private final double min;
    private final double max;
    private final double step;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final DoubleFunction<String> formatter;
    private double fraction;
    private boolean dragging;

    public FpvSlider(int x, int y, int width, double min, double max, double step,
                     DoubleSupplier getter, DoubleConsumer setter, DoubleFunction<String> formatter) {
        super(x, y, width, 20, "", null);
        this.min = min;
        this.max = max;
        this.step = step;
        this.getter = getter;
        this.setter = setter;
        this.formatter = formatter;
        syncFromGetter();
    }

    private void syncFromGetter() {
        double v = getter.getAsDouble();
        fraction = max > min ? Math.clamp((v - min) / (max - min), 0.0, 1.0) : 0.0;
        displayString = formatter.apply(valueOf(fraction));
    }

    private double valueOf(double f) {
        double v = min + f * (max - min);
        if (step > 0) {
            v = min + Math.round((v - min) / step) * step;
        }
        return Math.clamp(v, min, max);
    }

    private void setFromMouse(int mouseX) {
        fraction = Math.clamp((mouseX - (x + 4)) / (double) (width - 8), 0.0, 1.0);
        double v = valueOf(fraction);
        setter.accept(v);
        displayString = formatter.apply(v);
    }

    @Override
    protected int getHoverState(boolean mouseOver) {
        return 0;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!dragging) {
            syncFromGetter();
        }
        super.drawButton(mc, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (!visible) {
            return;
        }
        if (dragging) {
            setFromMouse(mouseX);
        }
        mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
        GlStateManager.color(1f, 1f, 1f, 1f);
        int knob = x + (int) (fraction * (width - 8));
        int v = enabled ? 66 : 46;
        drawTexturedModalRect(knob, y, 0, v, 4, 20);
        drawTexturedModalRect(knob + 4, y, 196, v, 4, 20);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (super.mousePressed(mc, mouseX, mouseY)) {
            setFromMouse(mouseX);
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        dragging = false;
    }
}
