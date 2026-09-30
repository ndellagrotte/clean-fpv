package io.github.ndellagrotte.cleanfpv.client.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

/**
 * Drives vanilla's "use item" binding from the joystick right-click switch while armed (spec §3.2,
 * PLAN §6.1). Edge-aware: the binding is touched only when the wanted state changes, so the real
 * mouse button keeps working whenever the switch is not involved.
 *
 * <p>Press = {@code KeyBinding.setKeyBindState(code, true)} + {@code KeyBinding.onTick(code)} (one
 * click; holding then repeats through vanilla's {@code rightClickDelayTimer}); release =
 * {@code setKeyBindState(code, false)}. Both are static and keyed by the use-item key code, so they
 * affect every binding on that code (vanilla behaves the same for a physical press).
 */
final class RightClickSwitch {

    private boolean held;

    /**
     * @param switchOn the right-click switch is ON (joystick present)
     * @param active   armed, in a world, and no GUI open
     */
    void update(boolean switchOn, boolean active) {
        boolean want = switchOn && active;
        if (want == held) {
            return;
        }
        int code = useItemKeyCode();
        if (want) {
            KeyBinding.setKeyBindState(code, true);
            KeyBinding.onTick(code);
        } else {
            KeyBinding.setKeyBindState(code, false);
        }
        held = want;
    }

    /** Releases the binding if this class is holding it. */
    void release() {
        update(false, false);
    }

    private static int useItemKeyCode() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.gameSettings != null ? mc.gameSettings.keyBindUseItem.getKeyCode() : 0;
    }
}
