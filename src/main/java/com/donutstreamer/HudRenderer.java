package com.donutstreamer;

import com.donutstreamer.auction.AuctionBrowserScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import org.joml.Matrix3x2fStack;

/** Draws every enabled widget at its own position. Pure rendering of locally stored numbers. */
public final class HudRenderer implements HudElement {
    @Override
    public void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Session s = DonutStreamerClient.session;
        if (s == null || mc.player == null || mc.options.hudHidden) return;
        if (s.watermark) drawWatermark(ctx, mc);
        if (!s.hudVisible) return;

        TextRenderer tr = mc.textRenderer;
        boolean browserOpen = mc.currentScreen instanceof AuctionBrowserScreen;

        for (Widgets.W w : Widgets.ALL) {
            Session.WidgetCfg c = s.cfg(w);
            if (!c.on || !Widgets.available(w, s)) continue;
            if (w == Widgets.W.NET && s.cfg(Widgets.W.GRAPH).on) continue;   // chart add-on already shows "Live: ..."
            if (w == Widgets.W.AUCTION && browserOpen) continue;   // the browser shows the same info
            fit(c, w, s, mc, tr);
            drawAt(ctx, mc, s, w, c);
        }
    }

    private static final int WATERMARK_COLOR = 0xFF4D94FF;
    private static final String WATERMARK = "Sirens Streamer Mod";

    /** Small blue tag in the bottom-left corner (turn off with /ds watermark). One string, no allocation. */
    private static void drawWatermark(DrawContext ctx, MinecraftClient mc) {
        Matrix3x2fStack m = ctx.getMatrices();
        float scale = 0.75f;
        m.pushMatrix();
        m.translate(4f, mc.getWindow().getScaledHeight() - 4f - 9f * scale);
        m.scale(scale, scale);
        ctx.drawTextWithShadow(mc.textRenderer, WATERMARK, 0, 0, WATERMARK_COLOR);
        m.popMatrix();
    }

    /** Keeps a widget on screen when the window or GUI scale shrinks (changes the saved position). */
    static void fit(Session.WidgetCfg c, Widgets.W w, Session s, MinecraftClient mc, TextRenderer tr) {
        float sc = s.scaleOf(c);
        int bw = Math.round(Widgets.width(w, s, tr) * sc);
        int bh = Math.round(Widgets.height(w) * sc);
        int maxX = Math.max(0, mc.getWindow().getScaledWidth() - bw);
        int maxY = Math.max(0, mc.getWindow().getScaledHeight() - bh);
        c.x = Math.max(0, Math.min(maxX, c.x));
        c.y = Math.max(0, Math.min(maxY, c.y));
    }

    static void drawAt(DrawContext ctx, MinecraftClient mc, Session s, Widgets.W w, Session.WidgetCfg c) {
        Matrix3x2fStack m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) c.x, (float) c.y);
        float sc = s.scaleOf(c);
        m.scale(sc, sc);
        Ui.bgMul = c.bgAlpha / 100f;
        try {
            Widgets.draw(w, s, ctx, mc.textRenderer);
        } finally {
            Ui.bgMul = 1.0f;
        }
        m.popMatrix();
    }
}
