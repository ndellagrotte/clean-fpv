package io.github.ndellagrotte.cleanfpv.client.input;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import org.lwjgl.input.Keyboard;

/**
 * The mod's key bindings (spec §3.2): Arm = I (edge-triggered toggle, PLAN §6.1), Settings = O.
 * Rebindable in the vanilla Controls screen. Registered once by {@code ClientProxy.init}.
 */
public final class KeyBindings {

    public static final String CATEGORY = "key.categories.cleanfpv";

    public static final KeyBinding ARM =
            new KeyBinding("key.cleanfpv.arm", KeyConflictContext.IN_GAME, Keyboard.KEY_I, CATEGORY);
    public static final KeyBinding SETTINGS =
            new KeyBinding("key.cleanfpv.settings", KeyConflictContext.IN_GAME, Keyboard.KEY_O, CATEGORY);

    private static boolean registered;

    private KeyBindings() {}

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        ClientRegistry.registerKeyBinding(ARM);
        ClientRegistry.registerKeyBinding(SETTINGS);
    }
}
