package io.github.ndellagrotte.cleanfpv.common.race;

import java.util.Locale;

/**
 * Lap-time text. Deliberately not the original's {@code h:m:s:cc} (colons throughout, no zero
 * padding, centiseconds right-padded so 5 cs read as "50"): times use the conventional
 * {@code m:ss.cc}, with {@code h:mm:ss.cc} from one hour on. Centiseconds are truncated.
 */
public final class LapTimes {

    private LapTimes() {}

    public static String format(long ms) {
        long t = Math.max(0L, ms);
        long cs = (t / 10L) % 100L;
        long totalS = t / 1000L;
        long s = totalS % 60L;
        long m = (totalS / 60L) % 60L;
        long h = totalS / 3600L;
        if (h > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
        }
        return String.format(Locale.ROOT, "%d:%02d.%02d", m, s, cs);
    }
}
