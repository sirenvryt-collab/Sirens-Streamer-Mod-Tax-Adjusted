package com.donutstreamer.music;

import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import com.donutstreamer.Ui;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.Locale;

/**
 * The "now playing" HUD panel for YouTube / YouTube Music (fed by the userscript, see YtMusic) (a normal HUD widget: draggable, scalable, toggleable).
 * <p>
 * Look: a generated cover (gradient bands, vinyl ring, glossy highlight) with a pulsing glow while playing,
 * a cover-tinted card wash, scrolling title, a 6-bar equalizer with peak caps, a PLAYING / PAUSED tag and a
 * progress bar with a glowing knob. The cover is derived from the track id (real artwork would need image
 * downloads, which this mod deliberately avoids).
 * <p>
 * Cost: text is rebuilt only when the track, play state or displayed second changes. Everything animated is a
 * few dozen flat fills computed from the clock, with no allocation per frame.
 */
public final class MusicPanel {
    private MusicPanel() {}

    public static final int WIDTH = 220;
    public static final int HEIGHT = 66;
    public static final int ACCENT = 0xFFFF4444;      // YouTube red
    private static final int AD = 0xFFFFC107;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int GRAY = 0xFF9DA7B3;
    private static final int DIM = 0xFF6E7681;
    private static final int TRACK = 0x33FFFFFF;
    private static final int COVER = 36;
    private static final int COVER_X = 8, COVER_Y = 8;
    private static final int TEXT_X = 54, TEXT_X_NOCOVER = 10;
    private static final int EQ_BARS = 6;

    private static YtMusic.Now cacheNow = null;
    private static boolean cacheConnected = false;
    private static String cacheStatus = "";
    private static long cacheSec = -2;
    private static boolean cacheValid = false;

    private static String title = "";
    private static String artist = "";
    private static String elapsedText = "";
    private static String totalText = "";
    private static int coverA = 0xFF2A2F36, coverB = 0xFF1B1F24;

    // marquee
    private static int scrollOffset = -1;
    private static String scrollText = "";

    private static Session sess() {
        return DonutStreamerClient.session;
    }

    private static boolean showCover() {
        Session s = sess();
        return s == null || s.musicCover;
    }

    private static boolean showEq() {
        Session s = sess();
        return s == null || s.musicEqualizer;
    }

    private static int textX() {
        return showCover() ? TEXT_X : TEXT_X_NOCOVER;
    }

    public static int width(TextRenderer tr) {
        return WIDTH;
    }

    private static void refresh(TextRenderer tr) {
        YtMusic.Now n = YtMusic.now();
        boolean connected = YtMusic.connected();
        String status = YtMusic.statusText();
        long sec = n == null ? -1 : n.progressNow() / 1000;
        if (cacheValid && n == cacheNow && connected == cacheConnected && sec == cacheSec
                && (n != null || status.equals(cacheStatus))) {
            return;
        }
        boolean trackChanged = !cacheValid || n != cacheNow && (n == null || cacheNow == null || !n.id().equals(cacheNow.id()));
        cacheValid = true;
        cacheNow = n;
        cacheConnected = connected;
        cacheStatus = status;
        cacheSec = sec;

        if (n != null) {
            if (n.ad()) {
                title = "Advertisement";
                artist = YtMusic.muteAds() ? "Muted until it ends" : "Ad playing";
                elapsedText = "";
                totalText = "";
            } else {
                title = n.title();
                artist = n.artist();
                elapsedText = clock(n.progressNow());
                totalText = n.durationMs() > 0 ? clock(n.durationMs()) : "";
            }
            if (trackChanged) {
                int h = n.id().hashCode();
                float hue = ((h & 0xFF) / 255f);
                coverA = Ui.hsv(hue, 0.62f, 0.98f);
                coverB = Ui.hsv(hue + 0.18f + (((h >> 8) & 0x3F) / 255f), 0.78f, 0.42f);
                scrollOffset = -1;
            }
        } else {
            title = "YouTube music";
            artist = status;
            elapsedText = "";
            totalText = "";
            coverA = 0xFF2A2F36;
            coverB = 0xFF1B1F24;
        }
        artist = fit(tr, artist, WIDTH - textX() - 8);
    }

    private static String fit(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) return s;
        return tr.trimToWidth(s, maxWidth - tr.getWidth("...")) + "...";
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** Title text for this frame: static if it fits, otherwise scrolling (rebuilt ~7 times a second). */
    private static String titleNow(TextRenderer tr, int maxW, boolean playing) {
        if (tr.getWidth(title) <= maxW) return title;
        if (!playing) return fit(tr, title, maxW);
        String loop = title + "     " + title;
        int off = (int) ((System.currentTimeMillis() / 150) % (title.length() + 5));
        if (off != scrollOffset) {
            scrollOffset = off;
            scrollText = tr.trimToWidth(loop.substring(off), maxW);
        }
        return scrollText;
    }

    /** Draws inside the widget box (origin = top-left; the card background is already drawn). */
    public static void draw(DrawContext ctx, TextRenderer tr, int w, int h) {
        refresh(tr);
        YtMusic.Now n = cacheNow;
        boolean ad = n != null && n.ad();
        boolean playing = n != null && n.playing() && !ad;
        long t = System.currentTimeMillis();
        boolean cover = showCover();
        int tx = textX();
        int accent = ad ? AD : ACCENT;

        // soft colour wash from the cover across the card (two flat fills)
        if (n != null && cover && !ad) {
            ctx.fill(2, 1, 64, h - 1, Ui.withAlpha(coverA, playing ? 0x26 : 0x12));
            ctx.fill(64, 1, w - 1, h - 1, Ui.withAlpha(coverB, playing ? 0x1A : 0x0C));
        } else if (ad) {
            ctx.fill(2, 1, w - 1, h - 1, Ui.withAlpha(AD, 0x12));
        }

        float pulse = playing ? (float) (0.5 + 0.5 * Math.sin(t / 380.0)) : 0f;

        // ---- cover art ----
        if (cover) {
            int cx = COVER_X, cy = COVER_Y;
            int ca = ad ? 0xFF4A3A10 : coverA, cb = ad ? 0xFF2A2008 : coverB;
            if (n != null) {
                int glow = playing ? 0x18 + (int) (0x2C * pulse) : 0x10;
                ctx.fill(cx - 2, cy - 1, cx + COVER + 2, cy + COVER + 1, Ui.withAlpha(ca, glow));
            }
            for (int i = 0; i < 6; i++) {
                ctx.fill(cx, cy + i * 6, cx + COVER, cy + (i + 1) * 6, Ui.lerp(ca, cb, i / 5f));
            }
            int bg = 0xFF0D1117;
            ctx.fill(cx, cy, cx + 1, cy + 1, bg);
            ctx.fill(cx + COVER - 1, cy, cx + COVER, cy + 1, bg);
            ctx.fill(cx, cy + COVER - 1, cx + 1, cy + COVER, bg);
            ctx.fill(cx + COVER - 1, cy + COVER - 1, cx + COVER, cy + COVER, bg);
            ctx.fill(cx + 1, cy, cx + COVER - 1, cy + 1, 0x30FFFFFF);
            ctx.fill(cx, cy + 1, cx + 1, cy + COVER / 2, 0x20FFFFFF);
            Ui.ring(ctx, cx + COVER / 2, cy + COVER / 2, 13, 9, 1.0, 0x40FFFFFF, 0x40FFFFFF);
            ctx.fill(cx + COVER / 2 - 2, cy + COVER / 2 - 2, cx + COVER / 2 + 2, cy + COVER / 2 + 2, 0xDD000000);
            ctx.fill(cx + COVER / 2 - 1, cy + COVER / 2 - 1, cx + COVER / 2 + 1, cy + COVER / 2 + 1, playing || ad ? accent : DIM);
            if (n != null && !playing && !ad) ctx.fill(cx, cy, cx + COVER, cy + COVER, 0x88000000);
        }

        // ---- title + artist ----
        int maxTitle = w - tx - 8;
        ctx.drawTextWithShadow(tr, titleNow(tr, maxTitle, playing), tx, 9, n != null ? (ad ? AD : WHITE) : ACCENT);
        ctx.drawTextWithShadow(tr, artist, tx, 21, GRAY);

        // ---- equalizer + state tag ----
        int baseY = 43;
        if (n != null) {
            int tagX = tx;
            if (showEq()) {
                for (int i = 0; i < EQ_BARS; i++) {
                    int bh = playing ? 2 + (int) (8 * (0.5 + 0.5 * Math.sin(t / 170.0 + i * 1.9 + i * i * 0.4))) : 2;
                    int bx = tx + i * 3;
                    int col = playing ? Ui.lerp(ACCENT, 0xFFFFC9C9, bh / 10f) : (ad ? AD : DIM);
                    ctx.fill(bx, baseY - bh, bx + 2, baseY, col);
                    if (playing && bh > 4) ctx.fill(bx, baseY - bh - 1, bx + 2, baseY - bh, 0x66FFFFFF);   // peak cap
                }
                tagX = tx + EQ_BARS * 3 + 4;
            }
            String tag = ad ? (YtMusic.muteAds() ? "AD - MUTED" : "AD") : (playing ? "PLAYING" : "PAUSED");
            ctx.drawTextWithShadow(tr, tag, tagX, baseY - 8, ad ? AD : (playing ? ACCENT : DIM));
            String src = n.source();
            ctx.drawTextWithShadow(tr, src, w - 8 - tr.getWidth(src), baseY - 8, DIM);
        }

        // ---- progress bar with glowing knob ----
        int barX = 8, barY = 49, barW = w - 16;
        double frac = (n != null && !ad && n.durationMs() > 0) ? (double) n.progressNow() / n.durationMs() : 0;
        frac = Math.max(0, Math.min(1, frac));
        Ui.bar(ctx, barX, barY, barW, 3, frac, accent, TRACK);
        if (n != null && !ad && n.durationMs() > 0) {
            int kx = barX + (int) Math.round(barW * frac);
            int left = Math.max(barX - 1, Math.min(barX + barW - 4, kx - 2));
            if (playing) ctx.fill(left - 1, barY - 2, left + 6, barY + 5, Ui.withAlpha(ACCENT, 0x28 + (int) (0x30 * pulse)));
            Ui.roundRect(ctx, left, barY - 1, 5, 5, WHITE);
        }

        if (!elapsedText.isEmpty()) ctx.drawTextWithShadow(tr, elapsedText, 8, 55, playing ? ACCENT : GRAY);
        if (!totalText.isEmpty()) ctx.drawTextWithShadow(tr, totalText, w - 8 - tr.getWidth(totalText), 55, GRAY);
    }
}
