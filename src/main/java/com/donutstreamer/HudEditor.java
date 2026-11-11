package com.donutstreamer;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Editing UI that lives on the chat screen: drag any HUD element with the left mouse button (it snaps to the
 * screen edges and centre; hold Shift to move freely), resize one element by dragging up/down with the right mouse
 * button, and use the "HUD editor" button (top right) to switch elements on and off or open /ds settings.
 * Purely local; it only changes where/whether numbers are drawn.
 */
public final class HudEditor {
    private HudEditor() {}

    /** checked == null means a plain action row (no checkbox). */
    private record Row(String label, Supplier<Boolean> checked, Runnable action) {}

    private static final List<Row> ROWS = buildRows();

    private static Screen registeredFor = null;
    private static boolean panelOpen = false;
    private static boolean prevDown = false;
    private static boolean prevRight = false;
    private static Widgets.W dragging = null;
    private static Widgets.W resizing = null;
    private static int rsStartY;
    private static float rsStartScale;
    private static int dragDX, dragDY;
    private static int labelW = -1;
    private static final int WHITE_TICK = 0xFFFFFFFF;

    private static Session session() {
        return DonutStreamerClient.session;
    }

    private static List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Open settings (/ds settings)", null, Actions::openSettings));
        rows.add(new Row("Show HUD", () -> session().hudVisible, () -> session().hudVisible = !session().hudVisible));
        rows.add(new Row("Turn ALL elements on", null, () -> {
            for (Widgets.W w : Widgets.ALL) session().cfg(w).on = true;
        }));
        rows.add(new Row("Turn ALL elements off", null, () -> {
            for (Widgets.W w : Widgets.ALL) session().cfg(w).on = false;
        }));
        for (Widgets.W w : Widgets.ALL) {
            rows.add(new Row(w.label, () -> session().cfg(w).on, () -> {
                Session.WidgetCfg c = session().cfg(w);
                c.on = !c.on;
            }));
        }
        rows.add(new Row("Tag doubled payments", () -> session().doubleTag, () -> session().doubleTag = !session().doubleTag));
        rows.add(new Row("Live as % of balance", () -> session().percentMode, () -> session().percentMode = !session().percentMode));
        rows.add(new Row("Count sells in profit", () -> session().includeSells, () -> {
            session().includeSells = !session().includeSells;
            session().recompute();
        }));
        rows.add(new Row("Count auction bids in tracker", () -> session().trackAuctionPayments,
                () -> session().trackAuctionPayments = !session().trackAuctionPayments));
        rows.add(new Row("Auctions on any server (testing)", () -> session().auctionAnyServer,
                () -> session().auctionAnyServer = !session().auctionAnyServer));
        rows.add(new Row("Stream mode (hide F3 coords)", () -> session().streamMode, () -> session().streamMode = !session().streamMode));
        rows.add(new Row("Hide coords in chat (stream mode)", () -> session().streamHideChatCoords,
                () -> session().streamHideChatCoords = !session().streamHideChatCoords));
        rows.add(new Row("Own nametag in F5", () -> session().selfNametag, () -> session().selfNametag = !session().selfNametag));
        rows.add(new Row("Rank on own nametag", () -> session().selfNametagRank, () -> session().selfNametagRank = !session().selfNametagRank));
        rows.add(new Row("New session every midnight", () -> session().dailyReset, () -> {
            session().dailyReset = !session().dailyReset;
            session().sessionDay = java.time.LocalDate.now().toString();
        }));
        rows.add(new Row("Auction/music previews in chat", () -> session().chatPreviews, () -> session().chatPreviews = !session().chatPreviews));
        rows.add(new Row("Auction 12s stream-delay countdown", () -> session().auctionStreamDelay, () -> session().auctionStreamDelay = !session().auctionStreamDelay));
        rows.add(new Row("Sirens watermark", () -> session().watermark, () -> session().watermark = !session().watermark));
        rows.add(new Row("Sounds", () -> session().sound, () -> session().sound = !session().sound));
        rows.add(new Row("Show player names", () -> session().showNames, () -> session().showNames = !session().showNames));
        rows.add(new Row("Reset HUD layout (positions and sizes)", null, () -> {
            session().widgets.clear();
            session().scale = 1.0f;
        }));
        return List.copyOf(rows);
    }

    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            prevDown = leftDown(client);
            prevRight = rightDown(client);
            dragging = null;
            resizing = null;
            if (registeredFor == screen) return;   // AFTER_INIT also fires on window resize
            registeredFor = screen;
            ScreenEvents.afterRender(screen).register((scr, ctx, mx, my, delta) -> render(client, ctx, mx, my));
            ScreenEvents.remove(screen).register(scr -> {
                if (dragging != null || resizing != null) {
                    dragging = null;
                    resizing = null;
                    session().save();
                }
                if (registeredFor == scr) registeredFor = null;
            });
        });
    }

    private static boolean leftDown(MinecraftClient mc) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private static boolean rightDown(MinecraftClient mc) {
        return GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
    }

    private static boolean shiftDown(MinecraftClient mc) {
        long h = mc.getWindow().getHandle();
        return GLFW.glfwGetKey(h, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(h, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
    }

    private static int snap(int v, int size, int total, boolean free) {
        if (free) return v;
        if (Math.abs(v) < 5) return 0;
        if (Math.abs(v + size - total) < 5) return total - size;
        int mid = total / 2 - size / 2;
        if (Math.abs(v - mid) < 5) return mid;
        return v;
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static void outline(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    private static void render(MinecraftClient mc, DrawContext ctx, int mx, int my) {
        Session s = session();
        TextRenderer tr = mc.textRenderer;
        int sw = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();

        boolean down = leftDown(mc);
        boolean pressed = down && !prevDown;
        boolean released = !down && prevDown;
        prevDown = down;
        boolean rdown = rightDown(mc);
        boolean rpressed = rdown && !prevRight;
        boolean rreleased = !rdown && prevRight;
        prevRight = rdown;

        // ---- widgets: draw ghosts for ones with no data yet, then outlines ----
        Widgets.W hovered = null;
        if (s.hudVisible) {
            for (Widgets.W w : Widgets.ALL) {
                Session.WidgetCfg c = s.cfg(w);
                if (!c.on || Widgets.previewHidden(w, s)) continue;
                if (w == Widgets.W.NET && s.cfg(Widgets.W.GRAPH).on) continue;
                if (dragging != w) HudRenderer.fit(c, w, s, mc, tr);
                if (!Widgets.available(w, s)) HudRenderer.drawAt(ctx, mc, s, w, c);
                float sc = s.scaleOf(c);
                int bw = Math.round(Widgets.width(w, s, tr) * sc);
                int bh = Math.round(Widgets.height(w) * sc);
                boolean over = inside(mx, my, c.x, c.y, bw, bh);
                if (over) hovered = w;
                boolean active = dragging == w || resizing == w;
                outline(ctx, c.x - 1, c.y - 1, bw + 2, bh + 2,
                        active ? 0xFFFFFF55 : (over ? 0xFFFFFFFF : 0x88FFFFFF));
                if (over || active) {
                    String tip = w.label + "  " + (Math.round(c.scale * 100) / 100f) + "x"
                            + (active ? "" : "  (right-drag up/down = resize)");
                    int ty = c.y >= 11 ? c.y - 11 : c.y + bh + 2;
                    int tw = tr.getWidth(tip);
                    Ui.box(ctx, c.x, ty - 1, tw + 6, 11, 0xE00D1117, 0xFF30363D);
                    ctx.drawTextWithShadow(tr, tip, c.x + 3, ty, Widgets.WHITE);
                }
            }
        }

        // ---- editor button ----
        String btn = "HUD editor " + (panelOpen ? "\u25B2" : "\u25BC");
        int btnW = tr.getWidth(btn) + 8, btnH = 12;
        int btnX = sw - btnW - 4, btnY = 4;
        boolean overBtn = inside(mx, my, btnX, btnY, btnW, btnH);

        // ---- panel geometry (rows flow into columns so everything fits on screen) ----
        int rowH = 11;
        int panelY = btnY + btnH + 2;
        int colW = 0, perCol = 1, panelW = 0, panelH = 0, panelX = 0;
        boolean overPanel = false;
        if (panelOpen) {
            if (labelW < 0) {
                for (Row r : ROWS) labelW = Math.max(labelW, tr.getWidth(r.label()) + 12);
            }
            colW = labelW + 10;
            perCol = Math.max(1, (screenH - panelY - 12) / rowH);
            int cols = (ROWS.size() + perCol - 1) / perCol;
            panelW = cols * colW;
            panelH = Math.min(ROWS.size(), perCol) * rowH + 6;
            panelX = sw - panelW - 4;
            overPanel = inside(mx, my, panelX, panelY, panelW, panelH);
        }

        boolean consumed = false;
        if (pressed) {
            if (overBtn) {
                panelOpen = !panelOpen;
                consumed = true;
            } else if (overPanel) {
                int col = (mx - panelX) / colW;
                int rowIdx = (my - panelY - 3) / rowH;
                int idx = col * perCol + rowIdx;
                if (rowIdx >= 0 && rowIdx < perCol && idx >= 0 && idx < ROWS.size()) {
                    ROWS.get(idx).action().run();
                    s.save();
                }
                consumed = true;
            }
        }

        // ---- dragging ----
        if (pressed && !consumed && hovered != null) {
            Session.WidgetCfg c = s.cfg(hovered);
            dragging = hovered;
            dragDX = mx - c.x;
            dragDY = my - c.y;
        }
        if (rpressed && !consumed && hovered != null && dragging == null) {
            Session.WidgetCfg c = s.cfg(hovered);
            resizing = hovered;
            rsStartY = my;
            rsStartScale = c.scale;
        }
        if (resizing != null) {
            if (rdown) {
                Session.WidgetCfg c = s.cfg(resizing);
                float v = Math.round((rsStartScale + (rsStartY - my) * 0.01f) * 20f) / 20f;
                c.scale = Math.max(0.4f, Math.min(4.0f, v));
                HudRenderer.fit(c, resizing, s, mc, tr);
            }
            if (rreleased) {
                resizing = null;
                s.save();
            }
        }
        if (dragging != null) {
            if (down) {
                Session.WidgetCfg c = s.cfg(dragging);
                float sc = s.scaleOf(c);
                int bw = Math.round(Widgets.width(dragging, s, tr) * sc);
                int bh = Math.round(Widgets.height(dragging) * sc);
                int maxX = Math.max(0, sw - bw);
                int maxY = Math.max(0, screenH - bh);
                boolean free = shiftDown(mc);
                int nx = snap(mx - dragDX, bw, sw, free);
                int ny = snap(my - dragDY, bh, screenH, free);
                c.x = Math.max(0, Math.min(maxX, nx));
                c.y = Math.max(0, Math.min(maxY, ny));
            }
            if (released) {
                dragging = null;
                s.save();
            }
        }

        // ---- draw button + panel on top ----
        Ui.box(ctx, btnX, btnY, btnW, btnH, overBtn ? 0xF0303844 : 0xF0141A22, overBtn ? 0xFFFFE066 : Widgets.GOLD);
        ctx.fill(btnX + 2, btnY + 1, btnX + btnW - 2, btnY + 2, 0x18FFFFFF);
        ctx.drawTextWithShadow(tr, btn, btnX + 4, btnY + 2, Widgets.GOLD);

        if (panelOpen) {
            ctx.fill(panelX + 2, panelY + panelH, panelX + panelW + 1, panelY + panelH + 2, 0x55000000);   // shadow
            ctx.fill(panelX + panelW, panelY + 2, panelX + panelW + 2, panelY + panelH + 2, 0x55000000);
            Ui.box(ctx, panelX, panelY, panelW, panelH, 0xF20D1117, 0xFF30363D);
            ctx.fill(panelX + 1, panelY + 1, panelX + panelW - 1, panelY + 2, 0x20FFFFFF);
            for (int i = 0; i < ROWS.size(); i++) {
                Row r = ROWS.get(i);
                int rx = panelX + (i / perCol) * colW;
                int ry = panelY + 3 + (i % perCol) * rowH;
                if (inside(mx, my, rx, ry, colW, rowH)) Ui.roundRect(ctx, rx + 1, ry, colW - 2, rowH, 0x40FFFFFF);
                boolean plain = r.checked() == null;
                boolean on = !plain && Boolean.TRUE.equals(r.checked().get());
                int textX = rx + 5;
                if (!plain) {
                    // little switch: green fill with a white tick when on, hollow when off
                    Ui.roundRect(ctx, rx + 4, ry + 2, 7, 7, on ? 0xFF2EA043 : 0xFF30363D);
                    if (on) {
                        ctx.fill(rx + 5, ry + 5, rx + 6, ry + 6, WHITE_TICK);
                        ctx.fill(rx + 6, ry + 6, rx + 7, ry + 7, WHITE_TICK);
                        ctx.fill(rx + 7, ry + 5, rx + 8, ry + 6, WHITE_TICK);
                        ctx.fill(rx + 8, ry + 4, rx + 9, ry + 5, WHITE_TICK);
                    } else {
                        ctx.fill(rx + 5, ry + 3, rx + 10, ry + 4, 0x18FFFFFF);
                    }
                    textX = rx + 15;
                }
                int color = plain ? Widgets.GOLD : (on ? Widgets.WHITE : Widgets.GRAY);
                ctx.drawTextWithShadow(tr, r.label(), textX, ry + 1, color);
            }
        }
    }
}
