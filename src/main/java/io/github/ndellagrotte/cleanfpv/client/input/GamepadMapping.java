package io.github.ndellagrotte.cleanfpv.client.input;

import io.github.ndellagrotte.cleanfpv.common.config.ChannelMap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Builds gamepad-scheme channel defaults for a specific device from its SDL gamepad mapping string
 * (PLAN §6.1 "optional pre-fill"). Pure string parsing, unit-tested; {@link JoystickService#gamepadDefaults()}
 * fetches the mapping from SDL.
 *
 * <p>An SDL mapping is {@code "<guid>,<name>,<target>:<source>,..."}. Sources are {@code aN} (raw axis N),
 * {@code bN} (raw button N) or {@code hN.M} (hat N, mask M), optionally prefixed {@code +}/{@code -}
 * (half axis) and suffixed {@code ~} (inverted). Targets may carry a {@code +}/{@code -} prefix too.
 * Raw indices are exactly the {@link JoystickAccess#rawAxes()} / {@link JoystickAccess#rawButtons()}
 * indices (hats appended after buttons, four per hat: up, right, down, left).
 *
 * <h2>Mode-2 layout produced</h2>
 * <table>
 *   <caption>channel ← gamepad control</caption>
 *   <tr><td>throttle</td><td>left stick Y, inverted (SDL Y is negative when pushed up)</td></tr>
 *   <tr><td>yaw</td><td>left stick X</td></tr>
 *   <tr><td>roll</td><td>right stick X</td></tr>
 *   <tr><td>pitch</td><td>right stick Y, inverted (stick forward = positive pitch, as on a radio)</td></tr>
 *   <tr><td>angle knob</td><td>left trigger</td></tr>
 *   <tr><td>arm switch</td><td>left shoulder (level: held = armed; the I key toggles instead)</td></tr>
 *   <tr><td>angle switch</td><td>face button X / west</td></tr>
 *   <tr><td>right-click</td><td>right shoulder</td></tr>
 * </table>
 * A {@code ~} on the source flips the corresponding invert flag. Anything the mapping lacks keeps the
 * {@link ChannelMap#gamepadDefaults(int)} value.
 */
public final class GamepadMapping {

    private GamepadMapping() {}

    /** One parsed source reference. {@code kind} is 'a', 'b' or 'h'. */
    record Source(char kind, int index, int hatMask, boolean inverted) {}

    /**
     * Channel map for a device with this mapping; falls back to {@link ChannelMap#gamepadDefaults(int)}
     * for missing or unparseable entries (a {@code null}/empty mapping yields the plain defaults).
     *
     * @param mapping     SDL mapping string (may be {@code null})
     * @param axisCount   the joystick's raw axis count
     * @param buttonCount the joystick's raw button count (without hat buttons), for hat sources
     */
    public static ChannelMap channelMap(String mapping, int axisCount, int buttonCount) {
        ChannelMap m = ChannelMap.gamepadDefaults(axisCount);
        Map<String, Source> sources = parse(mapping);
        if (sources.isEmpty()) {
            return m;
        }
        axis(m, ChannelMap.Axis.THROTTLE, sources.get("lefty"), true, axisCount);
        axis(m, ChannelMap.Axis.YAW, sources.get("leftx"), false, axisCount);
        axis(m, ChannelMap.Axis.ROLL, sources.get("rightx"), false, axisCount);
        axis(m, ChannelMap.Axis.PITCH, sources.get("righty"), true, axisCount);
        axis(m, ChannelMap.Axis.ANGLE, sources.get("lefttrigger"), false, axisCount);
        button(m, ChannelMap.Switch.ARM, sources.get("leftshoulder"), axisCount, buttonCount);
        button(m, ChannelMap.Switch.ANGLE, sources.get("x"), axisCount, buttonCount);
        button(m, ChannelMap.Switch.RIGHT_CLICK, sources.get("rightshoulder"), axisCount, buttonCount);
        return m;
    }

    /** Target name (lower case, sign prefix stripped) → source; malformed entries are skipped. */
    static Map<String, Source> parse(String mapping) {
        Map<String, Source> out = new HashMap<>();
        if (mapping == null || mapping.isEmpty()) {
            return out;
        }
        for (String entry : mapping.split(",")) {
            int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                continue;
            }
            String target = entry.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (target.startsWith("+") || target.startsWith("-")) {
                target = target.substring(1);
            }
            Source source = parseSource(entry.substring(colon + 1).trim());
            if (source != null && !target.isEmpty()) {
                out.putIfAbsent(target, source);
            }
        }
        return out;
    }

    static Source parseSource(String s) {
        if (s.startsWith("+") || s.startsWith("-")) {
            s = s.substring(1);
        }
        boolean inverted = s.endsWith("~");
        if (inverted) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() < 2) {
            return null;
        }
        char kind = s.charAt(0);
        try {
            return switch (kind) {
                case 'a', 'b' -> {
                    int idx = Integer.parseInt(s.substring(1));
                    yield idx >= 0 ? new Source(kind, idx, 0, inverted) : null;
                }
                case 'h' -> {
                    int dot = s.indexOf('.');
                    if (dot < 0) {
                        yield null;
                    }
                    int hat = Integer.parseInt(s.substring(1, dot));
                    int mask = Integer.parseInt(s.substring(dot + 1));
                    yield hat >= 0 && mask > 0 ? new Source(kind, hat, mask, inverted) : null;
                }
                default -> null;
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void axis(ChannelMap m, ChannelMap.Axis channel, Source src, boolean baseInvert, int axisCount) {
        if (src == null || src.kind() != 'a' || src.index() >= axisCount) {
            return;
        }
        m.setAxisIndex(channel, src.index());
        m.setInverted(channel, baseInvert != src.inverted());
    }

    private static void button(ChannelMap m, ChannelMap.Switch channel, Source src, int axisCount, int buttonCount) {
        if (src == null) {
            return;
        }
        switch (src.kind()) {
            case 'b' -> {
                if (src.index() < buttonCount) {
                    m.setSwitchIndex(channel, src.index());
                    m.setInverted(channel, false);
                }
            }
            case 'a' -> {
                if (src.index() < axisCount) {
                    // Virtual button: pressed when the calibrated axis is > 0.1 (triggers rest at −1).
                    m.setSwitchIndex(channel, ChannelMap.virtualIndex(src.index(), axisCount));
                    m.setInverted(channel, src.inverted());
                }
            }
            case 'h' -> {
                int dir = Integer.numberOfTrailingZeros(src.hatMask()); // 1 up, 2 right, 4 down, 8 left
                if (dir < 4) {
                    m.setSwitchIndex(channel, buttonCount + src.index() * 4 + dir);
                    m.setInverted(channel, false);
                }
            }
            default -> { }
        }
    }
}
