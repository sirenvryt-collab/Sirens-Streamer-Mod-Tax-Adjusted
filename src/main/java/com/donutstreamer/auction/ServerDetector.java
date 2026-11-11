package com.donutstreamer.auction;

import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.Locale;

/**
 * Detects whether the player is connected to DonutSMP. The auction tools check this before doing
 * anything, so they stay inactive on every other server and in singleplayer (unless you turn on
 * "/ds auction anywhere" for testing). The address check is cached per server entry.
 */
public final class ServerDetector {
    /** Matches donutsmp.net and any subdomain of it (e.g. play.donutsmp.net). */
    private static final String DONUT_SMP_DOMAIN = "donutsmp.net";

    private static ServerInfo lastInfo;
    private static String lastAddress;
    private static boolean lastResult;

    private ServerDetector() {}

    public static boolean isDonutSmp() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;

        Session s = DonutStreamerClient.session;
        if (s != null && s.auctionAnyServer) return true;

        if (client.isIntegratedServerRunning()) return false;

        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || info.address == null) return false;

        if (info != lastInfo || !info.address.equals(lastAddress)) {
            lastInfo = info;
            lastAddress = info.address;
            lastResult = info.address.toLowerCase(Locale.ROOT).contains(DONUT_SMP_DOMAIN);
        }
        return lastResult;
    }
}
