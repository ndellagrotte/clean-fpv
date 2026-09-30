package io.github.ndellagrotte.cleanfpv.client.gui.widget;

import net.minecraft.client.resources.I18n;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Two-state button labelled "{@code <name>: ON/OFF}" (or custom state words) that writes through a
 * setter and re-reads the getter every frame, so it always shows the live value.
 */
public class ToggleButton extends FpvButton {

    public ToggleButton(int x, int y, int width, String name, BooleanSupplier getter, Consumer<Boolean> setter) {
        this(x, y, width, name, getter, setter, I18n.format("cleanfpv.gui.on"), I18n.format("cleanfpv.gui.off"));
    }

    public ToggleButton(int x, int y, int width, String name, BooleanSupplier getter, Consumer<Boolean> setter,
                        String onWord, String offWord) {
        super(x, y, width, 20, "", () -> setter.accept(!getter.getAsBoolean()));
        label(() -> name + ": " + (getter.getAsBoolean() ? onWord : offWord));
    }
}
