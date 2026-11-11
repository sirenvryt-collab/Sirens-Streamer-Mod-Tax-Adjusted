package com.donutstreamer;

import com.donutstreamer.auction.AuctionPanel;
import com.donutstreamer.auction.AuctionState;
import com.donutstreamer.auction.ServerDetector;
import com.donutstreamer.chat.ChatPanel;
import com.donutstreamer.chat.StreamChat;
import com.donutstreamer.music.MusicPanel;
import com.donutstreamer.music.YtMusic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;
import java.util.Locale;

/** Every HUD element. Each one can be switched on/off and dragged separately. */
public final class Widgets {
    private Widgets() {}

    public enum W {
        NET("net", "Live profit", true),
        RECORD("record", "Win-loss record", true),
        STREAK("streak", "Current streak", true),
        LAST("last", "Last payment", true),
        TIME("time", "Session timer", true),
        GRAPH("graph", "Profit chart (add-on to Live profit)", false),
        BARS("bars", "Result bars chart", false),
        WINBAR("winbar", "Win rate bar", false),
        FORM("form", "Last 10 form", false),
        GAMBLES("gambles", "Gamble count", false),
        RECEIVED("received", "Total received", false),
        SENT("sent", "Total sent", false),
        SOLD("sold", "Sold (selling income)", false),
        SPENT("spent", "Spent on items", false),
        AVGWIN("avgwin", "Average win", false),
        AVGLOSS("avgloss", "Average loss", false),
        BIGWIN("bigwin", "Biggest received", false),
        BIGLOSS("bigloss", "Biggest sent", false),
        PEAK("peak", "Peak profit", false),
        LOW("low", "Lowest point", false),
        BEST("beststreak", "Best / worst streak", false),
        PERBET("perbet", "Profit per gamble", false),
        PHR("phr", "Profit per hour", false),
        TOPPAYER("toppayer", "Top payer", false),
        BALANCE("balance", "Balance", false),
        GOALBAR("goalbar", "Goal progress", false),
        LIMIT("limit", "Loss limit bar", false),
        AUCTION("auction", "Auction panel (DonutSMP)", true),
        MUSIC("music", "Music now playing (YouTube)", true),
        CHAT("chat", "Stream chat (Twitch / YouTube)", false);

        public final String id;
        public final String label;
        public final boolean defOn;

        W(String id, String label, boolean defOn) {
            this.id = id;
            this.label = label;
            this.defOn = defOn;
        }
    }

    /** Cached copy of W.values() (values() allocates a new array on every call). */
    public static final W[] ALL = W.values();

    public static final int WHITE = 0xFFFFFFFF;
    public static final int GRAY = 0xFFAAAAAA;
    public static final int GREEN = 0xFF55FF55;
    public static final int RED = 0xFFFF5555;
    public static final int GOLD = 0xFFFFD700;
    public static final int AQUA = 0xFF55FFFF;

    private static final int CHART_W = 124;

    private static final int[][] DEFAULTS = new int[ALL.length][2];

    private static int baseHeight(W w) {
        switch (w) {
            case GRAPH: return 64;
            case BARS: return 32;
            case WINBAR: case GOALBAR: case LIMIT: return 20;
            case AUCTION: return AuctionPanel.HEIGHT;
            case MUSIC: return MusicPanel.HEIGHT;
            case CHAT: return ChatPanel.height();
            default: return 13;
        }
    }

    static {
        int x = 6, y = 6;
        for (W w : ALL) {
            if (w == W.AUCTION || w == W.MUSIC || w == W.CHAT) continue;   // placed by screen size, see defaultPos
            int h = baseHeight(w);
            if (y + h > 210) {
                x += 140;
                y = 6;
            }
            DEFAULTS[w.ordinal()][0] = x;
            DEFAULTS[w.ordinal()][1] = y;
            y += h + 2;
        }
    }

    public static int[] defaultPos(W w) {
        if (w == W.AUCTION) {
            // Top-right, just under the "HUD editor" button.
            MinecraftClient mc = MinecraftClient.getInstance();
            int sw = (mc != null && mc.getWindow() != null) ? mc.getWindow().getScaledWidth() : 427;
            return new int[]{Math.max(0, sw - AuctionPanel.WIDTH - 8), 22};
        }
        if (w == W.CHAT) {
            // Right side, under the auction panel.
            MinecraftClient mc = MinecraftClient.getInstance();
            int sw = (mc != null && mc.getWindow() != null) ? mc.getWindow().getScaledWidth() : 427;
            return new int[]{Math.max(0, sw - ChatPanel.DEFAULT_WIDTH - 8), 100};
        }
        if (w == W.MUSIC) {
            // Bottom-right, clear of the hotbar and chat.
            MinecraftClient mc = MinecraftClient.getInstance();
            boolean ok = mc != null && mc.getWindow() != null;
            int sw = ok ? mc.getWindow().getScaledWidth() : 427;
            int sh = ok ? mc.getWindow().getScaledHeight() : 240;
            return new int[]{Math.max(0, sw - MusicPanel.WIDTH - 8), Math.max(0, sh - MusicPanel.HEIGHT - 8)};
        }
        return DEFAULTS[w.ordinal()];
    }

    private static List<Long> windowed(List<Long> list, int max) {
        return list.size() <= max ? list : list.subList(list.size() - max, list.size());
    }

    /** Whether the widget has something to show right now (the HUD hides widgets without data). */
    public static boolean available(W w, Session s) {
        switch (w) {
            case NET: case RECORD: case STREAK: case TIME: return true;
            case LAST: return !s.history.isEmpty();
            case GRAPH: return true;
            case BARS: return s.total() >= 2;
            case AVGWIN: return s.wins > 0;
            case AVGLOSS: return s.losses > 0;
            case SOLD: return s.sold > 0;
            case SPENT: return s.spent > 0;
            case PHR: return s.total() > 0 && s.elapsedMs >= 60_000;
            case BALANCE: return s.startBalance >= 0;
            case LIMIT: return s.lossLimit > 0;
            case GOALBAR: return s.goal > 0;
            case TOPPAYER: return s.topPayer() != null;
            case AUCTION: return ServerDetector.isDonutSmp() && AuctionState.INSTANCE.isActive();
            case MUSIC: return YtMusic.now() != null;
            case CHAT: return StreamChat.configured();
            default: return s.total() > 0;
        }
    }

    /** True when the chat-screen preview of an idle auction / music panel is switched off (/ds preview). */
    public static boolean previewHidden(W w, Session s) {
        return !s.chatPreviews && (w == W.AUCTION || w == W.MUSIC) && !available(w, s);
    }

    // ================= cached text / colour / width =================
    // Text is rebuilt only when the session changes (Session.version) or the timer's second ticks over,
    // not every frame. That removes all the per-frame String.format / regex work from the HUD.

    private static final class Cache {
        long version = -1;
        long sec = -1;
        String text = "";
        String label = "";
        String value = "";
        int labelWidth = -1;
        int color = WHITE;
        int width = -1;
    }

    private static final Cache[] CACHE = new Cache[ALL.length];

    static {
        for (int i = 0; i < CACHE.length; i++) CACHE[i] = new Cache();
    }

    private static int formWidth = -1;

    /** Only these two read the session clock, so only they need rebuilding as the seconds tick over. */
    private static boolean timeDependent(W w) {
        return w == W.TIME || w == W.PHR;
    }

    private static Cache cache(W w, Session s) {
        Cache c = CACHE[w.ordinal()];
        long sec = timeDependent(w) ? s.elapsedMs / 1000L : 0L;
        if (c.version != s.version || c.sec != sec) {
            c.version = s.version;
            c.sec = sec;
            c.text = buildText(w, s);
            c.color = buildColor(w, s);
            c.width = -1;
            // "Live: +45M" is drawn as a dim label plus a bright value.
            int split = c.text.indexOf(": ");
            if (split > 0 && split < 16) {
                c.label = c.text.substring(0, split + 2);
                c.value = c.text.substring(split + 2);
            } else {
                c.label = "";
                c.value = c.text;
            }
            c.labelWidth = -1;
        }
        return c;
    }

    public static String text(W w, Session s) {
        return cache(w, s).text;
    }

    public static int color(W w, Session s) {
        return cache(w, s).color;
    }

    private static int textWidth(W w, Session s, TextRenderer tr) {
        Cache c = cache(w, s);
        if (c.width < 0) c.width = tr.getWidth(c.text);
        return c.width;
    }

    /** Width of the widget in unscaled GUI pixels. */
    public static int width(W w, Session s, TextRenderer tr) {
        switch (w) {
            case BARS: return CHART_W;
            case GRAPH: return Math.max(CHART_W, textWidth(w, s, tr) + 12);
            case FORM:
                if (formWidth < 0) formWidth = 7 + tr.getWidth("Form") + 5 + 10 * 9 + 4;
                return formWidth;
            case AUCTION: return AuctionPanel.width(tr);
            case MUSIC: return MusicPanel.width(tr);
            case CHAT: return ChatPanel.width(tr);
            case WINBAR: case GOALBAR: case LIMIT: return Math.max(96, textWidth(w, s, tr) + 12);
            default: return textWidth(w, s, tr) + 12;
        }
    }

    /** Height of the widget in unscaled GUI pixels. */
    public static int height(W w) {
        return baseHeight(w);
    }

    private static String buildText(W w, Session s) {
        switch (w) {
            case NET: case GRAPH:
                if (s.percentMode && s.startBalance > 0) {
                    return "Live: " + String.format(Locale.ROOT, "%+.1f%%", 100.0 * s.net / s.startBalance);
                }
                return "Live: " + Money.signed(s.net);
            case RECORD: return s.wins + "W - " + s.losses + "L  (" + s.winRate() + "%)";
            case STREAK: return "Streak: " + s.streakText();
            case LAST: {
                if (s.history.isEmpty()) return "Last: -";
                Session.Entry e = s.history.get(s.history.size() - 1);
                boolean in = Session.IN.equals(e.kind);
                boolean sell = Session.SELL.equals(e.kind);
                if (Session.BUY.equals(e.kind)) {
                    return "Last buy: -" + Money.format(e.amount) + (s.namesVisible() && !e.who.isEmpty() ? " (" + e.who + ")" : "");
                }
                String who = (s.namesVisible() && !e.who.isEmpty()) ? (in ? " from " : " to ") + e.who : "";
                if (sell) return "Last sale: +" + Money.format(e.amount);
                return "Last: " + (in ? "+" : "-") + Money.format(e.amount) + who;
            }
            case TIME: return "Time: " + s.time();
            case WINBAR: return "Win rate: " + s.winRate() + "%";
            case GAMBLES: return "Gambles: " + s.total();
            case RECEIVED: return "Received: " + Money.format(s.received);
            case SENT: return "Sent: " + Money.format(s.sent);
            case SOLD: return "Sold: " + Money.format(s.sold);
            case SPENT: return "Spent: " + Money.format(s.spent);
            case AVGWIN: return "Avg win: " + Money.format(s.received / Math.max(1, s.wins));
            case AVGLOSS: return "Avg loss: " + Money.format(s.sent / Math.max(1, s.losses));
            case BIGWIN: return "Biggest in: " + Money.format(s.biggestIn);
            case BIGLOSS: return "Biggest out: " + Money.format(s.biggestOut);
            case PEAK: return "Peak: " + Money.signed(s.peak);
            case LOW: return "Low: " + Money.signed(s.low);
            case BEST: return "Best W" + s.bestWinStreak + " / Worst L" + s.worstLossStreak;
            case PERBET: return "Per gamble: " + Money.signed(s.net / Math.max(1, s.total()));
            case PHR: {
                if (s.elapsedMs < 60_000) return "Profit/hr: -";
                long perHour = Math.round(s.net * 3_600_000.0 / s.elapsedMs);
                return "Profit/hr: " + Money.signed(perHour);
            }
            case TOPPAYER: {
                Session.PlayerStat p = s.topPayer();
                if (p == null) return "Top payer: -";
                return "Top payer: " + (s.namesVisible() ? p.name : "Player") + " " + Money.format(p.in);
            }
            case BALANCE: return "Balance: " + (s.startBalance < 0 ? "-" : Money.format(s.startBalance + s.net));
            case GOALBAR: return "Goal: " + Money.signed(s.net) + " / " + Money.format(s.goal);
            case LIMIT: {
                if (s.lossLimit <= 0) return "Limit: off";
                return s.net <= -s.lossLimit ? "LOSS LIMIT HIT" : "Limit: -" + Money.format(s.lossLimit);
            }
            default: return "";
        }
    }

    private static int buildColor(W w, Session s) {
        switch (w) {
            case NET: case GRAPH: case PHR: case PERBET: return s.net > 0 ? GREEN : (s.net < 0 ? RED : WHITE);
            case STREAK: return s.streak > 0 ? GREEN : (s.streak < 0 ? RED : GRAY);
            case LAST: {
                if (s.history.isEmpty()) return GRAY;
                Session.Entry e = s.history.get(s.history.size() - 1);
                if (Session.SELL.equals(e.kind)) return AQUA;
                if (Session.BUY.equals(e.kind)) return GOLD;
                return Session.IN.equals(e.kind) ? GREEN : RED;
            }
            case RECEIVED: case BIGWIN: case AVGWIN: case PEAK: case TOPPAYER: return GREEN;
            case SENT: case BIGLOSS: case AVGLOSS: case LOW: case SPENT: return RED;
            case SOLD: case FORM: return AQUA;
            case WINBAR: return s.wins >= s.losses ? GREEN : RED;
            case GOALBAR: return (s.goal > 0 && s.net >= s.goal) ? GREEN : GOLD;
            case LIMIT: return (s.lossLimit > 0 && s.net <= -s.lossLimit) ? RED : GRAY;
            case BARS: case GAMBLES: return GOLD;
            case TIME: case BEST: return GRAY;
            case AUCTION: return AuctionPanel.ACCENT;
            case MUSIC: return MusicPanel.ACCENT;
            case CHAT: return ChatPanel.ACCENT;
            default: return WHITE;
        }
    }

    /** Draws the widget with its top-left corner at the current matrix origin. */
    public static void draw(W w, Session s, DrawContext ctx, TextRenderer tr) {
        int width = width(w, s, tr);
        int height = height(w);
        int iw = width - 14;
        int accent = color(w, s);
        int override = s.cfg(w).accent;
        if (override != 0) accent = 0xFF000000 | override;
        Ui.card(ctx, width, height, accent);
        switch (w) {
            case GRAPH:
                drawLabelled(ctx, tr, w, s, accent);
                lineChart(ctx, 7, 16, iw, height - 20, windowed(s.cumulative(), 80));
                break;
            case BARS:
                resultBars(ctx, 7, 3, iw, height - 6, s, 20);
                break;
            case FORM:
                ctx.drawTextWithShadow(tr, "Form", 7, 3, Ui.LABEL);
                pips(ctx, 7 + tr.getWidth("Form") + 5, 3, s, 10);
                break;
            case WINBAR:
                drawLabelled(ctx, tr, w, s, accent);
                splitBar(ctx, 7, 13, iw, 4, s.wins, s.losses);
                break;
            case GOALBAR:
                drawLabelled(ctx, tr, w, s, accent);
                progressBar(ctx, 7, 13, iw, 4, s.goal > 0 ? (double) s.net / s.goal : 0, accent);
                break;
            case LIMIT: {
                drawLabelled(ctx, tr, w, s, accent);
                double frac = s.lossLimit > 0 ? (double) Math.max(0, -s.net) / s.lossLimit : 0;
                int bar = frac >= 1 ? RED : (frac >= 0.5 ? GOLD : GREEN);
                progressBar(ctx, 7, 13, iw, 4, frac, bar);
                break;
            }
            case AUCTION:
                AuctionPanel.draw(ctx, tr, width, height);
                break;
            case MUSIC:
                MusicPanel.draw(ctx, tr, width, height);
                break;
            case CHAT:
                ChatPanel.draw(ctx, tr, width, height);
                break;
            default:
                drawLabelled(ctx, tr, w, s, accent);
                break;
        }
    }

    /** Draws "Label: value" with a dim label and a bright, coloured value. */
    private static void drawLabelled(DrawContext ctx, TextRenderer tr, W w, Session s, int accent) {
        Cache c = cache(w, s);
        if (c.label.isEmpty()) {
            ctx.drawTextWithShadow(tr, c.value, 7, 3, accent);
            return;
        }
        if (c.labelWidth < 0) c.labelWidth = tr.getWidth(c.label);
        ctx.drawTextWithShadow(tr, c.label, 7, 3, Ui.LABEL);
        ctx.drawTextWithShadow(tr, c.value, 7 + c.labelWidth, 3, accent);
    }

    // ================= chart helpers =================

    public static final int GREEN_DIM = 0x5555FF55;
    public static final int RED_DIM = 0x55FF5555;
    public static final int TRACK = 0x40FFFFFF;
    public static final int ZERO_LINE = 0x66FFFFFF;

    /** Filled line chart of a running profit series: faint grid, gradient fill, 2px line, glowing end dot. */
    public static void lineChart(DrawContext ctx, int x, int y, int w, int h, List<Long> pts) {
        if (w < 4 || h < 4) return;
        ctx.fill(x, y, x + w, y + h, 0x30000000);
        for (int g = 1; g < 4; g++) {
            int gy = y + h * g / 4;
            for (int gx = x; gx < x + w; gx += 4) ctx.fill(gx, gy, gx + 2, gy + 1, 0x18FFFFFF);
        }
        int n = pts.size();
        long min = 0, max = 0;
        for (long v : pts) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        long range = Math.max(1, max - min);
        int zeroY = y + (int) Math.round((double) max / range * (h - 1));
        ctx.fill(x, zeroY, x + w, zeroY + 1, ZERO_LINE);
        if (n < 2) {
            ctx.fill(x, zeroY - 1, x + w, zeroY + 1, 0xFF888888);
            return;
        }

        int prevY = -1, lastY = zeroY;
        boolean lastPos = true;
        for (int px = 0; px < w; px++) {
            double t = (double) px * (n - 1) / (w - 1);
            int i = (int) Math.floor(t);
            if (i >= n - 1) i = n - 2;
            double f = t - i;
            double val = pts.get(i) + (pts.get(i + 1) - pts.get(i)) * f;
            int vy = y + (int) Math.round((max - val) / range * (h - 1));
            boolean pos = val >= 0;
            int top = Math.min(vy, zeroY), bot = Math.max(vy, zeroY);
            if (pos) ctx.fillGradient(x + px, top, x + px + 1, bot + 1, 0x7055FF55, 0x0855FF55);
            else ctx.fillGradient(x + px, top, x + px + 1, bot + 1, 0x08FF5555, 0x70FF5555);
            int y1 = prevY < 0 ? vy : Math.min(prevY, vy);
            int y2 = prevY < 0 ? vy : Math.max(prevY, vy);
            ctx.fill(x + px, y1, x + px + 1, y2 + 2, pos ? GREEN : RED);
            prevY = vy;
            lastY = vy;
            lastPos = pos;
        }
        int dot = lastPos ? GREEN : RED;
        int dx = x + w - 3;
        ctx.fill(dx - 1, lastY - 2, dx + 4, lastY + 3, (dot & 0x00FFFFFF) | 0x50000000);
        ctx.fill(dx, lastY - 1, dx + 3, lastY + 2, 0xFFFFFFFF);
    }

    /** One bar per payment: received goes up (green), sent goes down (red), height = size of payment. */
    public static void resultBars(DrawContext ctx, int x, int y, int w, int h, Session s, int count) {
        List<Session.Entry> res = s.results();
        int mid = y + h / 2;
        ctx.fill(x, mid, x + w, mid + 1, ZERO_LINE);
        if (res.isEmpty() || count <= 0) return;
        List<Session.Entry> sub = res.subList(Math.max(0, res.size() - count), res.size());
        long max = 1;
        for (Session.Entry e : sub) max = Math.max(max, e.amount);
        int slot = Math.max(2, w / count);
        int half = Math.max(1, h / 2 - 1);
        int offset = count - sub.size();
        for (int i = 0; i < sub.size(); i++) {
            Session.Entry e = sub.get(i);
            int bx = x + (offset + i) * slot;
            int hh = Math.max(1, (int) Math.round((double) e.amount / max * half));
            if (Session.IN.equals(e.kind)) {
                ctx.fill(bx, mid - hh, bx + slot - 1, mid, GREEN);
            } else {
                ctx.fill(bx, mid + 1, bx + slot - 1, mid + 1 + hh, RED);
            }
        }
    }

    /** Green/red split bar for wins vs losses. */
    public static void splitBar(DrawContext ctx, int x, int y, int w, int h, int wins, int losses) {
        ctx.fill(x, y, x + w, y + h, TRACK);
        int total = wins + losses;
        if (total <= 0) return;
        int gw = (int) Math.round((double) w * wins / total);
        ctx.fill(x, y, x + gw, y + h, GREEN);
        ctx.fill(x + gw, y, x + w, y + h, RED);
    }

    /** Rounded progress bar, frac clamped to 0..1. */
    public static void progressBar(DrawContext ctx, int x, int y, int w, int h, double frac, int color) {
        Ui.bar(ctx, x, y, w, h, frac, color, TRACK);
    }

    /** Last N results as little coloured squares, newest on the right. */
    public static void pips(DrawContext ctx, int x, int y, Session s, int count) {
        List<Session.Entry> res = s.results();
        List<Session.Entry> sub = res.subList(Math.max(0, res.size() - count), res.size());
        int offset = count - sub.size();
        for (int i = 0; i < sub.size(); i++) {
            int px = x + (offset + i) * 9;
            ctx.fill(px, y, px + 7, y + 7, Session.IN.equals(sub.get(i).kind) ? GREEN : RED);
        }
    }
}
