package com.donutstreamer;

import net.minecraft.client.gui.DrawContext;

/**
 * Small drawing helpers shared by the HUD, the tracker window and the editor: rounded (notched) corners,
 * soft shadows, rounded bars and a ring/arc timer. Only uses DrawContext.fill, so it is cheap and
 * works at any GUI scale.
 */
public final class Ui {
    private Ui() {}

    public static final int CARD_BG = 0xCC0D1117;
    public static final int CARD_TOP = 0xD0151B24;
    public static final int CARD_BOTTOM = 0xD00A0E14;
    public static final int LABEL = 0xFF8B949E;

    /** Filled rectangle with 1px notched corners (reads as rounded at GUI scale). */
    public static void roundRect(DrawContext ctx, int x, int y, int w, int h, int color) {
        if (w < 3 || h < 3) {
            ctx.fill(x, y, x + w, y + h, color);
            return;
        }
        ctx.fill(x + 1, y, x + w - 1, y + h, color);
        ctx.fill(x, y + 1, x + w, y + h - 1, color);
    }

    /** Rounded box with a 1px border, drawn without overlap so translucent fills stay clean. */
    public static void box(DrawContext ctx, int x, int y, int w, int h, int fill, int border) {
        ctx.fill(x + 1, y, x + w - 1, y + 1, border);
        ctx.fill(x + 1, y + h - 1, x + w - 1, y + h, border);
        ctx.fill(x, y + 1, x + 1, y + h - 1, border);
        ctx.fill(x + w - 1, y + 1, x + w, y + h - 1, border);
        ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    /** Vertical gradient using one fill per row (cheap: panels are only a few dozen pixels tall). */
    public static void vGradient(DrawContext ctx, int x, int y, int w, int h, int top, int bottom) {
        if (h <= 0 || w <= 0) return;
        if (h == 1) {
            ctx.fill(x, y, x + w, y + 1, top);
            return;
        }
        for (int i = 0; i < h; i++) {
            ctx.fill(x, y + i, x + w, y + i + 1, lerpArgb(top, bottom, i / (float) (h - 1)));
        }
    }

    /** Opacity multiplier for the card background (0..1). HudRenderer sets it per element before drawing. */
    public static float bgMul = 1.0f;

    private static int mulA(int argb) {
        if (bgMul >= 0.999f) return argb;
        int a = Math.round((argb >>> 24) * Math.max(0f, bgMul));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** HUD card at the current matrix origin: layered soft shadow, gradient body, glass highlight, glowing accent edge. */
    public static void card(DrawContext ctx, int w, int h, int accent) {
        // layered drop shadow (3 thin L-shapes, fading out)
        ctx.fill(3, h, w + 1, h + 1, mulA(0x44000000));
        ctx.fill(w, 3, w + 1, h, mulA(0x44000000));
        ctx.fill(4, h + 1, w, h + 2, mulA(0x22000000));
        ctx.fill(w + 1, 4, w + 2, h, mulA(0x22000000));
        // body: subtle vertical gradient in a handful of bands (about 8 fills, whatever the height)
        int bandH = Math.max(2, h / 8);
        for (int by = 1; by < h - 1; by += bandH) {
            int bh = Math.min(bandH, h - 1 - by);
            ctx.fill(0, by, w, by + bh, mulA(lerpArgb(CARD_TOP, CARD_BOTTOM, by / (float) (h - 1))));
        }
        ctx.fill(1, 0, w - 1, 1, mulA(CARD_TOP));
        ctx.fill(1, h - 1, w - 1, h, mulA(CARD_BOTTOM));
        // glass highlight + inner border
        ctx.fill(2, 1, w - 2, 2, 0x22FFFFFF);
        ctx.fill(2, h - 1, w - 2, h, 0x14FFFFFF);
        // accent edge with a soft glow beside it
        ctx.fill(0, 3, 2, h - 3, accent);
        ctx.fill(2, 3, 3, h - 3, withAlpha(accent, 0x44));
        ctx.fill(3, 3, 4, h - 3, withAlpha(accent, 0x18));
    }

    /** Small pill-shaped tag (rounded rect with centred text height 9). */
    public static void pill(DrawContext ctx, int x, int y, int w, int h, int color) {
        roundRect(ctx, x, y, w, h, color);
    }

    /** Blend two ARGB colours including alpha. */
    public static int lerpArgb(int a, int b, float t) {
        int aa = (a >>> 24), ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24), br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int oa = Math.round(aa + (ba - aa) * t);
        int or = Math.round(ar + (br - ar) * t);
        int og = Math.round(ag + (bg - ag) * t);
        int ob = Math.round(ab + (bb - ab) * t);
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }

    /** Horizontal bar with rounded ends and a light top edge on the fill. frac is clamped to 0..1. */
    public static void bar(DrawContext ctx, int x, int y, int w, int h, double frac, int fill, int track) {
        roundRect(ctx, x, y, w, h, track);
        int fw = (int) Math.round(w * Math.max(0, Math.min(1, frac)));
        if (fw > 0) {
            int bw = Math.max(fw, Math.min(w, 2));
            roundRect(ctx, x, y, bw, h, fill);
            if (h >= 3 && bw > 2) ctx.fill(x + 1, y, x + bw - 1, y + 1, 0x30FFFFFF);
        }
    }

    /**
     * Ring centred on (cx, cy). The arc covers frac of the circle clockwise from 12 o'clock in arcColor;
     * the rest is drawn in trackColor. Quantised to 240 steps and cached, so it costs about 100 fills.
     */
    public static void ring(DrawContext ctx, int cx, int cy, int outer, int inner, double frac, int arcColor, int trackColor) {
        RingMask m = RingMask.get(outer, inner);
        int steps = 240;
        int step = (int) Math.round(Math.max(0, Math.min(1, frac)) * steps);
        m.build(step, steps);
        int ox = cx - outer, oy = cy - outer;
        int[] r = m.runs;
        for (int i = 0; i < m.runCount; i++) {
            int b = i * 4;
            ctx.fill(ox + r[b + 1], oy + r[b], ox + r[b + 2] + 1, oy + r[b] + 1, r[b + 3] == 1 ? arcColor : trackColor);
        }
    }

    /** Same-alpha colour with a new alpha (0..255). */
    public static int withAlpha(int argb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
    }

    public static int lerp(int a, int b, float t) {
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        int r = Math.round(ar + (br - ar) * t), g = Math.round(ag + (bg - ag) * t), bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** HSV (all 0..1) to opaque ARGB, without AWT. */
    public static int hsv(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6f;
        int i = (int) hh;
        float f = hh - i;
        float p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }
}
