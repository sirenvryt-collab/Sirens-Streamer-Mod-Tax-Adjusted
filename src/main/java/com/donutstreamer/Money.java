package com.donutstreamer;

import java.util.Locale;
import java.util.regex.Pattern;

/** Parses and formats amounts like 10k, 1.5m, 2b. */
public final class Money {
    private Money() {}

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d*)?|\\.\\d+");
    private static final String[] UNITS = {"", "K", "M", "B", "T"};

    public static long parse(String raw) throws NumberFormatException {
        if (raw == null) throw new NumberFormatException("null");
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").trim();
        if (s.isEmpty()) throw new NumberFormatException("empty");
        long mult = 1;
        char last = s.charAt(s.length() - 1);
        switch (last) {
            case 'k' -> mult = 1_000L;
            case 'm' -> mult = 1_000_000L;
            case 'b' -> mult = 1_000_000_000L;
            case 't' -> mult = 1_000_000_000_000L;
            default -> { }
        }
        if (mult != 1) s = s.substring(0, s.length() - 1);
        // Plain digits only: Double.parseDouble would also accept things like "5d", "1e9" or "NaN".
        if (!NUMBER.matcher(s).matches()) throw new NumberFormatException("bad amount");
        double v = Double.parseDouble(s);
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) throw new NumberFormatException("bad amount");
        return Math.round(v * mult);
    }

    public static String format(long v) {
        boolean neg = v < 0;
        double a = Math.abs((double) v);
        int u = 0;
        while (u < UNITS.length - 1 && a >= 1000) {
            a /= 1000;
            u++;
        }
        // 999,999 would round to "1000K": promote it to the next unit instead.
        if (u > 0 && u < UNITS.length - 1 && Math.round(a * 100) / 100.0 >= 1000) {
            a /= 1000;
            u++;
        }
        String out = u == 0 ? Long.toString((long) a) : trim(a) + UNITS[u];
        return neg ? "-" + out : out;
    }

    public static String signed(long v) {
        return v > 0 ? "+" + format(v) : format(v);
    }

    /** Up to two decimals with trailing zeros dropped (1.50 -> 1.5, 12.00 -> 12). No String.format or regex: this runs a lot. */
    private static String trim(double d) {
        long hundredths = Math.round(d * 100.0);
        long whole = hundredths / 100;
        int frac = (int) (hundredths % 100);
        if (frac == 0) return Long.toString(whole);
        StringBuilder sb = new StringBuilder(12).append(whole).append('.');
        if (frac % 10 == 0) {
            sb.append(frac / 10);
        } else {
            if (frac < 10) sb.append('0');
            sb.append(frac);
        }
        return sb.toString();
    }
}
