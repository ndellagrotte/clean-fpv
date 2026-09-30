package io.github.ndellagrotte.cleanfpv.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A vanilla button with an attached action, an optional dynamic label and an optional tooltip.
 * {@code FpvScreen.actionPerformed} runs {@link #onPress()}, so screens never switch on button ids
 * (all ids are 0).
 */
public class FpvButton extends GuiButton {

    private final Runnable action;
    private Supplier<String> label;
    private Supplier<String> tooltip;
    private BooleanSupplier enabledWhen;

    public FpvButton(int x, int y, int width, int height, String text, Runnable action) {
        super(0, x, y, width, height, text);
        this.action = action;
    }

    /** Recomputes the label every frame. */
    public FpvButton label(Supplier<String> label) {
        this.label = label;
        if (label != null) {
            this.displayString = label.get();
        }
        return this;
    }

    /** Already-localized tooltip text ({@code null} for none). */
    public FpvButton tooltip(String tooltip) {
        this.tooltip = tooltip == null ? null : () -> tooltip;
        return this;
    }

    /** Tooltip recomputed on hover (already localized; the supplier may return {@code null} for none). */
    public FpvButton tooltip(Supplier<String> tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** Re-evaluates {@link #enabled} every frame. */
    public FpvButton enabledWhen(BooleanSupplier condition) {
        this.enabledWhen = condition;
        if (condition != null) {
            this.enabled = condition.getAsBoolean();
        }
        return this;
    }

    /** The current tooltip text, or {@code null} for none. Shown on hover even while disabled. */
    public String tooltip() {
        return tooltip == null ? null : tooltip.get();
    }

    /** Called by the screen when the button is clicked. */
    public void onPress() {
        if (action != null) {
            action.run();
        }
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (label != null) {
            displayString = label.get();
        }
        if (enabledWhen != null) {
            enabled = enabledWhen.getAsBoolean();
        }
        super.drawButton(mc, mouseX, mouseY, partialTicks);
    }
}
