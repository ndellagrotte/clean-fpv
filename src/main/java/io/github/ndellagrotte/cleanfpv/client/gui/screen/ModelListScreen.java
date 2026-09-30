package io.github.ndellagrotte.cleanfpv.client.gui.screen;

import io.github.ndellagrotte.cleanfpv.client.gui.ClientSettings;
import io.github.ndellagrotte.cleanfpv.client.gui.logic.ModelRowLabel;
import io.github.ndellagrotte.cleanfpv.client.gui.widget.FpvButton;
import io.github.ndellagrotte.cleanfpv.common.config.DroneModelConfig;
import io.github.ndellagrotte.cleanfpv.common.config.Presets;
import io.github.ndellagrotte.cleanfpv.common.config.SettingsStore;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiYesNo;

import java.util.List;

/**
 * Model list (spec §11.2 "Choose model"): one row per model showing just its name; the highlighted
 * row is framed "&gt; name &lt;" and the model being flown carries an "[active]" marker. Clicking a
 * row highlights it, then Fly This / New / Rename / Delete act on it. "New" clones the
 * <em>current</em> model (PLAN §10 c). The built-in models ("5 Inch 4S", "Tiny Whoop") cannot be
 * renamed or deleted (PLAN §6.7); the disabled buttons explain why in their hover text.
 */
public class ModelListScreen extends FpvScreen {

    private static final int CONFIRM_DELETE = 1;

    private String highlighted;

    public ModelListScreen(GuiScreen parent) {
        super(parent, "cleanfpv.gui.models.title");
    }

    private SettingsStore store() {
        return ClientSettings.store();
    }

    @Override
    protected void build() {
        SettingsStore store = store();
        if (highlighted == null || store.model(highlighted) == null) {
            highlighted = store.currentName();
        }
        scrollBottom = height - 58;
        int cx = width / 2;
        int y = scrollTop + 4;
        List<String> names = store.modelNames();
        for (String name : names) {
            final String n = name;
            scrolled(new FpvButton(cx - 120, y, 240, 20, "", () -> highlighted = n).label(() -> rowLabel(n)));
            y += 22;
        }
        int by = height - 52;
        button(cx - 154, by, 100, t("cleanfpv.gui.models.use"), this::useHighlighted)
                .enabledWhen(() -> highlighted != null && !highlighted.equals(store().currentName()));
        button(cx - 50, by, 100, t("cleanfpv.gui.models.new"), this::promptNew)
                .tooltip(t("cleanfpv.gui.models.new.tooltip"));
        button(cx + 54, by, 100, t("cleanfpv.gui.models.rename"), this::promptRename)
                .enabledWhen(() -> highlighted != null && !Presets.isPreset(highlighted))
                .tooltip(this::builtInTooltip);
        button(cx - 154, height - 26, 100, t("cleanfpv.gui.models.delete"), this::confirmDelete)
                .enabledWhen(() -> highlighted != null && !Presets.isPreset(highlighted))
                .tooltip(this::builtInTooltip);
        button(cx - 50, height - 26, 204, t("gui.done"), this::close);
    }

    /** Hover text of Rename/Delete while a built-in model is highlighted (why they are disabled). */
    private String builtInTooltip() {
        return Presets.isPreset(highlighted) ? t("cleanfpv.gui.models.builtin.tooltip") : null;
    }

    private String rowLabel(String name) {
        return ModelRowLabel.of(name, name.equals(highlighted), name.equals(store().currentName()),
                t("cleanfpv.gui.models.active"));
    }

    private void useHighlighted() {
        if (highlighted != null && store().select(highlighted)) {
            ClientSettings.markDirty();
        }
    }

    private void promptNew() {
        String base = store().currentName();
        mc.displayGuiScreen(new NamePromptScreen(this, t("cleanfpv.gui.models.new.prompt", base), suggestName(base), name -> {
            SettingsStore.NameCheck check = store().checkName(name);
            if (check != SettingsStore.NameCheck.OK) {
                return check;
            }
            DroneModelConfig created = store().cloneCurrent(name);
            if (created != null) {
                store().select(created.name);
                highlighted = created.name;
                ClientSettings.markDirty();
            }
            return SettingsStore.NameCheck.OK;
        }));
    }

    private void promptRename() {
        String old = highlighted;
        if (old == null || Presets.isPreset(old)) {
            return;
        }
        mc.displayGuiScreen(new NamePromptScreen(this, t("cleanfpv.gui.models.rename.prompt", old), old, name -> {
            if (name.trim().equals(old)) {
                return SettingsStore.NameCheck.OK;
            }
            SettingsStore.NameCheck check = store().checkName(name);
            if (check != SettingsStore.NameCheck.OK) {
                return check;
            }
            if (store().rename(old, name)) {
                highlighted = name.trim();
                ClientSettings.markDirty();
            }
            return SettingsStore.NameCheck.OK;
        }));
    }

    private String suggestName(String base) {
        String stem = base.length() > SettingsStore.MAX_NAME_LENGTH - 3
                ? base.substring(0, SettingsStore.MAX_NAME_LENGTH - 3) : base;
        for (int i = 2; i < 100; i++) {
            String candidate = stem + " " + i;
            if (store().checkName(candidate) == SettingsStore.NameCheck.OK) {
                return candidate;
            }
        }
        return "";
    }

    private void confirmDelete() {
        if (highlighted == null || Presets.isPreset(highlighted)) {
            return;
        }
        mc.displayGuiScreen(new GuiYesNo(this, t("cleanfpv.gui.models.delete.confirm", highlighted),
                t("cleanfpv.gui.models.delete.confirm2"), CONFIRM_DELETE));
    }

    @Override
    public void confirmClicked(boolean result, int id) {
        if (id == CONFIRM_DELETE && result && highlighted != null && store().delete(highlighted)) {
            highlighted = store().currentName();
            ClientSettings.markDirty();
        }
        mc.displayGuiScreen(this);
    }
}
