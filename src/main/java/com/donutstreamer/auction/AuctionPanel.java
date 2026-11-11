package com.donutstreamer.auction;

import com.donutstreamer.Money;
import com.donutstreamer.Ui;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import org.joml.Matrix3x2fStack;

/**
 * The live auction HUD panel: a circular countdown ring (with 12 tick marks and a bright head dot) next to the
 * item, top bidder and a big top bid that flashes when someone outbids. Green, then amber at 30s, then a
 * flashing red ring and border for the last 10s. With the stream-delay option on, a second purple countdown
 * (12s) follows, during which bids still count. It is a normal HUD widget (draggable, scalable, toggleable).
 * Text is rebuilt only when the auction changes or the displayed second ticks over; the ring sweeps smoothly
 * every frame using flat fills only.
 */
public final class AuctionPanel {
    private AuctionPanel() {}

    public static final int WIDTH = 200;
    public static final int HEIGHT = 68;
    public static final int ACCENT = 0xFF3B82F6;

    private static final int LABEL = 0xFF8B949E;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BIDDER = 0xFF7EC8FF;
    private static final int BID = 0xFF55FF7F;
    private static final int TIME_SAFE = 0xFF55FF7F;
    private static final int TIME_WARN = 0xFFFFC107;
    private static final int TIME_CRITICAL = 0xFFFF5555;
    private static final int TIME_DELAY = 0xFFB388FF;   // stream-delay countdown
    private static final int TRACK = 0x26FFFFFF;

    private static final int RING_CX = 34, RING_CY = 41, RING_OUTER = 22, RING_INNER = 18;
    private static final int TEXT_X = 64;

    private static final int TICKS = 12;
    private static final int TICK_R = 24;
    private static final int[] TICK_X = new int[TICKS];
    private static final int[] TICK_Y = new int[TICKS];
    private static final long BID_FLASH_MS = 700;

    static {
        for (int i = 0; i < TICKS; i++) {
            double a = i * 2 * Math.PI / TICKS;
            TICK_X[i] = RING_CX + (int) Math.round(Math.sin(a) * TICK_R);
            TICK_Y[i] = RING_CY - (int) Math.round(Math.cos(a) * TICK_R);
        }
    }

    // cache
    private static long cacheRev = -1;
    private static long cacheSec = -2;
    private static boolean cacheActive = false;
    private static boolean cacheDelay = false;
    private static boolean cacheValid = false;

    private static String titleText = "AUCTION";
    private static String nameLine = "";
    private static String clockText = "";
    private static String minText = "";
    private static String bidderLine = "";
    private static String bidText = "";
    private static boolean hasBid = false;
    private static ItemStack stack = null;
    private static long lastBid = 0;
    private static long bidFlashStart = 0;

    public static int width(TextRenderer tr) {
        return WIDTH;
    }

    private static void refresh(TextRenderer tr) {
        AuctionState st = AuctionState.INSTANCE;
        boolean act = st.isActive();                 // may end the auction, so read the revision after it
        long rev = st.revision();
        boolean delay = act && st.isInDelay();
        long remaining = act ? (delay ? st.getDelayRemainingMillis() : st.getRemainingMillis()) : 0;
        long sec = act ? (remaining + 999) / 1000 : -1;
        if (cacheValid && rev == cacheRev && sec == cacheSec && act == cacheActive && delay == cacheDelay) return;

        cacheValid = true;
        cacheRev = rev;
        cacheSec = sec;
        cacheActive = act;
        cacheDelay = delay;
        titleText = delay ? "STREAM DELAY" : "AUCTION";

        String name;
        if (act) {
            stack = st.getCurrentStack();
            name = st.getCurrentItemName();
            clockText = TimeParse.clock(sec);
            long min = st.getMinimumBid();
            minText = min > 0 ? "min $" + Money.format(min) : "no minimum";
            hasBid = st.hasBid();
            long topBid = st.getHighestBid();
            if (topBid != lastBid) {
                if (topBid > 0) bidFlashStart = System.currentTimeMillis();
                lastBid = topBid;
            }
            bidderLine = hasBid ? st.getHighestBidder() : "no bids yet";
            bidText = hasBid ? "$" + Money.format(st.getHighestBid()) : "-";
        } else {
            // Shown as a ghost while you position the panel in the HUD editor.
            stack = null;
            name = "No active auction";
            clockText = "--:--";
            minText = "min bid";
            hasBid = false;
            bidderLine = "-";
            bidText = "-";
            lastBid = 0;
        }
        nameLine = fit(tr, name, WIDTH - (stack != null ? TEXT_X + 20 : TEXT_X) - 8);
        bidderLine = fit(tr, bidderLine, WIDTH - TEXT_X - 8 - tr.getWidth("Top: "));
    }

    private static String fit(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) return s;
        return tr.trimToWidth(s, maxWidth - tr.getWidth("...")) + "...";
    }

    /** Draws inside the widget box (origin = top-left; the card background is already drawn). */
    public static void draw(DrawContext ctx, TextRenderer tr, int w, int h) {
        refresh(tr);
        AuctionState st = AuctionState.INSTANCE;
        boolean act = cacheActive;
        long now = System.currentTimeMillis();

        boolean delay = act && cacheDelay;
        long remaining = act ? (delay ? st.getDelayRemainingMillis() : st.getRemainingMillis()) : 0;
        long total = act ? Math.max(1, delay ? st.getDelayMillis() : st.getDurationMillis()) : 1;
        double frac = act ? (double) remaining / total : 0;
        boolean critical = act && !delay && remaining <= 10_000;
        int timeColor = !act ? LABEL
                : (delay ? TIME_DELAY : (critical ? TIME_CRITICAL : (remaining <= 30_000 ? TIME_WARN : TIME_SAFE)));
        double fast = 0.5 + 0.5 * Math.sin(now / 120.0);

        // header band: pulsing live dot, title, minimum bid
        ctx.fill(2, 1, w - 2, 13, 0x12FFFFFF);
        int pulse = act ? 120 + (int) (135 * (0.5 + 0.5 * Math.sin(now / 260.0))) : 70;
        ctx.fill(8, 5, 12, 9, Ui.withAlpha(act ? (delay ? TIME_DELAY : TIME_CRITICAL) : LABEL, pulse));
        ctx.drawTextWithShadow(tr, titleText, 16, 3, delay ? TIME_DELAY : 0xFF6FA8FF);
        ctx.drawTextWithShadow(tr, minText, w - 8 - tr.getWidth(minText), 3, LABEL);
        ctx.fill(6, 13, w - 6, 14, 0x18FFFFFF);

        // border pulse: red for the final 10 seconds, purple during the stream-delay countdown
        if (critical || delay) {
            int edge = Ui.withAlpha(delay ? TIME_DELAY : TIME_CRITICAL, 60 + (int) (140 * fast));
            ctx.fill(1, 0, w - 1, 1, edge);
            ctx.fill(1, h - 1, w - 1, h, edge);
            ctx.fill(0, 1, 1, h - 1, edge);
            ctx.fill(w - 1, 1, w, h - 1, edge);
        }

        // tick marks around the ring: lit while that part of the time is still left
        for (int i = 0; i < TICKS; i++) {
            boolean lit = act && (double) i / TICKS < frac;
            int c = lit ? Ui.withAlpha(timeColor, 0xB0) : 0x30FFFFFF;
            ctx.fill(TICK_X[i] - 1, TICK_Y[i] - 1, TICK_X[i] + 1, TICK_Y[i] + 1, c);
        }

        // countdown ring (flashes in the last 10 seconds) + bright head dot where the arc ends
        int arc = critical ? Ui.withAlpha(timeColor, 150 + (int) (105 * fast)) : timeColor;
        Ui.ring(ctx, RING_CX, RING_CY, RING_OUTER, RING_INNER, frac, arc, TRACK);
        if (act && frac > 0.01 && frac < 0.995) {
            double a = frac * 2 * Math.PI;
            int hr = (RING_OUTER + RING_INNER) / 2;
            int hx = RING_CX + (int) Math.round(Math.sin(a) * hr);
            int hy = RING_CY - (int) Math.round(Math.cos(a) * hr);
            ctx.fill(hx - 2, hy - 2, hx + 2, hy + 2, Ui.withAlpha(WHITE, 0x70));
            ctx.fill(hx - 1, hy - 1, hx + 1, hy + 1, WHITE);
        }
        ctx.drawTextWithShadow(tr, clockText, RING_CX - tr.getWidth(clockText) / 2, RING_CY - 4, timeColor);

        // item in a little slot
        int nameX = TEXT_X;
        if (stack != null) {
            Ui.roundRect(ctx, TEXT_X - 2, 15, 20, 20, 0x30FFFFFF);
            ctx.drawItem(stack, TEXT_X, 17);
            nameX = TEXT_X + 22;
        }
        ctx.drawTextWithShadow(tr, nameLine, nameX, 21, WHITE);

        // top bidder + big bid (the bid flashes white when someone outbids)
        ctx.drawTextWithShadow(tr, "Top: ", TEXT_X, 38, LABEL);
        ctx.drawTextWithShadow(tr, bidderLine, TEXT_X + tr.getWidth("Top: "), 38, hasBid ? BIDDER : LABEL);

        int bidColor = hasBid ? BID : LABEL;
        long since = now - bidFlashStart;
        if (hasBid && since >= 0 && since < BID_FLASH_MS) {
            bidColor = Ui.lerp(WHITE, BID, since / (float) BID_FLASH_MS);
        }
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) TEXT_X, 50f);
        m.scale(1.5f, 1.5f);
        ctx.drawTextWithShadow(tr, bidText, 0, 0, bidColor);
        m.popMatrix();
    }
}
