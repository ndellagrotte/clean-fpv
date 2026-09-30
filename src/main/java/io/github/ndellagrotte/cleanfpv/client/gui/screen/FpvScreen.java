package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvButton;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.NumberField;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Base for every Clean FPV screen: parent navigation (Esc / Done return to the parent), a title,
 * {@link FpvButton} action dispatch, {@link NumberField} handling, tooltips, deferred rebuilds and
 * an optional vertically scrolling content area.
 *
 * <h2>Scrolling</h2>
 * Widgets registered with {@link #scrolled(GuiButton)} / {@link #scrolled(NumberField)} /
 * {@link #scrolledLabel} use <em>content</em> y coordinates; each frame they are shifted by the
 * scroll offset and hidden unless they fit entirely inside {@code [scrollTop, scrollBottom)}. The
 * mouse wheel scrolls. This keeps vanilla button drawing/clicking and needs no scissor.
 *
 * <h2>Persistence</h2>
 * Screens edit the live active model and call {@link ClientSettings#markDirty()}; closing any
 * screen saves once if something changed.
 */
public abstract class FpvScreen extends GuiScreen {

    protected static final int ROW = 24;

    protected final GuiScreen parent;
    private final String titleKey;
    protected final List<NumberField> fields = new ArrayList<>();
    private final Map<Object, Integer> contentY = new IdentityHashMap<>();
    private final List<Label> labels = new ArrayList<>();
    private boolean rebuildRequested;

    protected int scrollTop;
    protected int scrollBottom;
    protected int scroll;
    private int contentBottom;

    private record Label(Supplier<String> text, int x, int y, int color, boolean centered, boolean scrolled) {}

    protected FpvScreen(GuiScreen parent, String titleKey) {
        this.parent = parent;
        this.titleKey = titleKey;
    }

    protected static String t(String key, Object... args) {
        return I18n.format(key, args);
    }

    /** Title shown at the top (defaults to the title key). */
    protected String title() {
        return t(titleKey);
    }

    // =============================================================================================
    // Building

    @Override
    public final void initGui() {
        Keyboard.enableRepeatEvents(true);
        buttonList.clear();
        fields.clear();
        contentY.clear();
        labels.clear();
        scrollTop = 28;
        scrollBottom = height - 32;
        contentBottom = 0;
        build();
        clampScroll();
        layoutScrolled();
    }

    /** Adds this screen's widgets (called on open, resize and {@link #requestRebuild()}). */
    protected abstract void build();

    /** Rebuilds the widgets before the next frame (safe to call from a button action). */
    protected void requestRebuild() {
        rebuildRequested = true;
    }

    protected FpvButton button(int x, int y, int w, String text, Runnable action) {
        return addButton(new FpvButton(x, y, w, 20, text, action));
    }

    /** Standard "Done" footer button returning to the parent. */
    protected FpvButton doneButton() {
        return button(width / 2 - 100, height - 26, 200, t("gui.done"), this::close);
    }

    protected <T extends GuiButton> T scrolled(T b) {
        addButton(b);
        contentY.put(b, b.y);
        contentBottom = Math.max(contentBottom, b.y + b.height);
        return b;
    }

    protected NumberField field(NumberField f) {
        fields.add(f);
        return f;
    }

    protected NumberField scrolled(NumberField f) {
        fields.add(f);
        contentY.put(f, f.y());
        contentBottom = Math.max(contentBottom, f.y() + f.height());
        return f;
    }

    protected void label(Supplier<String> text, int x, int y, int color, boolean centered) {
        labels.add(new Label(text, x, y, color, centered, false));
    }

    protected void label(String text, int x, int y, int color) {
        label(() -> text, x, y, color, false);
    }

    protected void scrolledLabel(Supplier<String> text, int x, int contentYPos, int color) {
        labels.add(new Label(text, x, contentYPos, color, false, true));
        contentBottom = Math.max(contentBottom, contentYPos + 10);
    }

    protected void scrolledLabel(String text, int x, int contentYPos, int color) {
        scrolledLabel(() -> text, x, contentYPos, color);
    }

    /** Content y (as registered) → screen y. */
    protected int toScreenY(int contentYPos) {
        return contentYPos - scroll;
    }

    private void clampScroll() {
        int max = Math.max(0, contentBottom - scrollBottom + 4);
        scroll = Math.clamp(scroll, 0, max);
    }

    private void layoutScrolled() {
        for (Map.Entry<Object, Integer> e : contentY.entrySet()) {
            int y = e.getValue() - scroll;
            if (e.getKey() instanceof GuiButton b) {
                b.y = y;
                b.visible = y >= scrollTop && y + b.height <= scrollBottom;
            } else if (e.getKey() instanceof NumberField f) {
                f.setPosition(f.x(), y);
                f.setVisible(y >= scrollTop && y + f.height() <= scrollBottom);
            }
        }
    }

    // =============================================================================================
    // Navigation

    /** Returns to the parent screen (or the game). */
    protected void close() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        ClientSettings.saveIfDirty();
    }

    // =============================================================================================
    // Input

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button instanceof FpvButton b) {
            b.onPress();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        for (NumberField f : fields) {
            if (f.keyTyped(typedChar, keyCode)) {
                return;
            }
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Unfocus first, focus second: see NumberField.mouseClicked
        for (NumberField f : fields) {
            f.releaseFocus(mouseX, mouseY);
        }
        for (NumberField f : fields) {
            f.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && !contentY.isEmpty()) {
            scroll -= Integer.signum(wheel) * ROW;
            clampScroll();
            layoutScrolled();
        }
    }

    @Override
    public void updateScreen() {
        for (NumberField f : fields) {
            f.update();
        }
        tick();
    }

    /** Per client tick hook (20 Hz). */
    protected void tick() {}

    // =============================================================================================
    // Drawing

    /** Draws screen-specific content under the widgets (called every frame). */
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {}

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (rebuildRequested) {
            rebuildRequested = false;
            initGui();
        }
        drawDefaultBackground();
        drawCenteredString(fontRenderer, title(), width / 2, 10, 0xFFFFFF);
        drawContents(mouseX, mouseY, partialTicks);
        for (Label l : labels) {
            int y = l.scrolled ? l.y - scroll : l.y;
            if (l.scrolled && (y < scrollTop || y + 9 > scrollBottom)) {
                continue;
            }
            String s = l.text.get();
            if (l.centered) {
                drawCenteredString(fontRenderer, s, l.x, y, l.color);
            } else {
                drawString(fontRenderer, s, l.x, y, l.color);
            }
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        for (NumberField f : fields) {
            f.draw();
        }
        drawScrollbar();
        drawTooltips(mouseX, mouseY);
    }

    private void drawScrollbar() {
        int max = contentBottom - scrollBottom + 4;
        if (contentY.isEmpty() || max <= 0) {
            return;
        }
        int x = width - 8;
        int track = scrollBottom - scrollTop;
        int view = track * track / (track + max);
        int pos = scrollTop + (track - view) * scroll / max;
        drawRect(x, scrollTop, x + 4, scrollBottom, 0x60000000);
        drawRect(x, pos, x + 4, pos + Math.max(view, 8), 0xC0C0C0C0);
    }

    private void drawTooltips(int mouseX, int mouseY) {
        String tip = null;
        for (GuiButton b : buttonList) {
            if (b.visible && b instanceof FpvButton fb && fb.tooltip() != null && fb.isMouseOver()) {
                tip = fb.tooltip();
            }
        }
        for (NumberField f : fields) {
            if (f.tooltip() != null && f.isMouseOver(mouseX, mouseY)) {
                tip = f.tooltip();
            }
        }
        if (tip != null) {
            drawHoveringText(fontRenderer.listFormattedStringToWidth(tip, Math.max(120, width / 3)), mouseX, mouseY);
        }
    }

    /** Wraps and draws a paragraph; returns the y below it. */
    protected int paragraph(String text, int x, int y, int maxWidth, int color) {
        for (String line : fontRenderer.listFormattedStringToWidth(text, maxWidth)) {
            drawString(fontRenderer, line, x, y, color);
            y += 10;
        }
        return y;
    }

    /** Wraps and draws a centred paragraph; returns the y below it. */
    protected int centeredParagraph(String text, int y, int maxWidth, int color) {
        for (String line : fontRenderer.listFormattedStringToWidth(text, maxWidth)) {
            drawCenteredString(fontRenderer, line, width / 2, y, color);
            y += 10;
        }
        return y;
    }
}
