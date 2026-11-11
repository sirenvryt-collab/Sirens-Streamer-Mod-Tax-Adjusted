package com.donutstreamer;

import com.donutstreamer.chat.ChatPanel;
import com.donutstreamer.chat.StreamChat;
import com.donutstreamer.music.YtMusic;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * /ds settings: one place for every option. Tabs on the left (HUD, Elements, Chat, Music, Stream, Tracker),
 * rows on the right (switches, sliders, pickers, buttons). Every change is applied live and saved.
 * <p>
 * The Elements tab lists every HUD element; pick one to change its own size, background opacity and accent colour,
 * or to switch it on and off. Mouse input is polled (like the HUD editor) so this screen depends on as little of
 * the game's GUI input API as possible; only the scroll wheel goes through the normal screen callback.
 */
public final class SettingsScreen extends Screen {
    private static final int PANEL = 0xF00D1117;
    private static final int BORDER = 0xFF30363D;
    private static final int ROW_HOVER = 0x26FFFFFF;
    private static final int ON = 0xFF2EA043;
    private static final int OFF = 0xFF30363D;
    private static final int SIDE = 84;
    private static final int RH = 18;

    private static final String[] ACCENT_NAMES = {"Automatic", "Gold", "Green", "Red", "Blue", "Purple", "Aqua", "Pink", "Orange", "White"};
    private static final int[] ACCENT_RGB = {0, 0xFFD700, 0x55FF55, 0xFF5555, 0x3B82F6, 0x9146FF, 0x55FFFF, 0xFF7AC6, 0xFFA94D, 0xFFFFFF};

    private enum Tab {
        HUD("HUD"), ELEMENTS("Elements"), CHAT("Chat"), MUSIC("Music"), STREAM("Stream"), TRACKER("Tracker");
        final String title;
        Tab(String t) { title = t; }
    }

    private enum Kind { HEADER, INFO, TOGGLE, SLIDER, CYCLE, ACTION }

    private static final class Row {
        Kind kind;
        String label = "";
        Supplier<String> text;          // INFO text, or the value shown on the right of CYCLE / ACTION rows
        Supplier<Boolean> on;           // TOGGLE state
        Runnable action;                // TOGGLE flip, CYCLE next, ACTION click
        float min, max, step;           // SLIDER
        Supplier<Float> get;
        Consumer<Float> set;
        Function<Float, String> fmt;
        Runnable commit;                // SLIDER released (optional)
    }

    private Tab tab = Tab.HUD;
    private Widgets.W selected = null;       // Elements tab: the element being edited
    private List<Row> rows = new ArrayList<>();
    private double scroll = 0;
    private boolean prevDown = false;
    private Row activeSlider = null;
    private float pendingVolume = 50;

    public SettingsScreen() {
        super(Text.literal("Donut Streamer Settings"));
    }

    private static Session S() {
        return DonutStreamerClient.session;
    }

    @Override
    protected void init() {
        prevDown = leftDown();
        activeSlider = null;
        rebuild();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static boolean leftDown() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    // ================= row builders =================

    private static Row header(String t) {
        Row r = new Row();
        r.kind = Kind.HEADER;
        r.label = t;
        return r;
    }

    private static Row info(Supplier<String> t) {
        Row r = new Row();
        r.kind = Kind.INFO;
        r.text = t;
        return r;
    }

    private static Row info(String t) {
        return info(() -> t);
    }

    private static Row toggle(String label, Supplier<Boolean> on, Runnable flip) {
        Row r = new Row();
        r.kind = Kind.TOGGLE;
        r.label = label;
        r.on = on;
        r.action = flip;
        return r;
    }

    private static Row slider(String label, float min, float max, float step, Supplier<Float> get,
                              Consumer<Float> set, Function<Float, String> fmt) {
        Row r = new Row();
        r.kind = Kind.SLIDER;
        r.label = label;
        r.min = min;
        r.max = max;
        r.step = step;
        r.get = get;
        r.set = set;
        r.fmt = fmt;
        return r;
    }

    private static Row cycle(String label, Supplier<String> value, Runnable next) {
        Row r = new Row();
        r.kind = Kind.CYCLE;
        r.label = label;
        r.text = value;
        r.action = next;
        return r;
    }

    private static Row button(String label, Supplier<String> value, Runnable click) {
        Row r = new Row();
        r.kind = Kind.ACTION;
        r.label = label;
        r.text = value;
        r.action = click;
        return r;
    }

    private static Row button(String label, Runnable click) {
        return button(label, null, click);
    }

    private static String x2(float v) {
        return String.format(Locale.ROOT, "%.2fx", v);
    }

    // ================= tab contents =================

    private void rebuild() {
        List<Row> r = new ArrayList<>();
        switch (tab) {
            case HUD -> buildHud(r);
            case ELEMENTS -> buildElements(r);
            case CHAT -> buildChat(r);
            case MUSIC -> buildMusic(r);
            case STREAM -> buildStream(r);
            case TRACKER -> buildTracker(r);
        }
        rows = r;
        scroll = 0;
        activeSlider = null;
    }

    private void buildHud(List<Row> r) {
        Session s = S();
        r.add(header("HUD"));
        r.add(toggle("Show HUD", () -> s.hudVisible, () -> s.hudVisible = !s.hudVisible));
        r.add(slider("Overall size", 0.5f, 3.0f, 0.05f, () -> s.scale, v -> s.scale = v, SettingsScreen::x2));
        r.add(toggle("Sirens watermark", () -> s.watermark, () -> s.watermark = !s.watermark));
        r.add(toggle("Show player names", () -> s.showNames, () -> s.showNames = !s.showNames));
        r.add(toggle("Auction / music previews in chat", () -> s.chatPreviews, () -> s.chatPreviews = !s.chatPreviews));
        r.add(header("Layout"));
        r.add(button("Turn ALL elements on", () -> { for (Widgets.W w : Widgets.ALL) s.cfg(w).on = true; }));
        r.add(button("Turn ALL elements off", () -> { for (Widgets.W w : Widgets.ALL) s.cfg(w).on = false; }));
        r.add(button("Reset layout (positions and sizes)", () -> { s.widgets.clear(); s.scale = 1.0f; }));
        r.add(info("Each element has its own size, opacity and colour: see the Elements tab."));
        r.add(info("In chat (T): drag to move (snaps to edges, hold Shift to move freely)."));
        r.add(info("Right-drag up/down over an element to resize it."));
    }

    private void buildElements(List<Row> r) {
        Session s = S();
        if (selected == null) {
            r.add(header("Pick an element to edit"));
            for (Widgets.W w : Widgets.ALL) {
                Session.WidgetCfg c = s.cfg(w);
                r.add(button(w.label, () -> (c.on ? "on" : "off") + "   " + x2(c.scale),
                        () -> { selected = w; rebuild(); }));
            }
            return;
        }
        Widgets.W w = selected;
        Session.WidgetCfg c = s.cfg(w);
        r.add(button("< Back to all elements", () -> { selected = null; rebuild(); }));
        r.add(header(w.label));
        r.add(toggle("Show this element", () -> c.on, () -> c.on = !c.on));
        r.add(slider("Size", 0.4f, 4.0f, 0.05f, () -> c.scale, v -> c.scale = v, SettingsScreen::x2));
        r.add(slider("Background opacity", 0f, 100f, 5f, () -> (float) c.bgAlpha, v -> c.bgAlpha = Math.round(v),
                v -> Math.round(v) + "%"));
        r.add(cycle("Accent colour", () -> ACCENT_NAMES[accentIndex(c.accent)], () -> {
            int i = (accentIndex(c.accent) + 1) % ACCENT_RGB.length;
            c.accent = ACCENT_RGB[i];
        }));
        r.add(button("Reset this element (position, size, colour)", () -> { s.widgets.remove(w.id); rebuild(); }));
        if (w == Widgets.W.CHAT) r.add(info("Width, height and wrapping are in the Chat tab."));
        if (w == Widgets.W.MUSIC) r.add(info("Cover art, equalizer and ad muting are in the Music tab."));
        r.add(info("Size is multiplied with the Overall size from the HUD tab."));
    }

    private static int accentIndex(int rgb) {
        for (int i = 0; i < ACCENT_RGB.length; i++) if (ACCENT_RGB[i] == (rgb & 0xFFFFFF)) return i;
        return 0;
    }

    private void buildChat(List<Row> r) {
        Session s = S();
        r.add(header("Stream chat panel"));
        r.add(info(() -> StreamChat.configured() ? StreamChat.statusLine() : "Not set up: /ds chat twitch <channel>"));
        r.add(slider("Width", 140f, 520f, 10f, () -> (float) s.chatWidth, v -> s.chatWidth = Math.round(v),
                v -> Math.round(v) + " px"));
        r.add(slider("Height", 50f, 400f, 10f, () -> (float) s.chatHeight, v -> s.chatHeight = Math.round(v),
                v -> Math.round(v) + " px"));
        r.add(slider("Lines per message", 0f, 12f, 1f, () -> (float) s.chatMaxLines, v -> s.chatMaxLines = Math.round(v),
                v -> Math.round(v) == 0 ? "Full message" : Math.round(v) + (Math.round(v) == 1 ? " line" : " lines")));
        r.add(slider("Dim old messages after", 0f, 300f, 5f, () -> (float) s.chatFadeSec, v -> s.chatFadeSec = Math.round(v),
                v -> Math.round(v) == 0 ? "Never" : Math.round(v) + " s"));
        r.add(slider("Line spacing", 0f, 4f, 1f, () -> (float) s.chatLineGap, v -> s.chatLineGap = Math.round(v),
                v -> Math.round(v) + " px"));
        r.add(cycle("Message text colour", () -> ChatPanel.TEXT_COLOR_NAMES[s.chatTextColor],
                () -> s.chatTextColor = (s.chatTextColor + 1) % ChatPanel.TEXT_COLORS.length));
        r.add(toggle("Header bar", () -> s.chatShowHeader, () -> s.chatShowHeader = !s.chatShowHeader));
        r.add(toggle("Platform marker (Twitch / YouTube)", () -> s.chatShowMarker, () -> s.chatShowMarker = !s.chatShowMarker));
        r.add(toggle("Use each viewer's name colour", () -> s.chatViewerColors, () -> s.chatViewerColors = !s.chatViewerColors));
        r.add(toggle("Text shadow", () -> s.chatShadow, () -> s.chatShadow = !s.chatShadow));
        r.add(toggle("Hide !commands", StreamChat::hideCommands, StreamChat::toggleHideCommands));
        r.add(header("Actions"));
        r.add(button("Clear messages", StreamChat::clearMessages));
        r.add(button("Disconnect chat", StreamChat::disconnectAll));
        r.add(info("Connect with /ds chat twitch <channel> or /ds chat youtube <link>."));
    }

    private void buildMusic(List<Row> r) {
        Session s = S();
        r.add(header("YouTube / YouTube Music"));
        r.add(info(() -> {
            YtMusic.Now n = YtMusic.now();
            if (n == null) return YtMusic.statusText();
            return (n.ad() ? "Ad playing: " : "Now: ") + n.title();
        }));
        r.add(toggle("Mute the tab during ads", YtMusic::muteAds, YtMusic::toggleMuteAds));
        r.add(toggle("Show cover art", () -> s.musicCover, () -> s.musicCover = !s.musicCover));
        r.add(toggle("Show equalizer", () -> s.musicEqualizer, () -> s.musicEqualizer = !s.musicEqualizer));
        r.add(header("Controls"));
        r.add(button("Play / pause", YtMusic::togglePlay));
        r.add(button("Next track", YtMusic::next));
        r.add(button("Previous track", YtMusic::previous));
        r.add(button("Mute / unmute tab", YtMusic::toggleTabMute));
        Row vol = slider("Volume", 0f, 100f, 5f, () -> {
            YtMusic.Now n = YtMusic.now();
            return activeSlider != null ? pendingVolume : (n != null ? (float) n.volume() : pendingVolume);
        }, v -> pendingVolume = v, v -> Math.round(v) + "%");
        vol.commit = () -> YtMusic.volume(Math.round(pendingVolume));
        r.add(vol);
        r.add(header("Setup (once)"));
        r.add(button("Write the userscript and show the steps in chat", YtMusic::setup));
        r.add(info("Install Tampermonkey, then add donutstreamer/youtube-music.user.js from your game folder."));
        r.add(info("No Premium or login needed. The mod only listens on 127.0.0.1."));
    }

    private void buildStream(List<Row> r) {
        Session s = S();
        r.add(header("Stream safety"));
        r.add(toggle("Stream mode (master switch)", () -> s.streamMode, () -> s.streamMode = !s.streamMode));
        r.add(toggle("Hide F3 coordinates", () -> s.streamHideCoords, () -> s.streamHideCoords = !s.streamHideCoords));
        r.add(toggle("Blank coordinates in chat", () -> s.streamHideChatCoords, () -> s.streamHideChatCoords = !s.streamHideChatCoords));
        r.add(toggle("Hide player names on the HUD", () -> s.streamHideNames, () -> s.streamHideNames = !s.streamHideNames));
        r.add(header("Nametag"));
        r.add(toggle("Own nametag in F5", () -> s.selfNametag, () -> s.selfNametag = !s.selfNametag));
        r.add(toggle("Show rank on own nametag", () -> s.selfNametagRank, () -> s.selfNametagRank = !s.selfNametagRank));
        r.add(info("Stream mode must be on for the hiding options to apply."));
    }

    private void buildTracker(List<Row> r) {
        Session s = S();
        r.add(header("Tracking"));
        r.add(toggle("Tag doubled payments", () -> s.doubleTag, () -> s.doubleTag = !s.doubleTag));
        r.add(toggle("Show live profit as % of balance", () -> s.percentMode, () -> s.percentMode = !s.percentMode));
        r.add(toggle("Count sells in profit", () -> s.includeSells, () -> {
            s.includeSells = !s.includeSells;
            s.recompute();
        }));
        r.add(toggle("Count auction bids in tracker", () -> s.trackAuctionPayments, () -> s.trackAuctionPayments = !s.trackAuctionPayments));
        r.add(toggle("New session every midnight", () -> s.dailyReset, () -> {
            s.dailyReset = !s.dailyReset;
            s.sessionDay = java.time.LocalDate.now().toString();
        }));
        r.add(toggle("Sounds", () -> s.sound, () -> s.sound = !s.sound));
        r.add(header("Auctions"));
        r.add(toggle("12 s stream-delay countdown", () -> s.auctionStreamDelay, () -> s.auctionStreamDelay = !s.auctionStreamDelay));
        r.add(toggle("Auctions on any server (testing)", () -> s.auctionAnyServer, () -> s.auctionAnyServer = !s.auctionAnyServer));
        r.add(info("Limits, goal, tilt and rakeback: /ds limit, /ds goal, /ds tilt, /ds rakeback."));
    }

    // ================= input + drawing =================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll -= verticalAmount * RH * 2;
        return true;
    }

    private static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String fit(String t, int maxW) {
        TextRenderer tr = this.textRenderer;
        if (tr.getWidth(t) <= maxW) return t;
        return tr.trimToWidth(t, Math.max(0, maxW - tr.getWidth("..."))) + "...";
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        Session s = S();
        TextRenderer tr = this.textRenderer;

        int pw = Math.min(this.width - 16, 440);
        int ph = Math.min(this.height - 16, 300);
        int px = (this.width - pw) / 2;
        int py = (this.height - ph) / 2;
        int cx = px + SIDE + 6;                 // content left
        int cw = px + pw - 8 - cx;              // content width
        int top = py + 26;
        int bottom = py + ph - 6;
        int viewH = bottom - top;

        boolean down = leftDown();
        boolean pressed = down && !prevDown;
        boolean released = !down && prevDown;
        prevDown = down;

        double maxScroll = Math.max(0, rows.size() * RH - viewH);
        scroll = Math.max(0, Math.min(maxScroll, scroll));

        // ---- input ----
        boolean changed = false;
        if (pressed) {
            if (in(mx, my, px + pw - 16, py + 4, 12, 12)) {
                MinecraftClient.getInstance().setScreen(null);
                return;
            }
            Tab[] tabs = Tab.values();
            for (int i = 0; i < tabs.length; i++) {
                if (in(mx, my, px + 6, py + 28 + i * 17, SIDE - 10, 15) && tabs[i] != tab) {
                    tab = tabs[i];
                    selected = null;
                    rebuild();
                    break;
                }
            }
            if (in(mx, my, cx, top, cw, viewH)) {
                int idx = (int) ((my - top + scroll) / RH);
                if (idx >= 0 && idx < rows.size()) {
                    Row row = rows.get(idx);
                    int rowY = top + idx * RH - (int) scroll;
                    switch (row.kind) {
                        case TOGGLE, CYCLE, ACTION -> {
                            row.action.run();
                            changed = true;
                        }
                        case SLIDER -> {
                            if (in(mx, my, sliderX(cx, cw), rowY, sliderW(cw), RH)) activeSlider = row;
                        }
                        default -> { }
                    }
                }
            }
        }
        if (activeSlider != null) {
            if (down) {
                Row r = activeSlider;
                float f = (float) (mx - sliderX(cx, cw)) / sliderW(cw);
                f = Math.max(0f, Math.min(1f, f));
                float v = r.min + f * (r.max - r.min);
                v = Math.round(v / r.step) * r.step;
                v = Math.max(r.min, Math.min(r.max, v));
                r.set.accept(v);
                changed = true;
            }
            if (released) {
                if (activeSlider.commit != null) activeSlider.commit.run();
                activeSlider = null;
                changed = true;
            }
        }
        if (changed) s.save();

        // ---- panel ----
        ctx.fill(px + 2, py + ph, px + pw + 1, py + ph + 2, 0x66000000);
        ctx.fill(px + pw, py + 2, px + pw + 2, py + ph + 2, 0x66000000);
        Ui.box(ctx, px, py, pw, ph, PANEL, BORDER);
        Ui.vGradient(ctx, px + 1, py + 1, pw - 2, 18, 0xFF1E2530, 0xFF141A22);
        ctx.fill(px + 1, py + 19, px + pw - 1, py + 20, Widgets.GOLD);
        ctx.fill(px + 1, py + 20, px + pw - 1, py + 21, 0x30FFD700);
        ctx.drawTextWithShadow(tr, "Donut Streamer  -  Settings", px + 8, py + 6, Widgets.GOLD);
        boolean overClose = in(mx, my, px + pw - 16, py + 4, 12, 12);
        ctx.drawTextWithShadow(tr, "x", px + pw - 13, py + 6, overClose ? Widgets.RED : Widgets.GRAY);

        // tabs
        Tab[] tabs = Tab.values();
        for (int i = 0; i < tabs.length; i++) {
            int ty = py + 28 + i * 17;
            boolean sel = tabs[i] == tab;
            boolean over = in(mx, my, px + 6, ty, SIDE - 10, 15);
            Ui.roundRect(ctx, px + 6, ty, SIDE - 10, 15, sel ? 0xFF1F2A3A : (over ? 0x30FFFFFF : 0x14FFFFFF));
            if (sel) ctx.fill(px + 6, ty + 2, px + 8, ty + 13, Widgets.GOLD);
            ctx.drawTextWithShadow(tr, tabs[i].title, px + 13, ty + 4, sel ? Widgets.WHITE : Widgets.GRAY);
        }
        ctx.fill(px + SIDE + 1, py + 26, px + SIDE + 2, py + ph - 6, 0x30FFFFFF);

        // rows (clipped to the content area)
        ctx.enableScissor(cx, top, cx + cw, bottom);
        for (int i = 0; i < rows.size(); i++) {
            int ry = top + i * RH - (int) scroll;
            if (ry + RH < top || ry > bottom) continue;
            drawRow(ctx, tr, rows.get(i), cx, ry, cw, mx, my, in(mx, my, cx, top, cw, viewH));
        }
        ctx.disableScissor();

        // scrollbar
        if (maxScroll > 0) {
            int trackX = cx + cw + 2;
            int thumbH = Math.max(14, (int) (viewH * (viewH / (double) (rows.size() * RH))));
            int thumbY = top + (int) ((viewH - thumbH) * (scroll / maxScroll));
            Ui.roundRect(ctx, trackX, thumbY, 3, thumbH, 0xFFFFD700);
        }
    }

    private static int sliderX(int cx, int cw) {
        return cx + cw / 2;
    }

    private static int sliderW(int cw) {
        return cw / 2 - 58;
    }

    private void drawRow(DrawContext ctx, TextRenderer tr, Row r, int x, int y, int w, int mx, int my, boolean inView) {
        boolean hover = inView && in(mx, my, x, y, w, RH);
        switch (r.kind) {
            case HEADER -> {
                ctx.drawTextWithShadow(tr, r.label, x + 2, y + 7, Widgets.GOLD);
                ctx.fill(x + 2 + tr.getWidth(r.label) + 4, y + 11, x + w - 4, y + 12, 0x30FFFFFF);
            }
            case INFO -> ctx.drawTextWithShadow(tr, fit(r.text.get(), w - 8), x + 4, y + 5, Widgets.GRAY);
            case TOGGLE -> {
                if (hover) Ui.roundRect(ctx, x, y, w, RH - 1, ROW_HOVER);
                boolean on = Boolean.TRUE.equals(r.on.get());
                ctx.drawTextWithShadow(tr, fit(r.label, w - 44), x + 4, y + 5, on ? Widgets.WHITE : Widgets.GRAY);
                int sx = x + w - 30;
                Ui.roundRect(ctx, sx, y + 4, 24, 10, on ? ON : OFF);
                int kx = on ? sx + 14 : sx + 1;
                Ui.roundRect(ctx, kx, y + 5, 9, 8, Widgets.WHITE);
            }
            case SLIDER -> {
                ctx.drawTextWithShadow(tr, fit(r.label, w / 2 - 6), x + 4, y + 5, Widgets.WHITE);
                int tx = sliderX(x, w), tw = sliderW(w);
                float v = r.get.get();
                float f = (v - r.min) / (r.max - r.min);
                f = Math.max(0f, Math.min(1f, f));
                Ui.roundRect(ctx, tx, y + 7, tw, 4, 0xFF30363D);
                Ui.roundRect(ctx, tx, y + 7, Math.max(2, (int) (tw * f)), 4, Widgets.GOLD);
                int kx = tx + (int) (tw * f) - 2;
                Ui.roundRect(ctx, kx, y + 4, 5, 10, activeSlider == r ? 0xFFFFFF99 : Widgets.WHITE);
                String val = r.fmt.apply(v);
                ctx.drawTextWithShadow(tr, val, tx + tw + 6, y + 5, Widgets.GRAY);
            }
            case CYCLE -> {
                if (hover) Ui.roundRect(ctx, x, y, w, RH - 1, ROW_HOVER);
                ctx.drawTextWithShadow(tr, fit(r.label, w / 2 - 6), x + 4, y + 5, Widgets.WHITE);
                String v = "< " + r.text.get() + " >";
                ctx.drawTextWithShadow(tr, v, x + w - 6 - tr.getWidth(v), y + 5, Widgets.GOLD);
            }
            case ACTION -> {
                Ui.roundRect(ctx, x, y + 1, w, RH - 2, hover ? 0x40FFFFFF : 0x18FFFFFF);
                String val = r.text == null ? "" : r.text.get();
                int vw = val.isEmpty() ? 0 : tr.getWidth(val) + 8;
                ctx.drawTextWithShadow(tr, fit(r.label, w - 12 - vw), x + 6, y + 5, Widgets.WHITE);
                if (!val.isEmpty()) ctx.drawTextWithShadow(tr, val, x + w - 6 - tr.getWidth(val), y + 5, Widgets.GOLD);
            }
        }
    }
}
