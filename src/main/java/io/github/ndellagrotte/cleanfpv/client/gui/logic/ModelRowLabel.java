package io.github.ndellagrotte.cleanfpv.client.gui.logic;

/**
 * Text of one row in the Drone Models list: just the model name, the "[active]" marker (localized by
 * the caller) on the model being flown, and a yellow "&gt; … &lt;" frame around the highlighted row.
 * No controller-scheme or built-in subtext. Pure string logic, unit-tested without Minecraft.
 */
public final class ModelRowLabel {

    private ModelRowLabel() {}

    public static String of(String name, boolean highlighted, boolean active, String activeMarker) {
        StringBuilder sb = new StringBuilder();
        if (highlighted) {
            sb.append("§e> ");
        }
        sb.append(name);
        if (active) {
            sb.append(" §a").append(activeMarker);
        }
        if (highlighted) {
            sb.append(" §e<");
        }
        return sb.toString();
    }
}
