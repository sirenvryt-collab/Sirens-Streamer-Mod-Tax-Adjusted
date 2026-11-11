package com.donutstreamer;

import com.donutstreamer.music.YtMusic;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.util.Formatting;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Commands {
    private Commands() {}

    private static Long amount(CommandContext<FabricClientCommandSource> c, String name) {
        try {
            return Money.parse(StringArgumentType.getString(c, name));
        } catch (NumberFormatException e) {
            Actions.chat(Formatting.RED, "Invalid amount. Try something like 5000, 10k or 1.5m.");
            return null;
        }
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> entry(String name, String kind) {
        return literal(name).then(argument("amount", StringArgumentType.word())
            .executes(c -> {
                Long v = amount(c, "amount");
                if (v != null && v > 0) Actions.record(kind, "", v);
                return 1;
            })
            .then(argument("who", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null && v > 0) Actions.record(kind, StringArgumentType.getString(c, "who"), v);
                return 1;
            })));
    }

    /** start "<item>" <minBid> [time]  (time defaults to 5m; a plain number means minutes) */
    private static LiteralArgumentBuilder<FabricClientCommandSource> auctionStartNode(String name, boolean legacySeconds) {
        return literal(name).then(argument("item", StringArgumentType.string())
            .then(argument("minBid", StringArgumentType.word())
                .executes(c -> {
                    Actions.auctionStartText(StringArgumentType.getString(c, "item"),
                            StringArgumentType.getString(c, "minBid"), "5m");
                    return 1;
                })
                .then(argument("time", StringArgumentType.word()).executes(c -> {
                    String time = StringArgumentType.getString(c, "time");
                    // The old /auctionstart took plain seconds; keep that for the legacy alias only.
                    if (legacySeconds && time.matches("\\d+")) time = time + "s";
                    Actions.auctionStartText(StringArgumentType.getString(c, "item"),
                            StringArgumentType.getString(c, "minBid"), time);
                    return 1;
                }))));
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d, CommandRegistryAccess access) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("ds");
        root.executes(c -> { Actions.openWindow(); return 1; });

        root.then(literal("help").executes(c -> { Actions.help(); return 1; }));
        root.then(literal("window").executes(c -> { Actions.openWindow(); return 1; }));
        root.then(literal("settings").executes(c -> { Actions.openSettings(); return 1; }));

        // manual entry, for anything the chat reader missed
        root.then(entry("in", Session.IN));
        root.then(entry("out", Session.OUT));
        root.then(entry("buy", Session.BUY));
        root.then(literal("sell").then(argument("amount", StringArgumentType.word()).executes(c -> {
            Long v = amount(c, "amount");
            if (v != null && v > 0) Actions.record(Session.SELL, "", v);
            return 1;
        })));

        root.then(literal("undo").executes(c -> { Actions.undo(); return 1; }));
        root.then(literal("reset").executes(c -> { Actions.requestReset(); return 1; }));
        root.then(literal("summary").executes(c -> { Actions.summary(); return 1; }));
        root.then(literal("players").executes(c -> { Actions.players(); return 1; }));
        root.then(literal("sound").executes(c -> { Actions.toggleSound(); return 1; }));
        root.then(literal("names").executes(c -> { Actions.toggleNames(); return 1; }));
        root.then(literal("doubles").executes(c -> { Actions.toggleDoubles(); return 1; }));
        root.then(literal("percent").executes(c -> { Actions.togglePercent(); return 1; }));
        root.then(literal("sells").executes(c -> { Actions.toggleSells(); return 1; }));
        root.then(literal("sellsource").executes(c -> { Actions.toggleSellSource(); return 1; }));

        root.then(literal("limit")
            .then(literal("off").executes(c -> { Actions.setLimit(0); return 1; }))
            .then(argument("amount", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null) Actions.setLimit(v);
                return 1;
            })));

        root.then(literal("goal")
            .then(literal("off").executes(c -> { Actions.setGoal(0); return 1; }))
            .then(argument("amount", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null) Actions.setGoal(v);
                return 1;
            })));

        root.then(literal("balance")
            .then(literal("off").executes(c -> { Actions.setBalance(-1); return 1; }))
            .then(argument("amount", StringArgumentType.word()).executes(c -> {
                Long v = amount(c, "amount");
                if (v != null) Actions.setBalance(v);
                return 1;
            })));

        root.then(literal("tilt")
            .then(literal("off").executes(c -> { Actions.setTilt(0); return 1; }))
            .then(argument("n", IntegerArgumentType.integer(2, 20)).executes(c -> {
                Actions.setTilt(IntegerArgumentType.getInteger(c, "n"));
                return 1;
            })));

        root.then(literal("rakeback")
            .then(literal("off").executes(c -> { Actions.setRakeback(0); return 1; }))
            .then(argument("pct", DoubleArgumentType.doubleArg(0.5, 50)).executes(c -> {
                Actions.setRakeback(DoubleArgumentType.getDouble(c, "pct"));
                return 1;
            })));

        LiteralArgumentBuilder<FabricClientCommandSource> hud = literal("hud");
        hud.then(literal("toggle").executes(c -> { Actions.toggleHud(); return 1; }));
        hud.then(literal("reset").executes(c -> { Actions.resetLayout(); return 1; }));
        hud.then(literal("scale").then(argument("n", FloatArgumentType.floatArg(0.5f, 3.0f)).executes(c -> {
            Actions.setHudScale(FloatArgumentType.getFloat(c, "n"));
            return 1;
        })));
        hud.then(literal("all")
            .then(literal("on").executes(c -> { Actions.setAllWidgets(true); return 1; }))
            .then(literal("off").executes(c -> { Actions.setAllWidgets(false); return 1; })));
        for (Widgets.W w : Widgets.W.values()) {
            hud.then(literal(w.id).executes(c -> { Actions.toggleWidget(w); return 1; }));
        }
        root.then(hud);

        // ---- auctions (DonutSMP) ----
        LiteralArgumentBuilder<FabricClientCommandSource> auction = literal("auction");
        auction.executes(c -> { Actions.auctionStatus(); return 1; });
        auction.then(auctionStartNode("start", false));
        auction.then(literal("stop").executes(c -> { Actions.auctionStop(); return 1; }));
        auction.then(literal("status").executes(c -> { Actions.auctionStatus(); return 1; }));
        auction.then(literal("browser").executes(c -> { Actions.openAuctionBrowser(); return 1; }));
        auction.then(literal("track").executes(c -> { Actions.toggleAuctionTracking(); return 1; }));
        auction.then(literal("anywhere").executes(c -> { Actions.toggleAuctionAnywhere(); return 1; }));
        auction.then(literal("delay").executes(c -> { Actions.toggleAuctionStreamDelay(); return 1; }));
        root.then(auction);

        // ---- music (YouTube / YouTube Music through the browser userscript) ----
        LiteralArgumentBuilder<FabricClientCommandSource> music = literal("music");
        music.executes(c -> { YtMusic.showStatus(); return 1; });
        music.then(literal("status").executes(c -> { YtMusic.showStatus(); return 1; }));
        music.then(literal("setup").executes(c -> { YtMusic.setup(); return 1; }));
        music.then(literal("play").executes(c -> { YtMusic.play(); return 1; }));
        music.then(literal("pause").executes(c -> { YtMusic.pause(); return 1; }));
        music.then(literal("toggle").executes(c -> { YtMusic.togglePlay(); return 1; }));
        music.then(literal("next").executes(c -> { YtMusic.next(); return 1; }));
        music.then(literal("prev").executes(c -> { YtMusic.previous(); return 1; }));
        music.then(literal("mute").executes(c -> { YtMusic.toggleTabMute(); return 1; }));
        music.then(literal("muteads").executes(c -> {
            Actions.bar(Formatting.GRAY, "Mute YouTube ads " + (YtMusic.toggleMuteAds() ? "on" : "off"));
            return 1;
        }));
        music.then(literal("port").then(argument("n", IntegerArgumentType.integer(1024, 65535)).executes(c -> {
            YtMusic.setPort(IntegerArgumentType.getInteger(c, "n"));
            Actions.chat(Formatting.GRAY, "[Music] Port set. Run /ds music setup and update the userscript in your browser.");
            return 1;
        })));
        music.then(literal("volume").then(argument("percent", IntegerArgumentType.integer(0, 100)).executes(c -> {
            YtMusic.volume(IntegerArgumentType.getInteger(c, "percent"));
            return 1;
        })));
        root.then(music);

        // ---- stream mode / nametag / watermark ----
        root.then(literal("stream")
            .executes(c -> { Actions.toggleStreamMode(); return 1; })
            .then(literal("coords").executes(c -> { Actions.toggleStreamCoords(); return 1; }))
            .then(literal("chatcoords").executes(c -> { Actions.toggleStreamChatCoords(); return 1; }))
            .then(literal("names").executes(c -> { Actions.toggleStreamNames(); return 1; }))
            .then(literal("status").executes(c -> { Actions.streamStatus(); return 1; })));
        root.then(literal("nametag").executes(c -> { Actions.toggleSelfNametag(); return 1; })
            .then(literal("rank").executes(c -> { Actions.toggleNametagRank(); return 1; })));
        root.then(literal("daily").executes(c -> { Actions.toggleDailyReset(); return 1; }));
        root.then(literal("preview").executes(c -> { Actions.toggleChatPreviews(); return 1; }));
        root.then(literal("new").executes(c -> { Actions.requestReset(); return 1; }));
        root.then(literal("watermark").executes(c -> { Actions.toggleWatermark(); return 1; }));

        // ---- Twitch / YouTube chat on screen ----
        LiteralArgumentBuilder<FabricClientCommandSource> streamChat = literal("chat");
        streamChat.executes(c -> { Actions.chatStatus(); return 1; });
        streamChat.then(literal("status").executes(c -> { Actions.chatStatus(); return 1; }));
        streamChat.then(literal("twitch")
            .then(literal("off").executes(c -> { Actions.chatTwitchOff(); return 1; }))
            .then(argument("channel", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatTwitch(StringArgumentType.getString(c, "channel"));
                return 1;
            })));
        streamChat.then(literal("youtube")
            .then(literal("off").executes(c -> { Actions.chatYouTubeOff(); return 1; }))
            .then(argument("video", StringArgumentType.greedyString()).executes(c -> {
                Actions.chatYouTube(StringArgumentType.getString(c, "video"));
                return 1;
            })));
        streamChat.then(literal("youtubekey")
            .then(literal("paste").executes(c -> { Actions.chatYouTubeKeyPaste(); return 1; }))
            .then(literal("clear").executes(c -> { Actions.chatYouTubeKeyClear(); return 1; }))
            .then(argument("key", StringArgumentType.word()).executes(c -> {
                Actions.chatYouTubeKey(StringArgumentType.getString(c, "key"));
                return 1;
            })));
        streamChat.then(literal("off").executes(c -> { Actions.chatOff(); return 1; }));
        streamChat.then(literal("clear").executes(c -> { Actions.chatClear(); return 1; }));
        streamChat.then(literal("commands").executes(c -> { Actions.chatToggleCommands(); return 1; }));
        root.then(streamChat);

        d.register(root);

        // Short aliases from the original auction mod.
        d.register(auctionStartNode("auctionstart", true));
        d.register(literal("auctionstop").executes(c -> { Actions.auctionStop(); return 1; }));
    }
}
