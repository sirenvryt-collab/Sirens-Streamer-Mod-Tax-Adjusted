package com.donutstreamer.auction;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats auction durations such as 5m, 90s, 1m30s, 2h15m. */
public final class TimeParse {
    private TimeParse() {}

    public static final int MAX_SECONDS = 24 * 60 * 60;

    private static final Pattern DURATION = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * Accepts "5m", "90s", "1m30s", "1h", "2h15m". A plain number is minutes.
     * Returns -1 when it can't be parsed. Results are clamped to 24 hours.
     */
    public static int seconds(String raw) {
        if (raw == null) return -1;
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (text.isEmpty()) return -1;
        try {
            if (DIGITS.matcher(text).matches()) return clamp(Long.parseLong(text) * 60L);
            Matcher m = DURATION.matcher(text);
            if (!m.matches()) return -1;
            long h = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
            long min = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
            long s = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
            return clamp(h * 3600L + min * 60L + s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int clamp(long total) {
        if (total <= 0) return -1;
        return (int) Math.min(total, MAX_SECONDS);
    }

    /** "4:05" or "1:02:03", for chat messages. */
    public static String format(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    /** Countdown text for the HUD: "04:32", or "1:02:03" once over an hour. */
    public static String clock(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }
}
