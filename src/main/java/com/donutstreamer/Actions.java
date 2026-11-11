package com.donutstreamer;

import com.donutstreamer.auction.AuctionBrowserScreen;
import com.donutstreamer.auction.AuctionState;
import com.donutstreamer.auction.ServerDetector;
import com.donutstreamer.auction.TimeParse;
import com.donutstreamer.chat.StreamChat;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything the tracker does. All feedback is local (action bar / own chat).
 * Nothing here sends packets or touches the world, inventory or other players.
 */
public final class Actions {
    private Actions() {}

    private static boolean limitAlerted = false;
    private static boolean goalAlerted = false;
    private static long resetArmedUntil = 0;

    private static Session s() { return DonutStreamerClient.session; }

    public static void bar(Formatting color, String text) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal(text).formatted(color), true);
    }

    public static void chat(Formatting color, String text) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(Text.literal(text).formatted(color), false);
    }

    private static void checkAlerts() {
        Session s = s();

        if (s.lossLimit > 0 && s.net <= -s.lossLimit) {
            if (!limitAlerted) {
                limitAlerted = true;
                chat(Formatting.RED, "[Tracker] Loss limit reached (" + Money.format(s.lossLimit)
                        + "). Live: " + Money.signed(s.net) + ". Time to take a break.");
            }
        } else {
            limitAlerted = false;
        }

        if (s.goal > 0 && s.net >= s.goal) {
            if (!goalAlerted) {
                goalAlerted = true;
                chat(Formatting.GREEN, "[Tracker] Profit goal reached (" + Money.format(s.goal)
                        + "). Live: " + Money.signed(s.net) + ".");
            }
        } else {
            goalAlerted = false;
        }

        if (s.tiltAlert > 0 && s.streak == -s.tiltAlert) {
            chat(Formatting.GOLD, "[Tracker] " + s.tiltAlert + " payments sent in a row with nothing back. Maybe slow down?");
        }
    }

    /** Called for every payment / sale message read from chat (and by /ds in|out|sell). */
    public static void record(String kind, String who, long amount) {
        Session s = s();
        Session.Entry e = s.add(kind, who, amount);
        s.save();
        s.logCsv(kind, e.who, e.amount, s.delta(e));
        if (s.sound && Session.IN.equals(kind)) {
            ClientPlayerEntity pl = MinecraftClient.getInstance().player;
            if (pl != null) pl.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
        checkAlerts();
    }

    public static void undo() {
        Session s = s();
        Session.Entry e = s.undo();
        if (e == null) {
            bar(Formatting.GRAY, "Nothing to undo");
            return;
        }
        s.save();
        s.logCsv("UNDO_" + e.kind, e.who, e.amount, -s.delta(e));
        bar(Formatting.YELLOW, "Removed last entry  |  Live " + Money.signed(s.net));
        checkAlerts();
    }

    public static void requestReset() {
        long now = System.currentTimeMillis();
        if (now <= resetArmedUntil) {
            resetArmedUntil = 0;
            startNewSession("RESET");
            bar(Formatting.YELLOW, "Session reset");
        } else {
            resetArmedUntil = now + 5000;
            bar(Formatting.GOLD, "Run /ds reset again within 5s to confirm");
        }
    }

    /**
     * Ends the current session and starts a fresh one (timer, history and stats go back to zero; settings are kept).
     * The finished session's summary is appended to donutstreamer/sessions.log first.
     */
    public static void startNewSession(String reason) {
        Session s = s();
        if (!s.history.isEmpty() || s.elapsedMs >= 1000) s.logSessionEnd(reason, s.summary());
        s.reset();
        s.sessionDay = LocalDate.now().toString();
        s.save();
        s.logCsv(reason, "", 0, 0);
        limitAlerted = false;
        goalAlerted = false;
    }

    private static long lastDayCheck = 0;

    /** Called every client tick (throttled to once a second): starts a new session when the local date changes. */
    public static void checkNewDay() {
        long now = System.currentTimeMillis();
        if (now - lastDayCheck < 1000) return;
        lastDayCheck = now;
        Session s = s();
        String today = LocalDate.now().toString();
        if (s.sessionDay == null || s.sessionDay.isEmpty()) {
            s.sessionDay = today;      // config from before this feature: adopt today, don't wipe anything
            s.save();
        } else if (!s.sessionDay.equals(today)) {
            if (s.dailyReset) {
                boolean had = !s.history.isEmpty() || s.elapsedMs >= 1000;
                String previous = s.summary();
                startNewSession("NEW_DAY");
                if (had) s.pendingNotice = previous;
            } else {
                s.sessionDay = today;
                s.save();
            }
        }
        if (s.pendingNotice != null && MinecraftClient.getInstance().player != null) {
            chat(Formatting.GOLD, "[Tracker] New day: started a fresh session.");
            chat(Formatting.GRAY, "Previous session: " + s.pendingNotice);
            s.pendingNotice = null;
        }
    }

    public static void toggleDailyReset() {
        s().dailyReset = !s().dailyReset;
        s().sessionDay = LocalDate.now().toString();
        s().save();
        bar(Formatting.GRAY, "New session every day at midnight " + (s().dailyReset ? "on" : "off"));
    }

    public static void toggleChatPreviews() {
        s().chatPreviews = !s().chatPreviews;
        s().save();
        bar(Formatting.GRAY, "Auction / music previews in chat " + (s().chatPreviews ? "on" : "off"));
    }

    public static void toggleAuctionStreamDelay() {
        s().auctionStreamDelay = !s().auctionStreamDelay;
        s().save();
        bar(Formatting.GRAY, AuctionState.STREAM_DELAY_SECONDS + "s stream-delay countdown after each auction "
                + (s().auctionStreamDelay ? "on (applies to the next auction)" : "off"));
    }

    public static void toggleNametagRank() {
        s().selfNametagRank = !s().selfNametagRank;
        s().save();
        bar(Formatting.GRAY, "Rank on own nametag " + (s().selfNametagRank ? "on" : "off"));
    }

    public static void toggleHud() {
        s().hudVisible = !s().hudVisible;
        s().save();
        bar(Formatting.GRAY, "HUD " + (s().hudVisible ? "shown" : "hidden"));
    }

    public static void toggleWidget(Widgets.W w) {
        Session.WidgetCfg c = s().cfg(w);
        c.on = !c.on;
        s().save();
        bar(Formatting.GRAY, w.label + " " + (c.on ? "on" : "off"));
    }

    public static void setAllWidgets(boolean on) {
        for (Widgets.W w : Widgets.W.values()) s().cfg(w).on = on;
        s().save();
        bar(Formatting.GRAY, "All HUD elements " + (on ? "on" : "off"));
    }

    public static void resetLayout() {
        s().widgets.clear();
        s().scale = 1.0f;
        s().save();
        bar(Formatting.GRAY, "HUD layout reset");
    }

    public static void toggleSound() {
        s().sound = !s().sound;
        s().save();
        bar(Formatting.GRAY, "Sounds " + (s().sound ? "on" : "off"));
    }

    public static void toggleNames() {
        s().showNames = !s().showNames;
        s().save();
        bar(Formatting.GRAY, "Player names " + (s().showNames ? "shown" : "hidden"));
    }

    public static void toggleDoubles() {
        s().doubleTag = !s().doubleTag;
        s().save();
        bar(Formatting.GRAY, "Doubled-payment tag " + (s().doubleTag ? "on" : "off"));
    }

    public static void togglePercent() {
        s().percentMode = !s().percentMode;
        s().save();
        bar(Formatting.GRAY, s().percentMode
                ? (s().startBalance > 0 ? "Live shown as % of starting balance" : "Percent mode on (set /ds balance first)")
                : "Live shown as an amount");
    }

    public static void toggleSells() {
        s().includeSells = !s().includeSells;
        s().recompute();
        s().save();
        bar(Formatting.GRAY, "Sells " + (s().includeSells ? "counted in" : "not counted in") + " net profit");
    }

    public static void toggleSellSource() {
        s().sellsFromActionBar = !s().sellsFromActionBar;
        s().save();
        bar(Formatting.GRAY, "Reading sales from " + (s().sellsFromActionBar ? "the action bar" : "chat"));
    }

    public static void setHudScale(float scale) {
        s().scale = scale;
        s().save();
        bar(Formatting.GRAY, "HUD scale " + scale);
    }

    public static void setLimit(long amount) {
        s().lossLimit = amount;
        s().save();
        limitAlerted = false;
        checkAlerts();
        bar(Formatting.AQUA, amount > 0 ? "Loss limit: " + Money.format(amount) : "Loss limit off");
    }

    public static void setGoal(long amount) {
        s().goal = amount;
        s().save();
        goalAlerted = false;
        checkAlerts();
        bar(Formatting.AQUA, amount > 0 ? "Profit goal: " + Money.format(amount) : "Profit goal off");
    }

    public static void setTilt(int n) {
        s().tiltAlert = n;
        s().save();
        bar(Formatting.AQUA, n > 0 ? "Alert after " + n + " payments sent in a row" : "Loss-streak alert off");
    }

    public static void setRakeback(double pct) {
        s().rakebackPct = pct;
        s().save();
        bar(Formatting.AQUA, pct > 0 ? "Rakeback: " + pct + "% of what each player sent you" : "Rakeback tally off");
    }

    public static void setBalance(long amount) {
        s().startBalance = amount;
        s().save();
        bar(Formatting.AQUA, amount >= 0 ? "Starting balance: " + Money.format(amount) : "Balance display off");
    }

    public static void summary() {
        String text = s().summary();
        chat(Formatting.GOLD, text);
        MinecraftClient.getInstance().keyboard.setClipboard(text);
        chat(Formatting.GRAY, "(copied to clipboard)");
    }

    public static void players() {
        Session s = s();
        List<Session.PlayerStat> list = s.playerStats();
        if (list.isEmpty()) {
            chat(Formatting.GRAY, "No player payments yet this session.");
            return;
        }
        chat(Formatting.GOLD, "Payments by player (this session):");
        int shown = 0;
        for (Session.PlayerStat p : list) {
            if (shown++ >= 10) break;
            String rake = s.rakebackPct > 0 ? "  rakeback owed " + Money.format(s.rakebackOwed(p)) : "";
            chat(p.net() >= 0 ? Formatting.GREEN : Formatting.RED,
                    p.name + ": in " + Money.format(p.in) + ", out " + Money.format(p.out)
                            + ", net " + Money.signed(p.net()) + rake);
        }
    }

    // ================= auction =================

    private static MutableText auctionPrefix() {
        return Text.literal("[Auction] ").formatted(Formatting.BLUE, Formatting.BOLD);
    }

    private static void auctionChat(MutableText body) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p != null) p.sendMessage(auctionPrefix().append(body), false);
    }

    /** Auction tools only run on DonutSMP (unless "/ds auction anywhere" is on). Tells the player when blocked. */
    private static boolean requireDonut() {
        if (ServerDetector.isDonutSmp()) return true;
        chat(Formatting.RED, "Auction tools only work on DonutSMP. (To test elsewhere: /ds auction anywhere)");
        return false;
    }

    /** Starts an auction from typed text (commands). Prints what was wrong when the input is invalid. */
    public static void auctionStartText(String item, String minBidRaw, String timeRaw) {
        if (!requireDonut()) return;
        long minBid;
        try {
            minBid = Money.parse(minBidRaw);
        } catch (NumberFormatException e) {
            minBid = -1;
        }
        if (minBid <= 0) {
            chat(Formatting.RED, "Enter a valid minimum bid, e.g. 250k or 1.5m.");
            return;
        }
        int seconds = TimeParse.seconds(timeRaw);
        if (seconds <= 0) {
            chat(Formatting.RED, "Enter a valid time, e.g. 5m, 90s or 1m30s (a plain number means minutes).");
            return;
        }
        AuctionState.INSTANCE.start(item, minBid, seconds);
        announceAuctionStart(item, minBid, seconds);
    }

    /** Local chat line shown when an auction starts (from the browser or a command). */
    public static void announceAuctionStart(String item, long minBid, int seconds) {
        auctionChat(Text.literal("Started auction for ").formatted(Formatting.GRAY)
                .append(Text.literal(item).formatted(Formatting.WHITE))
                .append(Text.literal(" (min bid $" + Money.format(minBid) + ", " + TimeParse.format(seconds)
                        + (s().auctionStreamDelay ? ", then " + AuctionState.STREAM_DELAY_SECONDS + "s stream-delay countdown" : "") + ")")
                        .formatted(Formatting.GRAY)));
    }

    public static void auctionStop() {
        AuctionState st = AuctionState.INSTANCE;
        if (!st.isActive()) {
            chat(Formatting.GRAY, "No auction is running.");
            return;
        }
        st.stop();   // the result is announced on the next tick
    }

    public static void auctionStatus() {
        AuctionState st = AuctionState.INSTANCE;
        if (!st.isActive()) {
            chat(Formatting.GRAY, "No auction is running. Press B or use /ds auction start \"Item\" <min bid> [time].");
            return;
        }
        String top = st.hasBid()
                ? st.getHighestBidder() + " with $" + Money.format(st.getHighestBid())
                : "no bids yet";
        auctionChat(Text.literal(st.getCurrentItemName()).formatted(Formatting.WHITE)
                .append(Text.literal("  |  " + (st.isInDelay()
                                ? "stream delay ends in " + TimeParse.clock((st.getDelayRemainingMillis() + 999) / 1000)
                                : "ends in " + TimeParse.clock((st.getRemainingMillis() + 999) / 1000))
                        + "  |  min $" + Money.format(st.getMinimumBid()) + "  |  top: " + top)
                        .formatted(Formatting.GRAY)));
    }

    /** Shown in the action bar when a bid becomes the highest. */
    public static void auctionNewTopBid(String who, long amount) {
        bar(Formatting.AQUA, "New top bid: " + who + " $" + Money.format(amount));
        if (s().sound) {
            ClientPlayerEntity pl = MinecraftClient.getInstance().player;
            if (pl != null) pl.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        }
    }

    public static void announceAuctionResult(AuctionState.AuctionResult result) {
        if (result.hasWinner()) {
            auctionChat(Text.literal(result.winner()).formatted(Formatting.AQUA, Formatting.BOLD)
                    .append(Text.literal(" won ").formatted(Formatting.GRAY))
                    .append(Text.literal(result.itemName()).formatted(Formatting.WHITE))
                    .append(Text.literal(" with a bid of ").formatted(Formatting.GRAY))
                    .append(Text.literal("$" + Money.format(result.winningBid())).formatted(Formatting.GREEN))
                    .append(Text.literal("!").formatted(Formatting.GRAY)));
        } else {
            auctionChat(Text.literal("Auction for " + result.itemName() + " ended with no bids.")
                    .formatted(Formatting.GRAY));
        }
    }

    public static void openAuctionBrowser() {
        if (!requireDonut()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new AuctionBrowserScreen()));   // next tick, so the chat screen can close first
    }

    public static void toggleAuctionAnywhere() {
        s().auctionAnyServer = !s().auctionAnyServer;
        s().save();
        bar(Formatting.GRAY, "Auction tools " + (s().auctionAnyServer ? "enabled on any server" : "limited to DonutSMP"));
    }

    public static void toggleAuctionTracking() {
        s().trackAuctionPayments = !s().trackAuctionPayments;
        s().save();
        bar(Formatting.GRAY, "Auction bids " + (s().trackAuctionPayments ? "also count" : "do not count") + " in the tracker");
    }

    public static void openSettings() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new SettingsScreen()));   // next tick, so the chat screen can close first
    }

    public static void openWindow() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new TrackerScreen()));   // next tick, so the chat screen can close first
    }

    // ================= stream mode, nametag, watermark, stream chat =================

    private static String onOff(boolean v) {
        return v ? "on" : "off";
    }

    public static void toggleStreamMode() {
        Session s = s();
        s.streamMode = !s.streamMode;
        s.save();
        if (s.streamMode) {
            chat(Formatting.GREEN, "[Stream mode] ON. F3 coordinates: " + (s.streamHideCoords ? "hidden" : "shown")
                    + ", chat coordinates: " + (s.streamHideChatCoords ? "hidden" : "shown")
                    + ", HUD player names: " + (s.streamHideNames ? "hidden" : "shown")
                    + ". Change with /ds stream coords|chatcoords|names.");
        } else {
            chat(Formatting.GRAY, "[Stream mode] OFF. Everything shows normally again.");
        }
    }

    public static void toggleStreamCoords() {
        s().streamHideCoords = !s().streamHideCoords;
        s().save();
        bar(Formatting.GRAY, "Hide F3 coordinates " + onOff(s().streamHideCoords) + (s().streamMode ? "" : " (needs stream mode: /ds stream)"));
    }

    public static void toggleStreamChatCoords() {
        s().streamHideChatCoords = !s().streamHideChatCoords;
        s().save();
        bar(Formatting.GRAY, "Hide coordinates in chat " + onOff(s().streamHideChatCoords) + (s().streamMode ? "" : " (needs stream mode: /ds stream)"));
    }

    public static void toggleStreamNames() {
        s().streamHideNames = !s().streamHideNames;
        s().save();
        bar(Formatting.GRAY, "Hide HUD player names in stream mode " + onOff(s().streamHideNames));
    }

    public static void streamStatus() {
        Session s = s();
        chat(Formatting.GOLD, "Stream mode is " + onOff(s.streamMode));
        chat(Formatting.WHITE, "  /ds stream coords      hide F3 position, chunk and targeted block: " + onOff(s.streamHideCoords));
        chat(Formatting.WHITE, "  /ds stream chatcoords  blank \"x, y, z\" in incoming chat: " + onOff(s.streamHideChatCoords));
        chat(Formatting.WHITE, "  /ds stream names       hide player names on the HUD: " + onOff(s.streamHideNames));
    }

    public static void toggleSelfNametag() {
        s().selfNametag = !s().selfNametag;
        s().save();
        bar(Formatting.GRAY, "Own nametag in F5 " + onOff(s().selfNametag));
    }

    public static void toggleWatermark() {
        s().watermark = !s().watermark;
        s().save();
        bar(Formatting.GRAY, "Watermark " + onOff(s().watermark));
    }

    public static void chatStatus() {
        chat(Formatting.LIGHT_PURPLE, "Stream chat: " + StreamChat.statusLine());
        chat(Formatting.WHITE, "  Twitch: " + (StreamChat.twitchChannel().isEmpty() ? "not set" : "#" + StreamChat.twitchChannel())
                + "   YouTube: " + (StreamChat.youtubeVideo().isEmpty() ? "not set" : StreamChat.youtubeVideo())
                + (StreamChat.hasYouTubeKey() ? " (key saved)" : " (no API key)")
                + "   hide !commands: " + onOff(StreamChat.hideCommands()));
        chat(Formatting.GRAY, "  Show the panel with /ds hud chat (drag it in the HUD editor).");
    }

    public static void chatTwitch(String channel) {
        String err = StreamChat.setTwitch(channel);
        if (err != null) {
            chat(Formatting.RED, err);
            return;
        }
        s().cfg(Widgets.W.CHAT).on = true;
        s().save();
        chat(Formatting.LIGHT_PURPLE, "Stream chat: connecting to Twitch #" + StreamChat.twitchChannel() + " (read only).");
    }

    public static void chatTwitchOff() {
        StreamChat.clearTwitch();
        chat(Formatting.GRAY, "Twitch chat disconnected.");
    }

    public static void chatYouTube(String video) {
        String err = StreamChat.setYouTube(video);
        if (err != null) {
            chat(Formatting.RED, err);
            return;
        }
        s().cfg(Widgets.W.CHAT).on = true;
        s().save();
        if (StreamChat.hasYouTubeKey()) {
            chat(Formatting.RED, "Stream chat: connecting to YouTube video " + StreamChat.youtubeVideo() + " (read only).");
        } else {
            chat(Formatting.GOLD, "Video saved. YouTube also needs your own API key: copy it, then run /ds chat youtubekey paste "
                    + "(pasting keeps it off your screen).");
        }
    }

    public static void chatYouTubeOff() {
        StreamChat.clearYouTube();
        chat(Formatting.GRAY, "YouTube chat disconnected.");
    }

    public static void chatYouTubeKey(String key) {
        String err = StreamChat.setYouTubeKey(key);
        if (err != null) {
            chat(Formatting.RED, err);
        } else {
            chat(Formatting.GREEN, "YouTube API key saved to config/donutstreamer-chat.json (keep that file private)."
                    + (StreamChat.youtubeVideo().isEmpty() ? " Now run /ds chat youtube <link>." : ""));
        }
    }

    public static void chatYouTubeKeyPaste() {
        String clip = MinecraftClient.getInstance().keyboard.getClipboard();
        chatYouTubeKey(clip);
    }

    public static void chatYouTubeKeyClear() {
        StreamChat.clearYouTubeKey();
        chat(Formatting.GRAY, "YouTube API key removed.");
    }

    public static void chatOff() {
        StreamChat.disconnectAll();
        chat(Formatting.GRAY, "Stream chat disconnected (settings are kept; reconnect with /ds chat twitch <channel>).");
    }

    public static void chatClear() {
        StreamChat.clearMessages();
        bar(Formatting.GRAY, "Stream chat cleared");
    }

    public static void chatToggleCommands() {
        boolean now = StreamChat.toggleHideCommands();
        bar(Formatting.GRAY, "Hide !commands in stream chat " + onOff(now));
    }

    public static void help() {
        chat(Formatting.GOLD, "Donut Streamer (all local). Profit is read from payment messages in chat.");
        chat(Formatting.WHITE, "/ds settings  opens the settings screen (HUD, per-element size, chat, music, stream).");
        chat(Formatting.WHITE, "/ds  opens the tracker window. In chat, use the \"HUD editor\" button; drag elements to move.");
        chat(Formatting.WHITE, "/ds in|out <amt> [name]  /ds buy <amt> [item]  /ds sell <amt>  (manual entry)   /ds undo  /ds reset  /ds summary  /ds players");
        chat(Formatting.WHITE, "/ds daily (new session every midnight)  /ds preview (auction/music previews in chat)  /ds auction delay (+12s stream delay)  /ds nametag rank");
        chat(Formatting.WHITE, "/ds limit|goal <amt|off>  /ds tilt <2-20|off>  /ds rakeback <0.5-50|off>  /ds balance <amt|off>");
        chat(Formatting.WHITE, "/ds percent  /ds sells  /ds sellsource  /ds doubles  /ds sound  /ds names  /ds hud toggle|reset|all on|all off|scale <n>|<element>");
        chat(Formatting.AQUA, "Auctions (DonutSMP): B opens the browser, H shows/hides the panel.");
        chat(Formatting.GREEN, "Music (YouTube, no Premium): /ds music setup once, then \\ play/pause, ] next, [ previous (rebindable).");
        chat(Formatting.WHITE, "/ds music play|pause|toggle|next|prev|volume <0-100>|mute|muteads|port <n>|status|setup");
        chat(Formatting.AQUA, "Stream: /ds stream (mode on/off)  /ds stream coords|chatcoords|names|status  /ds nametag (own tag in F5)  /ds watermark");
        chat(Formatting.LIGHT_PURPLE, "Stream chat: /ds chat twitch <channel>|off  /ds chat youtube <link>|off  /ds chat youtubekey paste|<key>|clear  /ds chat off|clear|commands|status");
        chat(Formatting.WHITE, "/ds auction start \"<item>\" <min bid> [time]  /ds auction stop|status|browser  /ds auction track|anywhere");
    }
}
