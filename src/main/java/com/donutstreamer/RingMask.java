package com.donutstreamer;

import java.util.HashMap;
import java.util.Map;

/**
 * Pixel geometry of a ring (annulus), precomputed once per size. Each pixel knows its clockwise angle from
 * 12 o'clock, so any arc length can be turned into a handful of horizontal runs. Pure Java, no Minecraft types.
 */
final class RingMask {
    private static final Map<Integer, RingMask> CACHE = new HashMap<>();

    final int outer;
    private final int count;
    private final int[] xs;
    private final int[] ys;
    private final float[] ang;

    /** Flattened runs: y, x0, x1 (inclusive), state (1 = inside the arc, 0 = track). */
    int[] runs;
    int runCount;
    private int lastStep = -1;

    static RingMask get(int outer, int inner) {
        return CACHE.computeIfAbsent(outer * 1000 + inner, k -> new RingMask(outer, inner));
    }

    private RingMask(int outer, int inner) {
        this.outer = outer;
        int d = outer * 2;
        int[] tx = new int[d * d];
        int[] ty = new int[d * d];
        float[] ta = new float[d * d];
        int n = 0;
        double o2 = (double) outer * outer;
        double i2 = (double) inner * inner;
        for (int y = 0; y < d; y++) {
            for (int x = 0; x < d; x++) {
                int hits = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        double dx = x + (sx + 0.5) / 4 - outer;
                        double dy = y + (sy + 0.5) / 4 - outer;
                        double r2 = dx * dx + dy * dy;
                        if (r2 <= o2 && r2 >= i2) hits++;
                    }
                }
                if (hits < 8) continue;   // under half covered: leave the pixel out (keeps the pixel-art look crisp)
                double dx = x + 0.5 - outer;
                double dy = y + 0.5 - outer;
                double a = Math.atan2(dx, -dy) / (2 * Math.PI);   // 0 at the top, clockwise
                if (a < 0) a += 1;
                tx[n] = x;
                ty[n] = y;
                ta[n] = (float) a;
                n++;
            }
        }
        this.count = n;
        this.xs = java.util.Arrays.copyOf(tx, n);
        this.ys = java.util.Arrays.copyOf(ty, n);
        this.ang = java.util.Arrays.copyOf(ta, n);
        this.runs = new int[Math.max(4, n * 4)];
    }

    int pixelCount() {
        return count;
    }

    /** Rebuilds the run list for an arc covering step/steps of the circle (cached while the step is unchanged). */
    void build(int step, int steps) {
        if (step == lastStep) return;
        lastStep = step;
        float limit = (float) step / steps;
        int rc = 0;
        int cy = -1, cx0 = 0, cx1 = 0, cs = 0;
        for (int i = 0; i < count; i++) {
            int state = ang[i] <= limit ? 1 : 0;
            if (ys[i] == cy && xs[i] == cx1 + 1 && state == cs) {
                cx1 = xs[i];
                continue;
            }
            if (cy >= 0) {
                runs[rc++] = cy;
                runs[rc++] = cx0;
                runs[rc++] = cx1;
                runs[rc++] = cs;
            }
            cy = ys[i];
            cx0 = cx1 = xs[i];
            cs = state;
        }
        if (cy >= 0) {
            runs[rc++] = cy;
            runs[rc++] = cx0;
            runs[rc++] = cx1;
            runs[rc++] = cs;
        }
        runCount = rc / 4;
    }
}
