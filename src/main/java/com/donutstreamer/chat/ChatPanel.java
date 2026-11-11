package com.donutstreamer.chat;

import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import com.donutstreamer.Ui;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen Twitch / YouTube chat (a normal HUD widget: draggable, scalable, toggleable).
 * <p>
 * Messages are word-wrapped to the panel width, so a long message is shown in full instead of being cut off with
 * "...". The newest messages sit at the bottom and older ones scroll off the top. Width, height, lines per
 * message, fading, spacing, text colour and the header / marker / shadow options are set in /ds settings.
 * <p>
 * Cost: each message is wrapped once (and again only if the width or an option changes) and cached; each frame
 * only draws the cached strings.
 */
public final class ChatPanel {
    private ChatPanel() {}

    public static final int DEFAULT_WIDTH = 230;
    public static final int DEFAULT_HEIGHT = 100;
    public static final int ACCENT = 0xFF9146FF;

    /** Message text colours selectable in settings. */
    public static final int[] TEXT_COLORS = {0xFFE6EDF3, 0xFFFFFFFF, 0xFFFFF3C4, 0xFFB9F2FF, 0xFFC9D1D9};
    public static final String[] TEXT_COLOR_NAMES = {"Soft white", "White", "Cream", "Ice blue", "Light gray"};

    private static final int LABEL = 0xFF8B949E;
    private static final int TWITCH = 0xFF9146FF;
    private static final int YOUTUBE = 0xFFFF3B3B;
    private static final int NAME_MAX = 78;
    private static final int LINE_H = 9;
    private static final int TEXT_LEFT = 16;     // x of the name and of wrapped continuation lines

    private static final ChatMessage[] VIEW = new ChatMessage[40];
    private static final List<String> WRAP = new ArrayList<>();
    private static String hint = "";
    private static String hintFor = null;

    private static Session session() {
        return DonutStreamerClient.session;
    }

    public static int width(TextRenderer tr) {
        Session s = session();
        return s == null ? DEFAULT_WIDTH : s.chatWidth;
    }

    public static int height() {
        Session s = session();
        return s == null ? DEFAULT_HEIGHT : s.chatHeight;
    }

    private static String fit(TextRenderer tr, String s, int maxWidth) {
        if (tr.getWidth(s) <= maxWidth) return s;
        return tr.trimToWidth(s, Math.max(0, maxWidth - tr.getWidth("..."))) + "...";
    }

    /** Word-wraps text; the first line may be narrower (it sits after the name). Overlong words are split. */
    private static String[] wrap(TextRenderer tr, String text, int firstW, int nextW, int maxLines) {
        WRAP.clear();
        int avail = Math.max(24, firstW);
        nextW = Math.max(24, nextW);
        StringBuilder cur = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            int sp = text.indexOf(' ', i);
            String word = sp < 0 ? text.substring(i) : text.substring(i, sp);
            i = sp < 0 ? n : sp + 1;
            if (word.isEmpty()) continue;
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (tr.getWidth(test) <= avail) {
                cur.setLength(0);
                cur.append(test);
                continue;
            }
            if (cur.length() > 0) {
                WRAP.add(cur.toString());
                cur.setLength(0);
                avail = nextW;
            }
            while (tr.getWidth(word) > avail) {
                String piece = tr.trimToWidth(word, avail);
                if (piece.isEmpty()) piece = word.substring(0, 1);
                WRAP.add(piece);
                word = word.substring(piece.length());
                avail = nextW;
            }
            cur.append(word);
        }
        if (cur.length() > 0) WRAP.add(cur.toString());
        if (WRAP.isEmpty()) WRAP.add("");
        if (maxLines > 0 && WRAP.size() > maxLines) {
            while (WRAP.size() > maxLines) WRAP.remove(WRAP.size() - 1);
            WRAP.set(maxLines - 1, fit(tr, WRAP.get(maxLines - 1) + "...", Math.max(24, maxLines == 1 ? firstW : nextW)));
        }
        return WRAP.toArray(new String[0]);
    }

    private static void layout(TextRenderer tr, ChatMessage m, Session s, int w) {
        int key = (w << 8) | (s.chatMaxLines << 1) | (s.chatShowMarker ? 1 : 0);
        if (m.laidOutFor == key) return;
        m.nameShown = fit(tr, m.name, NAME_MAX);
        m.nameWidth = tr.getWidth(m.nameShown);
        int left = s.chatShowMarker ? TEXT_LEFT : 8;
        int textX = left + m.nameWidth + tr.getWidth(": ");
        m.lines = wrap(tr, m.text, w - textX - 7, w - left - 7, s.chatMaxLines);
        m.laidOutFor = key;
    }

    private static void text(DrawContext ctx, TextRenderer tr, String t, int x, int y, int color, boolean shadow) {
        if (shadow) ctx.drawTextWithShadow(tr, t, x, y, color);
        else ctx.drawText(tr, t, x, y, color, false);
    }

    /** Draws inside the widget box (origin = top-left; the card background is already drawn). */
    public static void draw(DrawContext ctx, TextRenderer tr, int w, int h) {
        Session s = session();
        if (s == null) return;
        long now = System.currentTimeMillis();
        boolean shadow = s.chatShadow;

        // header: marker per connected platform, title
        int top = 3;
        if (s.chatShowHeader) {
            ctx.fill(2, 1, w - 2, 13, 0x12FFFFFF);
            int mx = 8;
            if (StreamChat.twitchOn()) {
                ctx.fill(mx, 5, mx + 4, 9, TWITCH);
                mx += 6;
            }
            if (StreamChat.youtubeOn()) {
                ctx.fill(mx, 5, mx + 4, 9, YOUTUBE);
                mx += 6;
            }
            if (mx == 8) {
                ctx.fill(mx, 5, mx + 4, 9, 0x55FFFFFF);
                mx += 6;
            }
            ctx.drawTextWithShadow(tr, "STREAM CHAT", mx + 2, 3, 0xFFB794F6);
            ctx.fill(6, 13, w - 6, 14, 0x18FFFFFF);
            top = 16;
        }

        int n = StreamChat.newest(VIEW);
        if (n == 0) {
            String status = StreamChat.configured() ? StreamChat.statusLine() : "Set up: /ds chat twitch <channel>";
            if (!status.equals(hintFor)) {
                hintFor = status;
                hint = fit(tr, status, w - 16);
            }
            ctx.drawTextWithShadow(tr, hint, 8, top + 4, LABEL);
            if (!StreamChat.configured()) {
                ctx.drawTextWithShadow(tr, "or /ds chat youtube <link>", 8, top + 16, LABEL);
            }
            return;
        }

        int gap = s.chatLineGap;
        int bottom = h - 4;
        int avail = bottom - top;
        int step = LINE_H + gap;

        // pick the newest messages that fit (VIEW is oldest first)
        int used = 0, first = n;
        for (int i = n - 1; i >= 0; i--) {
            ChatMessage m = VIEW[i];
            layout(tr, m, s, w);
            int mh = m.lines.length * step + 1;
            if (used + mh > avail && first != n) break;
            used += mh;
            first = i;
            if (used >= avail) break;
        }

        int textColor = TEXT_COLORS[s.chatTextColor];
        int fade = s.chatFadeSec;
        int left = s.chatShowMarker ? TEXT_LEFT : 8;
        int y = Math.max(top, bottom - used);
        int maxBottom = bottom;
        for (int i = first; i < n; i++) {
            ChatMessage m = VIEW[i];
            long age = now - m.time;
            int alpha = fade <= 0 ? 0xFF : (age > fade * 1000L ? 0x66 : (age > fade * 450L ? 0xAA : 0xFF));
            int nameColor = s.chatViewerColors ? m.color : ACCENT;
            String[] lines = m.lines;
            for (int l = 0; l < lines.length; l++) {
                if (y + LINE_H > maxBottom + 1) break;   // a message taller than the panel is clipped, never drawn outside it
                if (l == 0) {
                    if (s.chatShowMarker) {
                        ctx.fill(8, y + 2, 12, y + 6, Ui.withAlpha(m.platform == ChatMessage.YOUTUBE ? YOUTUBE : TWITCH, alpha));
                    }
                    text(ctx, tr, m.nameShown, left, y, Ui.withAlpha(nameColor, alpha), shadow);
                    text(ctx, tr, ": ", left + m.nameWidth, y, Ui.withAlpha(LABEL, alpha), shadow);
                    text(ctx, tr, lines[0], left + m.nameWidth + tr.getWidth(": "), y, Ui.withAlpha(textColor, alpha), shadow);
                } else {
                    text(ctx, tr, lines[l], left, y, Ui.withAlpha(textColor, alpha), shadow);
                }
                y += step;
            }
            y += 1;
        }
        for (int i = 0; i < n; i++) VIEW[i] = null;
    }
}
