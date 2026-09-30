package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.common.config.SettingsStore;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;
import java.util.function.Function;

/**
 * Asks for a model name (≤ {@link SettingsStore#MAX_NAME_LENGTH} chars). {@code onSubmit} validates
 * and applies it; {@link SettingsStore.NameCheck#OK} returns to the parent, anything else shows the
 * reason and keeps the prompt open.
 */
public class NamePromptScreen extends FpvScreen {

    private final String prompt;
    private final String initial;
    private final Function<String, SettingsStore.NameCheck> onSubmit;
    private GuiTextField input;
    private SettingsStore.NameCheck error;

    public NamePromptScreen(GuiScreen parent, String prompt, String initial,
                            Function<String, SettingsStore.NameCheck> onSubmit) {
        super(parent, "cleanfpv.gui.name.title");
        this.prompt = prompt;
        this.initial = initial == null ? "" : initial;
        this.onSubmit = onSubmit;
    }

    @Override
    protected void build() {
        String text = input != null ? input.getText() : initial;
        input = new GuiTextField(0, fontRenderer, width / 2 - 100, height / 2 - 10, 200, 20);
        input.setMaxStringLength(SettingsStore.MAX_NAME_LENGTH);
        input.setText(text);
        input.setFocused(true);
        button(width / 2 - 102, height / 2 + 30, 100, t("gui.ok"), this::submit);
        button(width / 2 + 2, height / 2 + 30, 100, t("gui.cancel"), this::close);
    }

    private void submit() {
        SettingsStore.NameCheck result = onSubmit.apply(input.getText());
        if (result == SettingsStore.NameCheck.OK) {
            close();
        } else {
            error = result;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            submit();
            return;
        }
        if (input.textboxKeyTyped(typedChar, keyCode)) {
            error = null;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        input.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void tick() {
        input.updateCursorCounter();
    }

    @Override
    protected void drawContents(int mouseX, int mouseY, float partialTicks) {
        centeredParagraph(prompt, height / 2 - 40, 300, 0xFFFFFF);
        input.drawTextBox();
        if (error != null) {
            drawCenteredString(fontRenderer, t("cleanfpv.gui.name.error." + error.name().toLowerCase(Locale.ROOT),
                    SettingsStore.MAX_NAME_LENGTH), width / 2, height / 2 + 16, 0xFF5555);
        }
    }
}
