package io.github.ndellagrotte.cleanfpv.client.gui.widget;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * Typed numeric field (spec §11.2 "typed numeric fields"): every keystroke is parsed; a finite value
 * inside {@code [min, max]} is written through the setter immediately, anything else is "silently
 * rejected" (not applied; the text turns red). Losing focus re-displays the live value. While not
 * focused the text follows the getter, so resets done elsewhere show up.
 */
public class NumberField {

    private static final int COLOR_OK = 0xE0E0E0;
    private static final int COLOR_BAD = 0xFF5555;

    private final GuiTextField field;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private final DoubleSupplier min;
    private final DoubleSupplier max;
    private final boolean integer;
    private String tooltip;
    private boolean visible = true;
    private boolean enabled = true;
    private String lastShown = "";

    public NumberField(FontRenderer font, int x, int y, int width, DoubleSupplier getter, DoubleConsumer setter,
                       DoubleSupplier min, DoubleSupplier max, boolean integer) {
        this.field = new GuiTextField(0, font, x, y, width, 16);
        this.field.setMaxStringLength(12);
        this.getter = getter;
        this.setter = setter;
        this.min = min;
        this.max = max;
        this.integer = integer;
        refresh();
    }

    public NumberField(FontRenderer font, int x, int y, int width, DoubleSupplier getter, DoubleConsumer setter,
                       double min, double max, boolean integer) {
        this(font, x, y, width, getter, setter, () -> min, () -> max, integer);
    }

    public NumberField tooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    public String tooltip() {
        return tooltip;
    }

    public int x() {
        return field.x;
    }

    public int y() {
        return field.y;
    }

    public int width() {
        return field.width;
    }

    public int height() {
        return field.height;
    }

    public void setPosition(int x, int y) {
        field.x = x;
        field.y = y;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible && field.isFocused()) {
            field.setFocused(false);
            refresh();
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        field.setEnabled(enabled);
        if (!enabled && field.isFocused()) {
            field.setFocused(false);
            refresh();
        }
    }

    public boolean isFocused() {
        return field.isFocused();
    }

    public boolean isMouseOver(int mouseX, int mouseY) {
        return visible && mouseX >= field.x && mouseX < field.x + field.width
                && mouseY >= field.y && mouseY < field.y + field.height;
    }

    /** Shows the live value (unless the user is typing). */
    public void refresh() {
        if (field.isFocused()) {
            return;
        }
        String text = format(getter.getAsDouble());
        if (!text.equals(lastShown) || !text.equals(field.getText())) {
            field.setText(text);
            field.setCursorPositionZero();
            lastShown = text;
        }
        field.setTextColor(COLOR_OK);
    }

    private String format(double v) {
        if (integer) {
            return Long.toString(Math.round(v));
        }
        String s = String.format(Locale.ROOT, "%.3f", v);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private void onEdited() {
        String text = field.getText().trim();
        boolean ok;
        double v = 0;
        try {
            v = integer ? Long.parseLong(text) : Double.parseDouble(text);
            ok = Double.isFinite(v) && v >= min.getAsDouble() && v <= max.getAsDouble();
        } catch (NumberFormatException e) {
            ok = false;
        }
        if (ok) {
            setter.accept(v);
        }
        field.setTextColor(ok ? COLOR_OK : COLOR_BAD);
    }

    /** Returns true if the key was consumed. */
    public boolean keyTyped(char c, int keyCode) {
        if (!visible || !enabled || !field.isFocused()) {
            return false;
        }
        if (keyCode == 28 || keyCode == 156) { // Enter: commit and leave the field
            field.setFocused(false);
            refresh();
            return true;
        }
        if (field.textboxKeyTyped(c, keyCode)) {
            onEdited();
            return true;
        }
        return false;
    }

    /**
     * Handles a click. Cleanroom's {@code GuiTextField.setFocused} also switches the window's SDL
     * text input on or off, and typed characters only arrive while it is on. So a field only
     * touches its focus when the click concerns it, and callers must run {@link #releaseFocus}
     * on every field before {@link #mouseClicked} on any, or a later field's unfocus switches
     * text input off again after an earlier field was focused.
     */
    public void mouseClicked(int mouseX, int mouseY, int button) {
        if (!visible || !enabled || !isMouseOver(mouseX, mouseY)) {
            return;
        }
        field.mouseClicked(mouseX, mouseY, button);
    }

    /** Unfocuses this field when a click lands elsewhere (or it cannot take input). */
    public void releaseFocus(int mouseX, int mouseY) {
        if (field.isFocused() && (!visible || !enabled || !isMouseOver(mouseX, mouseY))) {
            field.setFocused(false);
            refresh();
        }
    }

    public void update() {
        field.updateCursorCounter();
        refresh();
    }

    public void draw() {
        if (visible) {
            field.drawTextBox();
        }
    }
}
